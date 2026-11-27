// Shared code for every endpoint. "private" = Twilio does not expose this file as a URL.
//
// Privacy rule for this whole backend: never console.log phone numbers, names,
// transcripts, summaries or audio. Only log endpoint names and HTTP status codes.

const crypto = require('crypto');

const EL_API = 'https://api.elevenlabs.io';
const EL_WS = 'wss://api.elevenlabs.io';
const TZ = 'America/New_York';

const HTTP_TIMEOUT_MS = 4000; // Twilio stops a function after 10 s
const LIVE_EL = new Set(['initiated', 'in-progress']);
// Twilio's call statuses (from its official SDK): queued, ringing, in-progress, completed, busy,
// failed, no-answer, canceled. Only these may be used as list filters, or Twilio rejects the request.
const LIVE_TWILIO = ['queued', 'ringing', 'in-progress'];
const CALL_SID = /^CA[0-9a-f]{32}$/i;
const SAFE_ID = /^[A-Za-z0-9_-]{1,100}$/;

// Swappable in tests.
const deps = {
  fetch: (...a) => fetch(...a),
  WebSocket: () => require('ws'),
  sleep: (ms) => new Promise((r) => setTimeout(r, ms)),
  now: () => Date.now(),
};

// ---------------------------------------------------------------- errors

class UpstreamError extends Error {
  constructor(message, { status = 502, permission = null, rateLimited = false } = {}) {
    super(message);
    this.httpStatus = status;
    this.permission = permission;
    this.rateLimited = rateLimited;
  }
}

// ---------------------------------------------------------------- responses + key

function jsonResponse(status, body) {
  const res = new Twilio.Response();
  res.setStatusCode(status);
  res.appendHeader('Content-Type', 'application/json');
  res.appendHeader('Cache-Control', 'no-store');
  res.setBody(body);
  return res;
}

function errorResponse(e) {
  if (e instanceof UpstreamError) {
    return jsonResponse(e.httpStatus, {
      ok: false,
      message: e.message,
      missingPermission: e.permission || undefined,
      rateLimited: e.rateLimited || undefined,
    });
  }
  return jsonResponse(500, { ok: false, message: 'Something went wrong on the server.' });
}

const sha256 = (t) => crypto.createHash('sha256').update(String(t), 'utf8').digest();

// Returns an error response, or null if the request has the right key.
// Note: the Twilio runtime copies URL/body parameters over event.request, so a caller
// can replace "headers" with anything. That's safe here because the value must still
// equal the secret key; we also insist on a single plain string (no lists or objects).
function checkAccess(context, event) {
  const expected = context.AGENT_CONTROL_KEY || '';
  if (expected.length < 32 || !context.ELEVENLABS_API_KEY) {
    // Say which one only in the private log, never to the caller.
    console.error(`config: ${expected.length < 32 ? 'AGENT_CONTROL_KEY missing/short' : 'ELEVENLABS_API_KEY missing'}`);
    return jsonResponse(500, { ok: false, message: 'Server is not set up. Run the deploy again (see README).' });
  }
  const request = event.request;
  const headers = request && typeof request === 'object' && request.headers && typeof request.headers === 'object' ? request.headers : {};
  const given = headers['x-agent-control-key'];
  if (typeof given !== 'string' || given.length === 0 || given.length > 512 ||
      !crypto.timingSafeEqual(sha256(given), sha256(expected))) {
    return jsonResponse(401, { ok: false, message: 'Missing or wrong access key.' });
  }
  return null;
}

// Wraps an endpoint: key check, then the handler, then a JSON reply.
function endpoint(fn) {
  return async function (context, event, callback) {
    const denied = checkAccess(context, event);
    if (denied) return callback(null, denied);
    try {
      const body = await fn(context, event);
      return callback(null, jsonResponse(body.httpStatus || 200, body));
    } catch (e) {
      console.error(`endpoint failed: ${e.httpStatus || 500}`); // no private data
      return callback(null, errorResponse(e));
    }
  };
}

