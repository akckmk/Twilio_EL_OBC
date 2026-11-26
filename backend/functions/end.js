// GET /end?agent=<agent_id>  ends only that agent's live calls.
// GET /end?agent=all         ends every live call in the Twilio account.

exports.handler = async function (context, event, callback) {
  const lib = require(Runtime.getFunctions()['common'].path);
  const denied = lib.checkAccess(context, event);
  if (denied) return callback(null, denied);

  const agent = String(event.agent || '').trim();
  if (!agent) {
    return callback(null, lib.jsonResponse(400, { ok: false, message: 'Add ?agent=<agent_id> or ?agent=all.' }));
  }
  if (agent !== 'all' && !/^[A-Za-z0-9_-]{1,100}$/.test(agent)) {
    return callback(null, lib.jsonResponse(400, { ok: false, message: 'That agent id does not look right.' }));
  }

  try {
    const client = context.getTwilioClient();
    const body = agent === 'all' ? await endAll(lib, client) : await endForAgent(lib, context, client, agent);
    return callback(null, lib.jsonResponse(body.status || 200, body));
  } catch (e) {
    console.error('end failed:', e.message);
    return callback(null, lib.jsonResponse(502, { ok: false, message: `Something went wrong: ${e.message}` }));
  }
};

async function endAll(lib, client) {
  const calls = await lib.listLiveTwilioCalls(client);
  const results = await Promise.all(calls.map((c) => lib.endFetchedCall(client, c)));
  const ended = results.filter((r) => r.ended);
  const failed = results.filter((r) => !r.ended && !r.alreadyOver);
  let message = ended.length
    ? `Ended ${lib.plural(ended.length, 'call')} (all agents).`
    : 'No live calls to end.';
  if (failed.length) message += ` Could not end ${failed.length}; see details.`;
  return { ok: failed.length === 0, agent: 'all', message, endedCount: ended.length, calls: results };
}

async function endForAgent(lib, context, client, agentId) {
  const [name, conversationIds] = await Promise.all([
    lib.getAgentName(context, agentId),
    lib.listLiveConversations(context, agentId),
  ]);
  if (name === null) {
    return { status: 404, ok: false, agent: { id: agentId }, message: `No ElevenLabs agent with id ${agentId}.` };
  }

  const handledSids = new Set();
  const conversations = await Promise.all(
    conversationIds.map((id) => endOneConversation(lib, context, client, id, handledSids))
  );

  const twilioEnded = conversations.flatMap((c) => c.calls.filter((r) => r.ended));
  const elEnded = conversations.filter((c) => c.elevenLabs && c.elevenLabs.ended);
  const failed = conversations.filter((c) => !c.ended && !c.alreadyOver);
  const total = twilioEnded.length + elEnded.length;

  let message = total
    ? `Ended ${lib.plural(total, 'call')} for ${name}.`
    : `No live calls found for ${name}.`;
  if (failed.length) message += ` Could not end ${failed.length}; see details.`;

  return {
    ok: failed.length === 0,
    agent: { id: agentId, name },
    message,
    endedCount: total,
    conversations,
  };
}

async function endOneConversation(lib, context, client, conversationId, handledSids) {
  const out = { conversationId, ended: false, method: null, calls: [], notes: [] };
  let info;
  try {
    info = await lib.getConversationCallInfo(context, conversationId);
  } catch (e) {
    out.notes.push(`Could not read the conversation: ${e.message}`);
    info = { conversationId };
  }
  out.callSid = info.callSid || null;

  // 1) Best route: the Twilio call SID that ElevenLabs stored.
  if (info.callSid && !handledSids.has(info.callSid)) {
    handledSids.add(info.callSid);
    const r = await lib.endTwilioCall(client, info.callSid);
    out.calls.push(r);
    if (r.ended) return { ...out, ended: true, method: 'twilio_call_sid' };
    if (r.alreadyOver) {
      out.alreadyOver = true;
      out.notes.push('Twilio says this call is already over.');
      return out;
    }
    out.notes.push(`Twilio could not end call ${info.callSid}: ${r.error}`);
  } else if (!info.callSid) {
    out.notes.push('No Twilio call SID on this conversation yet.');
  }

  // 2) Backup: find the live Twilio call by its two phone numbers.
  if (info.agentNumber && info.externalNumber) {
    const inbound = info.direction === 'inbound';
    const filter = inbound
      ? { from: info.externalNumber, to: info.agentNumber }
      : { from: info.agentNumber, to: info.externalNumber };
    try {
      const matches = (await lib.listLiveTwilioCalls(client, filter)).filter((c) => !handledSids.has(c.sid));
      for (const c of matches) {
        handledSids.add(c.sid);
        out.calls.push(await lib.endFetchedCall(client, c));
      }
      if (out.calls.some((r) => r.ended)) return { ...out, ended: true, method: 'twilio_number_match' };
      if (!matches.length) out.notes.push('No live Twilio call between these two numbers.');
    } catch (e) {
      out.notes.push(`Twilio number search failed: ${e.message}`);
    }
  }

  // 3) Last resort: ask ElevenLabs to end the conversation itself.
  out.elevenLabs = await lib.endOnElevenLabs(context, conversationId);
  if (out.elevenLabs.ended) return { ...out, ended: true, method: 'elevenlabs_end_call' };
  out.notes.push(
    `ElevenLabs end_call did not work: ${out.elevenLabs.error || `status is still ${out.elevenLabs.statusAfter}`}`
  );
  return out;
}
