// GET /health -> checks each thing the backend needs and says exactly what's missing.
const lib = () => require(Runtime.getFunctions()['common'].path);

exports.handler = (context, event, callback) =>
  lib().endpoint(async (ctx) => {
    const L = lib();
    const client = ctx.getTwilioClient();
    const now = Math.floor(L.deps.now() / 1000);
    const check = async (name, needs, fn) => {
      try { await fn(); return { name, ok: true }; } catch (e) {
        return { name, ok: false, needs, missingPermission: e.permission || undefined, message: e.permission ? e.message : (e.message || 'failed').slice(0, 200) };
      }
    };
    const checks = await Promise.all([
      check('ElevenLabs: list agents', 'convai_read', () => L.elFetch(ctx, '/v1/convai/agents?page_size=1')),
      check('ElevenLabs: list conversations', 'convai_read', () => L.elFetch(ctx, `/v1/convai/conversations?page_size=1&call_start_after_unix=${now - 86400}`)),
      check('ElevenLabs: subscription / credits', 'user_read', () => L.elFetch(ctx, '/v1/user/subscription')),
      check('Twilio: calls', 'Twilio account access', () => client.calls.list({ limit: 1 })),
      check('Twilio: balance', 'Twilio account access', () => client.balance.fetch()),
      check('Twilio: usage this month', 'Twilio account access', () => client.usage.records.thisMonth.list({ category: 'totalprice', limit: 1 })),
      check('Twilio Sync (remembers matches and calls you ended)', 'SYNC_SERVICE_SID set by the deploy',
        async () => { if (!ctx.SYNC_SERVICE_SID) throw new Error('SYNC_SERVICE_SID is not set'); await client.sync.v1.services(ctx.SYNC_SERVICE_SID).fetch(); }),
    ]);
    const missing = [...new Set(checks.filter((c) => c.missingPermission).map((c) => c.missingPermission))];
    return {
      ok: checks.every((c) => c.ok),
      message: missing.length
        ? `The ElevenLabs key is missing: ${missing.join(', ')}. Add ${missing.length > 1 ? 'them' : 'it'} at elevenlabs.io > Developers > API Keys > edit the key.`
        : checks.every((c) => c.ok) ? 'Everything is set up.' : 'Some checks failed; see the list.',
      missingElevenLabsPermissions: missing,
      checks,
    };
  })(context, event, callback);