// Request parameters must be plain strings. Anything else (objects, lists from JSON bodies
// or "a[b]=c" style URLs) is treated as missing.
function param(v) {
  return typeof v === 'string' && v.length <= 200 ? v.trim() : '';
}

// ---------------------------------------------------------------- small memory cache
// Lives as long as Twilio keeps the function warm (minutes). Best effort only.

const memory = new Map();
function cacheGet(key, maxAgeMs) {
  const hit = memory.get(key);
  if (!hit) return null;
  if (deps.now() - hit.at > maxAgeMs) return { value: hit.value, stale: true, at: hit.at };
  return { value: hit.value, stale: false, at: hit.at };
}
function cacheSet(key, value) {
  memory.set(key, { value, at: deps.now() });
  if (memory.size > 200) memory.delete(memory.keys().next().value);
}

// Serve from cache when fresh; refresh otherwise; on upstream trouble serve stale data.
async function cached(key, maxAgeMs, load) {
  const hit = cacheGet(key, maxAgeMs);
  if (hit && !hit.stale) return { ...hit.value, cachedAt: hit.at };
  try {
    const value = await load();
    cacheSet(key, value);
    return { ...value, cachedAt: deps.now() };
  } catch (e) {
    if (hit && (e.rateLimited || e.httpStatus >= 500)) return { ...hit.value, cachedAt: hit.at, stale: true };
    throw e;
  }
}

// ---------------------------------------------------------------- ElevenLabs

function missingPermissionFrom(text) {
  const m = /\b(convai|user|workspace|speech_history)_(read|write)\b/.exec(text || '');
  return m ? m[0] : null;
}

async function elFetch(context, path, { raw = false } = {}) {
  for (let attempt = 0; attempt < 2; attempt++) {
    const res = await deps.fetch(EL_API + path, {
      headers: { 'xi-api-key': context.ELEVENLABS_API_KEY },
      redirect: 'error',
      signal: AbortSignal.timeout(raw ? 8000 : HTTP_TIMEOUT_MS),
    });
    if (res.status === 429 && attempt === 0) {
      const wait = Math.min(2000, (Number(res.headers && res.headers.get && res.headers.get('retry-after')) || 1) * 1000);
      await deps.sleep(wait);
      continue;
    }
    if (res.status === 429) {
      throw new UpstreamError('ElevenLabs is rate-limiting requests. Try again in a minute.', { status: 503, rateLimited: true });
    }
    if (res.status === 401 || res.status === 403) {
      const text = await res.text().catch(() => '');
      const permission = missingPermissionFrom(text);
      throw new UpstreamError(
        permission
          ? `The ElevenLabs API key is missing the "${permission}" permission. See README, "ElevenLabs key permissions".`
          : 'ElevenLabs rejected the API key.',
        { status: 502, permission }
      );
    }
    if (res.status === 404) throw new UpstreamError('Not found in ElevenLabs.', { status: 404 });
    if (!res.ok) throw new UpstreamError(`ElevenLabs returned HTTP ${res.status}.`, { status: 502 });
    return raw ? res : res.json();
  }
}

async function listAgents(context) {
  return (await cached('agents', 60_000, async () => {
    const agents = [];
    let cursor = null;
    for (let page = 0; page < 10; page++) {
      const q = new URLSearchParams({ page_size: '100' });
      if (cursor) q.set('cursor', cursor);
      const data = await elFetch(context, `/v1/convai/agents?${q}`);
      for (const a of data.agents || []) agents.push({ id: a.agent_id, name: a.name });
      if (!data.has_more || !data.next_cursor) break;
      cursor = data.next_cursor;
    }
    agents.sort((a, b) => a.name.localeCompare(b.name));
    return { agents };
  })).agents;
}

