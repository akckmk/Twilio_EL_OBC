// GET /usage -> ElevenLabs credits for this billing period + Twilio balance and spend this month.
const lib = () => require(Runtime.getFunctions()['common'].path);

exports.handler = (context, event, callback) =>
  lib().endpoint(async (ctx) => {
    const L = lib();
    return L.cached('usage', 60_000, async () => { // 1 minute: balances change
      const client = ctx.getTwilioClient();
      const [sub, balance, month] = await Promise.all([
        L.elFetch(ctx, '/v1/user/subscription').then((s) => ({ ok: true, s })).catch((e) => ({ ok: false, e })),
        client.balance.fetch().then((b) => ({ ok: true, b })).catch(() => ({ ok: false })),
        client.usage.records.thisMonth.list({ category: 'totalprice' }).then((r) => ({ ok: true, r })).catch(() => ({ ok: false })),
      ]);
      const out = { ok: true };
      if (sub.ok) {
        const s = sub.s;
        // ElevenLabs reports credits as "characters" (character_count / character_limit).
        out.elevenlabs = {
          plan: s.tier || null,
          creditsUsed: s.character_count,
          creditsTotal: s.character_limit,
          creditsLeft: Math.max(0, (s.character_limit || 0) - (s.character_count || 0)),
          resetsAt: s.next_character_count_reset_unix || null,
        };
      } else {
        out.elevenlabs = { error: sub.e.message, missingPermission: sub.e.permission || undefined };
      }
      out.twilio = {
        balance: balance.ok ? Number(balance.b.balance) : null,
        currency: balance.ok ? balance.b.currency : null,
        spendThisMonth: month.ok && month.r[0] ? Number(month.r[0].price) : null,
        spendCurrency: month.ok && month.r[0] ? month.r[0].priceUnit : null,
        error: !balance.ok || !month.ok ? 'Could not read some Twilio billing numbers.' : undefined,
      };
      return out;
    });
  })(context, event, callback);
