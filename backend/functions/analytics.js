// GET /analytics?from=YYYY-MM-DD&to=YYYY-MM-DD
// Charts and totals computed from the same history as /calls (Eastern time).
const lib = () => require(Runtime.getFunctions()['common'].path);

exports.handler = (context, event, callback) =>
  lib().endpoint(async (ctx, ev) => {
    const L = lib();
    const range = L.parseRange(ev);
    const h = await L.loadHistory(ctx, range);
    return { ok: true, from: range.fromDate, to: range.toDate, stale: !!h.stale, cachedAt: h.cachedAt, ...summarize(L, h.items, range) };
  })(context, event, callback);

function summarize(L, items, range) {
  const r2 = (n) => Math.round(n * 100) / 100;
  const days = new Map();
  for (let t = range.fromUnix + 7200; t < range.toUnix - 3600; t += 86400) {
    days.set(L.easternParts(t).date, { day: L.easternParts(t).date, calls: 0, answered: 0 });
  }
  const hours = new Array(24).fill(0);
  const outcomes = Object.fromEntries(L.OUTCOMES.map((o) => [o, 0]));
  const agents = new Map();
  const periods = { day: new Map(), week: new Map(), month: new Map() };
  let answered = 0, talkTotal = 0, pending = 0;

  const add = (map, key) => {
    if (!map.has(key)) map.set(key, { period: key, calls: 0, credits: 0, elevenUsd: 0, twilioUsd: 0, twilioPending: 0 });
    return map.get(key);
  };

  for (const i of items) {
    const p = L.easternParts(i.startTime);
    const d = days.get(p.date) || { day: p.date, calls: 0, answered: 0 };
    days.set(p.date, d);
    d.calls++;
    const wasAnswered = (i.talkSecs || 0) > 0;
    if (wasAnswered) { d.answered++; answered++; talkTotal += i.talkSecs; }
    hours[p.hour]++;
    outcomes[i.outcome] = (outcomes[i.outcome] || 0) + 1;
    if (i.twilioPricePending) pending++;

    const a = agents.get(i.agentName) || { agentId: i.agentId, agentName: i.agentName, calls: 0, answered: 0, credits: 0, elevenUsd: 0, twilioUsd: 0, twilioPending: 0 };
    agents.set(i.agentName, a);
    a.calls++; if (wasAnswered) a.answered++;
    for (const row of [a, add(periods.day, p.date), add(periods.week, L.weekStart(p.date)), add(periods.month, p.date.slice(0, 7))]) {
      if (row !== a) row.calls++;
      row.credits += i.credits || 0;
      row.elevenUsd += i.elevenUsd || 0;
      row.twilioUsd += i.twilioUsd || 0;
      if (i.twilioPricePending) row.twilioPending++;
    }
  }
  const tidy = (rows) => rows.map((r) => ({ ...r, elevenUsd: r2(r.elevenUsd), twilioUsd: r2(r.twilioUsd), totalUsd: r2(r.elevenUsd + r.twilioUsd) }));
  return {
    totalCalls: items.length,
    answeredCalls: answered,
    answerRate: items.length ? r2(answered / items.length) : null,
    avgTalkSecs: answered ? Math.round(talkTotal / answered) : null,
    twilioPricesPending: pending,
    callsPerDay: [...days.values()].sort((x, y) => x.day.localeCompare(y.day)),
    busiestHours: hours.map((calls, hour) => ({ hour, calls })),
    outcomes,
    byAgent: tidy([...agents.values()].sort((x, y) => y.calls - x.calls)),
    totals: {
      byDay: tidy([...periods.day.values()].sort((x, y) => y.period.localeCompare(x.period))),
      byWeek: tidy([...periods.week.values()].sort((x, y) => y.period.localeCompare(x.period))),
      byMonth: tidy([...periods.month.values()].sort((x, y) => y.period.localeCompare(x.period))),
    },
  };
}
exports.summarize = summarize;
