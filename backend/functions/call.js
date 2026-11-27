// GET /call?id=<CallSid>[&conversation=<conversation_id>]
// One call's details: timing, outcome, costs, and from ElevenLabs the summary,
// full transcript and analysis/evaluation results. Private: never logged.
const lib = () => require(Runtime.getFunctions()['common'].path);

exports.handler = (context, event, callback) =>
  lib().endpoint(async (ctx, ev) => {
    const L = lib();
    const sid = String(ev.id || '');
    const convParam = String(ev.conversation || '');
    if (!L.CALL_SID.test(sid)) return { httpStatus: 400, ok: false, message: 'Add ?id=<CallSid>.' };
    if (convParam && !L.SAFE_ID.test(convParam)) return { httpStatus: 400, ok: false, message: 'Bad conversation id.' };
    return L.cached(`call:${sid}:${convParam}`, 10 * 60_000, () => loadCall(L, ctx, sid, convParam));
  })(context, event, callback);

async function loadCall(L, ctx, sid, convParam) {
  const client = ctx.getTwilioClient();
  let raw;
  try { raw = await client.calls(sid).fetch(); } catch (e) {
    throw new L.UpstreamError(e.status === 404 ? 'Twilio has no call with that id.' : L.twilioError(e), { status: e.status === 404 ? 404 : 502 });
  }
  const call = L.callFromTwilio(raw);
  const ended = await L.loadSyncMap(ctx, 'ended');

  // Find the conversation: given by the app, remembered in the index, or by time.
  let conversationId = convParam || null;
  let matchedBy = convParam ? 'given' : null;
  if (!conversationId) {
    const index = await L.loadSyncMap(ctx, 'index');
    for (const [cid, v] of index) if (v && v.callSid === sid) { conversationId = cid; matchedBy = 'call_sid'; break; }
  }
  if (!conversationId && call.startTime) {
    const convs = await L.listConversations(ctx, { fromUnix: Math.floor(call.startTime) - 60, toUnix: Math.floor(call.startTime) + 3600, maxPages: 1 });
    const m = L.matchConversations([call], convs, new Map()).get(sid);
    if (m) { conversationId = m.conv.conversation_id; matchedBy = 'time'; }
  }

  let conversation = null;
  if (conversationId) {
    const c = await L.getConversation(ctx, conversationId);
    const info = L.phoneInfo(c);
    if (info.callSid && info.callSid !== sid) {
      conversation = null; // time match was wrong; don't show someone else's transcript
    } else {
      if (info.callSid) await L.remember(ctx, 'index', conversationId, { callSid: info.callSid });
      const a = c.analysis || {};
      const list = (obj, listKey) => (Array.isArray(a[listKey]) ? a[listKey] : Object.values(a[obj] || {}));
      conversation = {
        id: conversationId,
        matchedBy: info.callSid === sid ? 'call_sid' : matchedBy,
        agentId: c.agent_id,
        agentName: c.agent_name || 'Unknown agent',
        status: c.status,
        credits: c.metadata && c.metadata.cost != null ? c.metadata.cost : null,
        elevenUsd: c.metadata && c.metadata.cost_fiat != null ? c.metadata.cost_fiat : null,
        terminationReason: (c.metadata && c.metadata.termination_reason) || null,
        title: a.call_summary_title || null,
        summary: a.transcript_summary || null,
        callSuccessful: a.call_successful || null,
        evaluations: list('evaluation_criteria_results', 'evaluation_criteria_results_list').map((e) => ({
          id: e.criteria_id, result: e.result, rationale: e.rationale || '',
        })),
        dataCollection: list('data_collection_results', 'data_collection_results_list').map((d) => ({
          id: d.data_collection_id, value: d.value == null ? null : String(d.value), rationale: d.rationale || '',
        })),
        transcript: (c.transcript || []).filter((t) => t.message).map((t) => ({
          role: t.role, text: t.message, t: t.time_in_call_secs || 0,
        })),
        hasAudio: c.has_audio !== false,
      };
    }
  }

  const outbound = (call.direction || '').startsWith('outbound');
  return {
    ok: true,
    call: {
      callSid: sid,
      direction: outbound ? 'outbound' : 'inbound',
      number: outbound ? call.to : call.from,
      startTime: call.startTime || call.dateCreated,
      ringSecs: L.ringSeconds(call),
      talkSecs: call.duration,
      twilioStatus: call.status,
      outcome: L.outcomeOf(call, ended.has(sid)),
      twilioUsd: call.price,
      twilioPricePending: call.price == null && !L.LIVE_TWILIO.includes(call.status),
    },
    conversation,
  };
}