// Conversations (summary form) that started in [fromUnix, toUnix). Newest first.
async function listConversations(context, { fromUnix, toUnix, agentId, maxPages = 10 }) {
  const out = [];
  let cursor = null;
  for (let page = 0; page < maxPages; page++) {
    const q = new URLSearchParams({ page_size: '100', call_start_after_unix: String(fromUnix) });
    if (toUnix) q.set('call_start_before_unix', String(toUnix));
    if (agentId) q.set('agent_id', agentId);
    if (cursor) q.set('cursor', cursor);
    const data = await elFetch(context, `/v1/convai/conversations?${q}`);
    for (const c of data.conversations || []) {
      if (SAFE_ID.test(c.conversation_id || '')) out.push(c);
    }
    if (!data.has_more || !data.next_cursor) break;
    cursor = data.next_cursor;
  }
  return out;
}

async function getConversation(context, conversationId) {
  if (!SAFE_ID.test(conversationId)) throw new UpstreamError('Bad conversation id.', { status: 400 });
  return elFetch(context, `/v1/convai/conversations/${conversationId}`);
}

// The Twilio call SID and numbers stored on a conversation.
// Field names checked against a real call and the ElevenLabs SDK
// (ConversationHistoryTwilioPhoneCallModel): metadata.phone_call.call_sid,
// agent_number, external_number, direction. Backup: dynamic variable system__call_sid.
function phoneInfo(conv) {
  const pc = (conv.metadata && conv.metadata.phone_call) || {};
  const dyn = (conv.conversation_initiation_client_data && conv.conversation_initiation_client_data.dynamic_variables) || {};
  const sid = pc.call_sid || dyn.system__call_sid || null;
  return {
    callSid: sid && CALL_SID.test(sid) ? sid : null,
    direction: pc.direction || null,
    agentNumber: pc.agent_number || null,
    externalNumber: pc.external_number || null,
    startUnix: (conv.metadata && conv.metadata.start_time_unix_secs) || null,
  };
}

// ---------------------------------------------------------------- durable store (Twilio Sync)
// Remembers (1) which conversation belongs to which Twilio call SID, and
// (2) which calls YOU hung up. Uses the Sync service named in SYNC_SERVICE_SID
// (the deploy workflow creates it). If Sync is missing, falls back to memory.

const SYNC_MAPS = { index: 'agentcontrol_conv_index', ended: 'agentcontrol_ended_by_me' };
const syncMemory = { index: null, ended: null };

function syncMap(context, which) {
  if (!context.SYNC_SERVICE_SID) return null;
  return context.getTwilioClient().sync.v1.services(context.SYNC_SERVICE_SID).syncMaps(SYNC_MAPS[which]);
}

async function ensureSyncMap(context, which) {
  try {
    await context.getTwilioClient().sync.v1.services(context.SYNC_SERVICE_SID).syncMaps.create({ uniqueName: SYNC_MAPS[which] });
  } catch (e) {
    if (e.code !== 54301) throw e; // 54301 = already exists
  }
}

async function loadSyncMap(context, which) {
  if (syncMemory[which] && deps.now() - syncMemory[which].at < 5 * 60_000) return syncMemory[which].map;
  const map = new Map();
  const m = syncMap(context, which);
  if (m) {
    try {
      const items = await m.syncMapItems.list({ pageSize: 100, limit: 5000 });
      for (const it of items) map.set(it.key, it.data);
    } catch (e) {
      if (e.status !== 404) console.error(`sync read failed: ${e.status || ''}`);
    }
  }
  // Keep anything we already know in memory.
  if (syncMemory[which]) for (const [k, v] of syncMemory[which].map) if (!map.has(k)) map.set(k, v);
  syncMemory[which] = { map, at: deps.now() };
  return map;
}

async function remember(context, which, key, data, ttlDays = 400) {
  const map = await loadSyncMap(context, which);
  if (map.has(key)) return;
  map.set(key, data);
  const m = syncMap(context, which);
  if (!m) return;
  try {
    await m.syncMapItems.create({ key, data, itemTtl: ttlDays * 86400 });
  } catch (e) {
    if (e.status === 404) {
      try { await ensureSyncMap(context, which); await m.syncMapItems.create({ key, data, itemTtl: ttlDays * 86400 }); } catch (_) {}
    } else if (e.code !== 54208) { // 54208 = key already exists
      console.error(`sync write failed: ${e.status || ''}`);
    }
  }
}

