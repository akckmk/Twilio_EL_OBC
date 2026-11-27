// GET /calls?from=YYYY-MM-DD&to=YYYY-MM-DD[&agent=<id>]
// Every call in the date range (Eastern time; default: last 31 days; max 92 days),
// newest first, with agent, outcome, timing and costs. Cached for 60 s.
const lib = () => require(Runtime.getFunctions()['common'].path);

exports.handler = (context, event, callback) =>
  lib().endpoint(async (ctx, ev) => {
    const L = lib();
    const range = L.parseRange(ev);
    const h = await L.loadHistory(ctx, range);
    let items = h.items;
    if (L.param(ev.agent)) items = items.filter((i) => i.agentId === L.param(ev.agent));
    return {
      ok: true, from: range.fromDate, to: range.toDate, count: items.length,
      truncated: h.truncated, stale: !!h.stale, cachedAt: h.cachedAt, items,
    };
  })(context, event, callback);
