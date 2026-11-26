// Shared helpers for /agents and /end. "private" means Twilio does not expose
// this file as a URL; the other functions load it with Runtime.getFunctions().

const crypto = require('crypto');

const EL_API = 'https://api.elevenlabs.io';
const EL_WS = 'wss://api.elevenlabs.io';

// Twilio Functions stop after 10 seconds, so every outside call gets a short timeout.
const HTTP_TIMEOUT_MS = 4000;

// ElevenLabs conversation statuses that mean "the call may still be live".
const LIVE_EL_STATUSES = new Set(['initiated', 'in-progress']);

// Twilio call statuses that mean "the call is not over yet".
const LIVE_TWILIO_STATUSES = ['in-progress', 'ringing', 'queued'];

// Swappable so the tests can run without the network.
const deps = {
  fetch: (...args) => fetch(...args),
  WebSocket: () => require('ws'),
  sleep: (ms) => new Promise((r) => setTimeout(r, ms)),
};

// ---------- responses and the access key ----------

function jsonResponse(status, body) {
  const res = new Twilio.Response();
  res.setStatusCode(status);
  res.appendHeader('Content-Type', 'application/json');
  res.appendHeader('Cache-Control', 'no-store');
  res.setBody(body);
  return res;
}

function sha256(text) {
  return crypto.createHash('sha256').update(String(text), 'utf8').digest();
}

// Returns an error response, or null if the request carries the right key.
// The key must come in the "X-Agent-Control-Key" header (never in the URL,
// so it doesn't end up in logs).
function checkAccess(context, event) {
  const expected = context.AGENT_CONTROL_KEY || '';
  if (expected.length < 32) {
    return jsonResponse(500, {
      ok: false,
      message: 'Server is not set up: AGENT_CONTROL_KEY is missing or shorter than 32 characters.',
    });
  }
  if (!context.ELEVENLABS_API_KEY) {
    return jsonResponse(500, { ok: false, message: 'Server is not set up: ELEVENLABS_API_KEY is missing.' });
  }
  const headers = (event.request && event.request.headers) || {};
  const given = headers['x-agent-control-key'] || '';
  // Compare hashes so the check takes the same time whatever was sent.
  if (!given || !crypto.timingSafeEqual(sha256(given), sha256(expected))) {
    return jsonResponse(401, { ok: false, message: 'Missing or wrong access key.' });
  }
  return null;
}

// ---------- ElevenLabs ----------

async function elGet(context, path) {
  const res = await deps.fetch(EL_API + path, {
    headers: { 'xi-api-key': context.ELEVENLABS_API_KEY },
    signal: AbortSignal.timeout(HTTP_TIMEOUT_MS),
  });
  if (!res.ok) {
    const err = new Error(`ElevenLabs ${path.split('?')[0]} returned HTTP ${res.status}`);
    err.status = res.status;
    throw err;
  }
  return res.json();
}

// All agents in the workspace, as [{ id, name }], sorted by name.
async function listAgents(context) {
  const agents = [];
  let cursor = null;
  for (let page = 0; page < 10; page++) {
    const q = new URLSearchParams({ page_size: '100' });
    if (cursor) q.set('cursor', cursor);
    const data = await elGet(context, `/v1/convai/agents?${q}`);
    for (const a of data.agents || []) agents.push({ id: a.agent_id, name: a.name });
    if (!data.has_more || !data.next_cursor) break;
    cursor = data.next_cursor;
  }
  agents.sort((a, b) => a.name.localeCompare(b.name));
  return agents;
}

async function getAgentName(context, agentId) {
  try {
    const a = await elGet(context, `/v1/convai/agents/${encodeURIComponent(agentId)}`);
    return a.name || agentId;
  } catch (e) {
    if (e.status === 404) return null;
    return agentId; // name lookup failed for another reason; carry on with the id
  }
}

// The agent's conversations that are still live (started in the last 6 hours).
async function listLiveConversations(context, agentId) {
  const since = Math.floor(Date.now() / 1000) - 6 * 3600;
  const live = [];
  let cursor = null;
  for (let page = 0; page < 3; page++) {
    const q = new URLSearchParams({
      agent_id: agentId,
      page_size: '100',
      call_start_after_unix: String(since),
    });
    if (cursor) q.set('cursor', cursor);
    const data = await elGet(context, `/v1/convai/conversations?${q}`);
    for (const c of data.conversations || []) {
      if (LIVE_EL_STATUSES.has(c.status)) live.push(c.conversation_id);
    }
    if (!data.has_more || !data.next_cursor) break;
    cursor = data.next_cursor;
  }
  return live;
}