// ---------------------------------------------------------------- Twilio

function twilioError(e) {
  return e && e.code ? `Twilio error ${e.code}` : 'Twilio request failed';
}

function callFromTwilio(c) {
  return {
    sid: c.sid,
    status: c.status,
    direction: c.direction,
    from: c.from,
    to: c.to,
    dateCreated: c.dateCreated ? new Date(c.dateCreated).getTime() / 1000 : null,
    startTime: c.startTime ? new Date(c.startTime).getTime() / 1000 : null,
    endTime: c.endTime ? new Date(c.endTime).getTime() / 1000 : null,
    duration: c.duration != null && c.duration !== '' ? Number(c.duration) : null,
    price: c.price != null && c.price !== '' ? Math.abs(Number(c.price)) : null,
    priceUnit: c.priceUnit || 'USD',
  };
}

async function listLiveTwilioCalls(client, filter = {}) {
  const lists = await Promise.all(LIVE_TWILIO.map((status) => client.calls.list({ ...filter, status, limit: 200 })));
  return lists.flat();
}

async function endTwilioCall(context, client, call) {
  const base = { callSid: call.sid, statusBefore: call.status };
  if (!LIVE_TWILIO.includes(call.status)) return { ...base, ended: false, alreadyOver: true };
  const newStatus = call.status === 'in-progress' ? 'completed' : 'canceled';
  try {
    await client.calls(call.sid).update({ status: newStatus });
    await remember(context, 'ended', call.sid, { at: Math.floor(deps.now() / 1000) }, 400);
    return { ...base, ended: true, statusAfter: newStatus };
  } catch (e) {
    return { ...base, ended: false, error: twilioError(e) };
  }
}

async function endTwilioCallBySid(context, client, sid) {
  try {
    return await endTwilioCall(context, client, await client.calls(sid).fetch());
  } catch (e) {
    return { callSid: sid, ended: false, error: twilioError(e) };
  }
}

// Last resort: ask ElevenLabs to end a conversation (monitoring WebSocket, "end_call").
// Not in ElevenLabs' public SDK, so it may not work everywhere. We re-check afterwards.
async function endOnElevenLabs(context, conversationId) {
  if (!SAFE_ID.test(conversationId)) return { conversationId, ended: false, error: 'bad id' };
  const WebSocket = deps.WebSocket();
  const sent = await new Promise((resolve) => {
    let done = false;
    const finish = (r) => { if (done) return; done = true; clearTimeout(timer); try { ws.terminate(); } catch (_) {} resolve(r); };
    const ws = new WebSocket(`${EL_WS}/v1/convai/conversations/${conversationId}/monitor`, {
      headers: { 'xi-api-key': context.ELEVENLABS_API_KEY },
    });
    const timer = setTimeout(() => finish({ sent: false, error: 'timed out' }), 3500);
    ws.on('open', () => ws.send(JSON.stringify({ command_type: 'end_call' }), (err) => finish(err ? { sent: false, error: err.message } : { sent: true })));
    ws.on('unexpected-response', (_q, res) => finish({ sent: false, error: `HTTP ${res.statusCode}` }));
    ws.on('error', (err) => finish({ sent: false, error: err.message }));
  });
  if (!sent.sent) return { conversationId, ended: false, error: sent.error };
  await deps.sleep(1500);
  try {
    const c = await getConversation(context, conversationId);
    return { conversationId, ended: !LIVE_EL.has(c.status), statusAfter: c.status };
  } catch (e) {
    return { conversationId, ended: false, error: 'sent end_call, could not confirm' };
  }
}

// ---------------------------------------------------------------- time (Eastern)

const easternParts = (unix) => {
  const f = new Intl.DateTimeFormat('en-CA', {
    timeZone: TZ, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', hourCycle: 'h23', weekday: 'short',
  }).formatToParts(new Date(unix * 1000));
  const g = (t) => f.find((p) => p.type === t).value;
  return { date: `${g('year')}-${g('month')}-${g('day')}`, hour: Number(g('hour')), weekday: g('weekday') };
};

