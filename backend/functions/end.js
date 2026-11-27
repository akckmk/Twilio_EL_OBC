// GET /end?agent=<agent_id>  ends only that agent's live calls.
// GET /end?agent=all         ends every live call in the Twilio account.
// GET /end?call=<CallSid>    ends one specific call (used by the Live screen).
const lib = () => require(Runtime.getFunctions()['common'].path);
const plural = (n, w) => `${n} ${w}${n === 1 ? '' : 's'}`;

exports.handler = (context, event, callback) =>
  lib().endpoint(async (ctx, ev) => {
    const L = lib();
    const client = ctx.getTwilioClient();
    const callSid = L.param(ev.call);
    const agent = L.param(ev.agent);

    if (callSid) {
      if (!L.CALL_SID.test(callSid)) return { httpStatus: 400, ok: false, message: 'That call id does not look right.' };
      const r = await L.endTwilioCallBySid(ctx, client, callSid);
      const message = r.ended ? 'Ended the call.' : r.alreadyOver ? 'That call had already ended.' : 'Could not end that call.';
      return { ok: r.ended || !!r.alreadyOver, message, calls: [r] };
    }
    if (!agent) return { httpStatus: 400, ok: false, message: 'Add ?agent=<agent_id>, ?agent=all or ?call=<CallSid>.' };
    if (agent !== 'all' && !L.SAFE_ID.test(agent)) return { httpStatus: 400, ok: false, message: 'That agent id does not look right.' };
    return agent === 'all' ? endAll(L, ctx, client) : endForAgent(L, ctx, client, agent);
  })(context, event, callback);

async function endAll(L, ctx, client) {
  const calls = await L.listLiveTwilioCalls(client);
  const results = await Promise.all(calls.map((c) => L.endTwilioCall(ctx, client, c)));
  const ended = results.filter((r) => r.ended).length;
  const failed = results.filter((r) => !r.ended && !r.alreadyOver).length;
  let message = ended ? `Ended ${plural(ended, 'call')} (all agents).` : 'No live calls to end.';
  if (failed) message += ` Could not end ${failed}; see details.`;
  return { ok: failed === 0, agent: 'all', message, endedCount: ended, calls: results };
}

async function endForAgent(L, ctx, client, agentId) {
  let name;
  try {
    name = (await L.elFetch(ctx, `/v1/convai/agents/${agentId}`)).name || agentId;
  } catch (e) {
    if (e.httpStatus === 404) return { httpStatus: 404, ok: false, message: `No ElevenLabs agent with id ${agentId}.` };
    throw e;
  }
  const now = Math.floor(L.deps.now() / 1000);
  const live = (await L.listConversations(ctx, { fromUnix: now - 6 * 3600, agentId, maxPages: 3 }))
    .filter((c) => L.LIVE_EL.has(c.status));
  const handled = new Set();
  const conversations = [];
  for (const c of live) conversations.push(await endOne(L, ctx, client, c.conversation_id, handled));

  const total = conversations.filter((c) => c.ended).length;
  const failed = conversations.filter((c) => !c.ended && !c.alreadyOver).length;
  let message = total ? `Ended ${plural(total, 'call')} for ${name}.` : `No live calls found for ${name}.`;
  if (failed) message += ` Could not end ${failed}; see details.`;
  return { ok: failed === 0, agent: { id: agentId, name }, message, endedCount: total, conversations };
}

async function endOne(L, ctx, client, conversationId, handled) {
  const out = { conversationId, ended: false, method: null, calls: [] };
  let info = {};
  try { info = L.phoneInfo(await L.getConversation(ctx, conversationId)); } catch (_) {}
  if (info.callSid) await L.remember(ctx, 'index', conversationId, { callSid: info.callSid });

  // 1) The Twilio call SID ElevenLabs stored.
  if (info.callSid && !handled.has(info.callSid)) {
    handled.add(info.callSid);
    const r = await L.endTwilioCallBySid(ctx, client, info.callSid);
    out.calls.push(r);
    if (r.ended) return { ...out, ended: true, method: 'twilio_call_sid' };
    if (r.alreadyOver) return { ...out, alreadyOver: true };
  }
  // 2) The live Twilio call between the two phone numbers.
  if (info.agentNumber && info.externalNumber) {
    const inbound = info.direction === 'inbound';
    const filter = inbound ? { from: info.externalNumber, to: info.agentNumber } : { from: info.agentNumber, to: info.externalNumber };
    try {
      for (const c of await L.listLiveTwilioCalls(client, filter)) {
        if (handled.has(c.sid)) continue;
        handled.add(c.sid);
        out.calls.push(await L.endTwilioCall(ctx, client, c));
      }
      if (out.calls.some((r) => r.ended)) return { ...out, ended: true, method: 'twilio_number_match' };
    } catch (_) {}
  }
  // 3) Last resort: ElevenLabs end_call.
  out.elevenLabs = await L.endOnElevenLabs(ctx, conversationId);
  if (out.elevenLabs.ended) return { ...out, ended: true, method: 'elevenlabs_end_call' };
  return out;
}
