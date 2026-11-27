// GET /live -> every call that is queued, ringing or in progress right now.
// Status and timing come from Twilio; the agent comes from ElevenLabs, matched by
// the Twilio call SID on the conversation (metadata.phone_call.call_sid).
const lib = () => require(Runtime.getFunctions()['common'].path);

// When we first saw each call "in-progress" (Twilio has no "answered at" field).
const firstSeenInProgress = new Map();

exports.handler = (context, event, callback) =>
  lib().endpoint(async (ctx) => {
    const L = lib();
    // A 2-second cache: protects the APIs if the app and the notification watcher poll together.
    return L.cached('live', 2000, () => loadLive(L, ctx));
  })(context, event, callback);

async function loadLive(L, ctx) {
  const now = L.deps.now() / 1000;
  const client = ctx.getTwilioClient();
  const [twilioCalls, liveConvs, agents] = await Promise.all([
    L.listLiveTwilioCalls(client),
    L.listConversations(ctx, { fromUnix: Math.floor(now) - 6 * 3600, maxPages: 2 })
      .then((cs) => cs.filter((c) => L.LIVE_EL.has(c.status))),
    L.listAgents(ctx).catch(() => []),
  ]);
  const agentNames = new Map(agents.map((a) => [a.id, a.name]));

  // Read each live conversation's phone metadata (only live ones: a handful at most).
  const details = await Promise.all(liveConvs.slice(0, 20).map(async (c) => {
    try {
      const info = L.phoneInfo(await L.getConversation(ctx, c.conversation_id));
      if (info.callSid) L.remember(ctx, 'index', c.conversation_id, { callSid: info.callSid }).catch(() => {});
      return { conv: c, info };
    } catch (_) {
      return { conv: c, info: {} };
    }
  }));

  const used = new Set();
  const calls = twilioCalls.map(L.callFromTwilio).map((call) => {
    const outbound = (call.direction || '').startsWith('outbound');
    const number = outbound ? call.to : call.from;
    // Exact match by call SID; otherwise by the other party's number.
    let m = details.find((d) => d.info.callSid === call.sid);
    if (!m) m = details.find((d) => !used.has(d.conv.conversation_id) && !d.info.callSid && d.info.externalNumber === number);
    if (m) used.add(m.conv.conversation_id);

    const status = call.status === 'in-progress' ? 'in-progress' : call.status === 'ringing' ? 'ringing' : 'queued';
    let answeredAt = null;
    if (status === 'in-progress') {
      if (!firstSeenInProgress.has(call.sid)) firstSeenInProgress.set(call.sid, now);
      const elStart = m && m.info.startUnix;
      // ElevenLabs starts the conversation when the call connects; use it when it's plausible.
      answeredAt = elStart && call.startTime && elStart >= call.startTime - 2 ? elStart : firstSeenInProgress.get(call.sid);
    }
    return {
      callSid: call.sid,
      conversationId: m ? m.conv.conversation_id : null,
      agentId: m ? m.conv.agent_id : null,
      agentName: m ? (m.conv.agent_name || agentNames.get(m.conv.agent_id) || 'Unknown agent') : 'Unknown agent',
      matchedBy: m ? (m.info.callSid === call.sid ? 'call_sid' : 'number') : null,
      direction: outbound ? 'outbound' : 'inbound',
      number,
      status,
      dialedAt: call.startTime || call.dateCreated,
      answeredAt,
    };
  });

  // Forget calls that are no longer live.
  const liveSids = new Set(calls.map((c) => c.callSid));
  for (const sid of firstSeenInProgress.keys()) if (!liveSids.has(sid)) firstSeenInProgress.delete(sid);

  return { ok: true, serverTime: now, calls };
}