// Unix time of midnight (Eastern) at the start of YYYY-MM-DD.
function easternMidnight(dateStr) {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(dateStr || '');
  if (!m) return null;
  for (const offset of [4, 5]) {
    const t = Date.UTC(+m[1], +m[2] - 1, +m[3], offset) / 1000;
    const p = easternParts(t);
    if (p.date === dateStr && p.hour === 0) return t;
  }
  return null;
}

// ISO week start (Monday), in Eastern dates.
function weekStart(dateStr) {
  const [y, mo, d] = dateStr.split('-').map(Number);
  const dt = new Date(Date.UTC(y, mo - 1, d));
  const back = (dt.getUTCDay() + 6) % 7;
  dt.setUTCDate(dt.getUTCDate() - back);
  return dt.toISOString().slice(0, 10);
}

// ---------------------------------------------------------------- history (shared by /calls and /analytics)

const OUTCOMES = ['completed', 'no_answer', 'busy', 'failed', 'ended_by_you', 'in_progress'];

function outcomeOf(call, endedByMe) {
  if (LIVE_TWILIO.includes(call.status)) return 'in_progress';
  if (endedByMe) return 'ended_by_you';
  switch (call.status) {
    case 'completed': return call.duration > 0 ? 'completed' : 'no_answer';
    case 'busy': return 'busy';
    case 'failed': return 'failed';
    default: return 'no_answer'; // no-answer, canceled
  }
}

// How long it rang: from dialing until answer. Twilio has no "answered at" field,
// so this is (end - start) - duration. Estimate, +-1 s.
function ringSeconds(call) {
  if (!call.startTime) return null;
  if (call.endTime && call.duration != null) return Math.max(0, Math.round(call.endTime - call.startTime - call.duration));
  if (call.endTime) return Math.max(0, Math.round(call.endTime - call.startTime)); // never answered
  return null;
}

// Pairs ElevenLabs conversations with Twilio calls.
// 1) exact: by the call SID stored on the conversation (remembered in the index);
// 2) otherwise by time: the conversation starting closest to (and not long before) the call.
function matchConversations(calls, conversations, index) {
  const bySid = new Map();
  const unmatched = [];
  for (const c of conversations) {
    const known = index.get(c.conversation_id);
    if (known && known.callSid) bySid.set(known.callSid, c);
    else unmatched.push(c);
  }
  const used = new Set();
  const result = new Map();
  for (const call of calls) {
    const exact = bySid.get(call.sid);
    if (exact) { result.set(call.sid, { conv: exact, matchedBy: 'call_sid' }); used.add(exact.conversation_id); }
  }
  const pending = calls.filter((c) => !result.has(c.sid) && c.startTime)
    .sort((a, b) => a.startTime - b.startTime);
  for (const call of pending) {
    const end = call.endTime || call.startTime + 3600;
    let best = null;
    for (const c of unmatched) {
      if (used.has(c.conversation_id)) continue;
      const t = c.start_time_unix_secs;
      if (t < call.startTime - 10 || t > end + 5) continue;
      const gap = Math.abs(t - call.startTime);
      if (!best || gap < best.gap) best = { c, gap };
    }
    if (best) { used.add(best.c.conversation_id); result.set(call.sid, { conv: best.c, matchedBy: 'time' }); }
  }
  return result;
}

// Fetches details for a few unknown conversations so the exact index keeps growing.
async function growIndex(context, conversations, index, budget = 8) {
  const todo = conversations.filter((c) => !index.has(c.conversation_id) && !LIVE_EL.has(c.status)).slice(0, budget);
  for (let i = 0; i < todo.length; i += 4) {
    await Promise.all(todo.slice(i, i + 4).map(async (c) => {
      try {
        const info = phoneInfo(await getConversation(context, c.conversation_id));
        await remember(context, 'index', c.conversation_id, { callSid: info.callSid });
      } catch (e) {
        if (e.rateLimited) throw e;
      }
    }));
  }
}