// Reads the Twilio call SID and phone numbers out of one conversation.
// Field names checked against a real call: metadata.phone_call.call_sid
// (phone_call.type is "twilio"); the same SID is also in the dynamic
// variable system__call_sid, used here as a backup.
async function getConversationCallInfo(context, conversationId) {
  const c = await elGet(context, `/v1/convai/conversations/${encodeURIComponent(conversationId)}`);
  const pc = (c.metadata && c.metadata.phone_call) || {};
  const dyn =
    (c.conversation_initiation_client_data && c.conversation_initiation_client_data.dynamic_variables) || {};
  let callSid = pc.call_sid || dyn.system__call_sid || null;
  if (callSid && !/^CA[0-9a-f]{32}$/i.test(callSid)) callSid = null; // not a Twilio call SID
  return {
    conversationId,
    status: c.status,
    callSid,
    phoneType: pc.type || null,
    direction: pc.direction || null,
    agentNumber: pc.agent_number || null,
    externalNumber: pc.external_number || null,
  };
}

// Backup: ask ElevenLabs itself to end the conversation, using its
// real-time monitoring WebSocket and the "end_call" control command.
// This is NOT in ElevenLabs' public SDK, so it may not work on every
// account. We then re-check the conversation status to see if it worked.
async function endOnElevenLabs(context, conversationId) {
  const WebSocket = deps.WebSocket();
  const sent = await new Promise((resolve) => {
    let done = false;
    const finish = (result) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      try { ws.terminate(); } catch (_) {}
      resolve(result);
    };
    const ws = new WebSocket(
      `${EL_WS}/v1/convai/conversations/${encodeURIComponent(conversationId)}/monitor`,
      { headers: { 'xi-api-key': context.ELEVENLABS_API_KEY } }
    );
    const timer = setTimeout(() => finish({ sent: false, error: 'timed out' }), 3500);
    ws.on('open', () => {
      ws.send(JSON.stringify({ command_type: 'end_call' }), (err) =>
        finish(err ? { sent: false, error: err.message } : { sent: true })
      );
    });
    ws.on('unexpected-response', (_req, res) => finish({ sent: false, error: `HTTP ${res.statusCode}` }));
    ws.on('error', (err) => finish({ sent: false, error: err.message }));
  });
  if (!sent.sent) return { conversationId, ended: false, error: sent.error };

  await deps.sleep(1500);
  try {
    const c = await elGet(context, `/v1/convai/conversations/${encodeURIComponent(conversationId)}`);
    const ended = !LIVE_EL_STATUSES.has(c.status);
    return { conversationId, ended, statusAfter: c.status };
  } catch (e) {
    return { conversationId, ended: false, error: `sent end_call, could not confirm: ${e.message}` };
  }
}

// ---------- Twilio ----------

// Ends one Twilio call if it is still live. Returns a result object.
async function endTwilioCall(client, callSid) {
  try {
    const call = await client.calls(callSid).fetch();
    return await endFetchedCall(client, call);
  } catch (e) {
    return { callSid, ended: false, error: twilioError(e) };
  }
}

async function endFetchedCall(client, call) {
  const base = { callSid: call.sid, from: call.from, to: call.to, statusBefore: call.status };
  if (!LIVE_TWILIO_STATUSES.includes(call.status)) {
    return { ...base, ended: false, alreadyOver: true };
  }
  // In-progress calls are hung up with "completed"; ringing or queued ones with "canceled".
  const newStatus = call.status === 'in-progress' ? 'completed' : 'canceled';
  try {
    await client.calls(call.sid).update({ status: newStatus });
    return { ...base, ended: true, statusAfter: newStatus };
  } catch (e) {
    return { ...base, ended: false, error: twilioError(e) };
  }
}

// Every live call in the Twilio account.
async function listLiveTwilioCalls(client, filter = {}) {
  const lists = await Promise.all(
    LIVE_TWILIO_STATUSES.map((status) => client.calls.list({ ...filter, status, limit: 500 }))
  );
  return lists.flat();
}

function twilioError(e) {
  return e && e.code ? `Twilio error ${e.code}: ${e.message}` : String((e && e.message) || e);
}

function plural(n, word) {
  return `${n} ${word}${n === 1 ? '' : 's'}`;
}

module.exports = {
  deps,
  jsonResponse,
  checkAccess,
  listAgents,
  getAgentName,
  listLiveConversations,
  getConversationCallInfo,
  endOnElevenLabs,
  endTwilioCall,
  endFetchedCall,
  listLiveTwilioCalls,
  plural,
};