const MAX_RANGE_DAYS = 92;

function parseRange(event) {
  const today = easternParts(deps.now() / 1000).date;
  const toDate = /^\d{4}-\d{2}-\d{2}$/.test(param(event.to)) ? param(event.to) : today;
  let fromDate = /^\d{4}-\d{2}-\d{2}$/.test(param(event.from)) ? param(event.from) : null;
  const toUnix = easternMidnight(toDate) + 86400 + 3600; // end of that day (+1h covers DST days)
  let fromUnix = fromDate ? easternMidnight(fromDate) : toUnix - 31 * 86400;
  if (fromUnix == null || toUnix == null) throw new UpstreamError('Dates must look like 2026-11-27.', { status: 400 });
  if (toUnix - fromUnix > MAX_RANGE_DAYS * 86400) fromUnix = toUnix - MAX_RANGE_DAYS * 86400;
  return { fromUnix, toUnix, fromDate: easternParts(fromUnix).date, toDate };
}

async function loadHistory(context, { fromUnix, toUnix }) {
  const key = `history:${fromUnix}:${toUnix}`;
  return cached(key, 60_000, async () => {
    const client = context.getTwilioClient();
    const [twilioCalls, conversations, agents, index, ended] = await Promise.all([
      client.calls.list({ startTimeAfter: new Date(fromUnix * 1000), startTimeBefore: new Date(toUnix * 1000), limit: 1000 }),
      listConversations(context, { fromUnix: fromUnix - 60, toUnix }),
      listAgents(context).catch(() => []),
      loadSyncMap(context, 'index'),
      loadSyncMap(context, 'ended'),
    ]);
    await growIndex(context, conversations, index).catch(() => {});
    const agentNames = new Map(agents.map((a) => [a.id, a.name]));
    const calls = twilioCalls.map(callFromTwilio).filter((c) => !/^trunking/.test(c.direction || ''));
    const matches = matchConversations(calls, conversations, index);

    const items = calls.map((call) => {
      const m = matches.get(call.sid);
      const conv = m && m.conv;
      const outbound = (call.direction || '').startsWith('outbound');
      return {
        callSid: call.sid,
        conversationId: conv ? conv.conversation_id : null,
        agentId: conv ? conv.agent_id : null,
        agentName: conv ? (conv.agent_name || agentNames.get(conv.agent_id) || 'Unknown agent') : 'Unknown agent',
        matchedBy: m ? m.matchedBy : null,
        direction: outbound ? 'outbound' : 'inbound',
        number: outbound ? call.to : call.from,
        startTime: call.startTime || call.dateCreated,
        ringSecs: ringSeconds(call),
        talkSecs: call.duration,
        twilioStatus: call.status,
        outcome: outcomeOf(call, ended.has(call.sid)),
        credits: conv && conv.cost != null ? conv.cost : null,
        elevenUsd: conv && conv.cost_fiat != null ? conv.cost_fiat : null,
        twilioUsd: call.price,
        twilioPricePending: call.price == null && !LIVE_TWILIO.includes(call.status),
        title: conv ? conv.call_summary_title || null : null,
      };
    }).sort((a, b) => (b.startTime || 0) - (a.startTime || 0));

    return { items, truncated: twilioCalls.length >= 1000 };
  });
}

module.exports = {
  deps, param, UpstreamError, jsonResponse, errorResponse, checkAccess, endpoint,
  cached, cacheGet, cacheSet, memory,
  elFetch, listAgents, listConversations, getConversation, phoneInfo, missingPermissionFrom,
  loadSyncMap, remember, syncMemory,
  callFromTwilio, listLiveTwilioCalls, endTwilioCall, endTwilioCallBySid, endOnElevenLabs, twilioError,
  easternParts, easternMidnight, weekStart, parseRange, loadHistory, matchConversations, outcomeOf, ringSeconds,
  OUTCOMES, LIVE_EL, LIVE_TWILIO, CALL_SID, SAFE_ID,
};
