// Runs every endpoint against fake ElevenLabs, Twilio and Sync. No network, no real calls.
// Run with: npm test

const test = require('node:test');
const assert = require('node:assert');
const path = require('path');
const { EventEmitter } = require('events');

const FN = path.join(__dirname, '..', 'functions');
const KEY = 'k'.repeat(40);

global.Twilio = {
  Response: class {
    constructor() { this.statusCode = 200; this.headers = {}; this.body = null; }
    setStatusCode(c) { this.statusCode = c; }
    appendHeader(k, v) { this.headers[k] = v; }
    setBody(b) { this.body = b; }
  },
};
global.Runtime = { getFunctions: () => ({ common: { path: path.join(FN, 'common.private.js') } }) };

const lib = require(path.join(FN, 'common.private.js'));
const fn = (name) => require(path.join(FN, `${name}.js`));

// Fail any test that logs private data.
const PRIVATE = ['+15550001111', '+15550002222', '+15550003333', 'SECRET TRANSCRIPT', 'Private summary'];
const logged = [];
for (const m of ['log', 'error', 'warn', 'info']) {
  const orig = console[m];
  console[m] = (...a) => { logged.push(a.join(' ')); if (process.env.SHOW_LOGS) orig(...a); };
}

const sid = (c) => 'CA' + c.repeat(32);
const NOW = Date.UTC(2026, 10, 27, 17, 0, 0); // 27 Nov 2026 12:00 Eastern
const T = NOW / 1000;

function setup({ el = {}, calls = {}, elStatus = {}, sync = true } = {}) {
  lib.memory.clear();
  lib.syncMemory.index = null;
  lib.syncMemory.ended = null;
  lib.deps.now = () => NOW;
  lib.deps.sleep = async () => {};
  const updates = [];
  const syncData = { agentcontrol_conv_index: new Map(), agentcontrol_ended_by_me: new Map() };

  const routes = {
    '/v1/convai/agents': { agents: [{ agent_id: 'agent_m', name: 'Merida' }, { agent_id: 'agent_f', name: 'Fergus' }], has_more: false },
    '/v1/convai/agents/agent_m': { name: 'Merida' },
    '/v1/convai/agents/agent_f': { name: 'Fergus' },
    '/v1/user/subscription': { tier: 'creator', character_count: 12000, character_limit: 100000, next_character_count_reset_unix: T + 86400 * 10 },
    ...el,
  };
  lib.deps.fetch = async (url, opts) => {
    assert.strictEqual(opts.headers['xi-api-key'], 'el-key');
    assert.strictEqual(opts.redirect, 'error');
    const u = new URL(url);
    let key = u.pathname;
    if (key === '/v1/convai/conversations') {
      const agent = u.searchParams.get('agent_id');
      const list = routes['conversations'] || [];
      const after = Number(u.searchParams.get('call_start_after_unix'));
      return reply(200, { conversations: list.filter((c) => (!agent || c.agent_id === agent) && c.start_time_unix_secs >= after), has_more: false });
    }
    const status = elStatus[key];
    if (status) return reply(status.code, status.body || {});
    if (key.endsWith('/audio')) {
      const bytes = Buffer.alloc(3_200_000, 7);
      return { ok: true, status: 200, headers: new Map([['content-type', 'audio/mpeg']]), arrayBuffer: async () => bytes };
    }
    const body = routes[key];
    return body ? reply(200, body) : reply(404, {});
  };
  function reply(code, body) {
    return { ok: code < 300, status: code, headers: new Map(), json: async () => body, text: async () => JSON.stringify(body) };
  }
  lib.deps.WebSocket = () => class extends EventEmitter {
    constructor() { super(); setImmediate(() => this.emit('error', new Error('refused'))); }
    terminate() {}
  };

  const matches = (c, f) =>
    (!f.status || c.status === f.status) && (!f.from || c.from === f.from) && (!f.to || c.to === f.to) &&
    (!f.startTimeAfter || (c.startTime && c.startTime >= f.startTimeAfter)) &&
    (!f.startTimeBefore || (c.startTime && c.startTime < f.startTimeBefore));
  const client = {
    calls: Object.assign(
      (s) => ({
        fetch: async () => { if (!calls[s]) { const e = new Error('nf'); e.status = 404; e.code = 20404; throw e; } return { sid: s, ...calls[s] }; },
        update: async ({ status }) => { updates.push({ sid: s, status }); calls[s].status = status; },
      }),
      { list: async (f) => {
        // Like real Twilio: an unknown status filter is an error (code 20001), not "no results".
        const VALID = ['queued', 'ringing', 'in-progress', 'completed', 'busy', 'failed', 'no-answer', 'canceled'];
        if (f.status && !VALID.includes(f.status)) { const e = new Error(`Invalid Status: ${f.status}`); e.status = 400; e.code = 20001; throw e; }
        return Object.entries(calls).map(([s, c]) => ({ sid: s, ...c })).filter((c) => matches(c, f));
      } }
    ),
    balance: { fetch: async () => ({ balance: '23.45', currency: 'USD' }) },
    usage: { records: { thisMonth: { list: async () => [{ price: '6.70', priceUnit: 'USD' }] } } },
    sync: { v1: { services: Object.assign(() => ({
      fetch: async () => ({}),
      syncMaps: Object.assign((name) => ({
        syncMapItems: {
          list: async () => [...syncData[name]].map(([key, data]) => ({ key, data })),
          create: async ({ key, data }) => { syncData[name].set(key, data); },
        },
      }), { create: async () => ({}) }),
    }), {}) } },
  };
  const context = {
    AGENT_CONTROL_KEY: KEY, ELEVENLABS_API_KEY: 'el-key', getTwilioClient: () => client,
    SYNC_SERVICE_SID: sync ? 'IS' + '0'.repeat(32) : undefined,
  };
  return { context, updates, routes, syncData, calls };
}

function run(name, context, query = {}, key = KEY) {
  return new Promise((resolve, reject) => {
    const event = { ...query, request: { headers: key ? { 'x-agent-control-key': key } : {} } };
    fn(name).handler(context, event, (err, res) => (err ? reject(err) : resolve(res)));
  });
}

const call = (status, extra = {}) => ({ status, direction: 'outbound-api', from: '+15559990000', to: '+15550001111', dateCreated: new Date(NOW), ...extra });
const conv = (id, agent, status, start, phone = {}, extra = {}) => ({
  conversation_id: id, agent_id: agent, agent_name: agent === 'agent_m' ? 'Merida' : 'Fergus', status, start_time_unix_secs: start,
  cost: 400, cost_fiat: 0.08, call_summary_title: 'Title', ...extra,
  _detail: { conversation_id: id, agent_id: agent, status, metadata: { start_time_unix_secs: start, cost: 400, cost_fiat: 0.08, phone_call: phone } },
});
function withConvs(routes, list) {
  routes.conversations = list;
  for (const c of list) routes[`/v1/convai/conversations/${c.conversation_id}`] = c._detail;
}

// ------------------------------------------------------------------ access

test('every endpoint refuses a missing or wrong key', async () => {
  const { context } = setup();
  for (const name of ['agents', 'end', 'live', 'calls', 'call', 'recording', 'usage', 'analytics', 'health']) {
    assert.strictEqual((await run(name, context, {}, null)).statusCode, 401, name);
    assert.strictEqual((await run(name, context, {}, 'wrong')).statusCode, 401, name);
  }
});

test('/agents lists agents sorted by name', async () => {
  const { context } = setup();
  const res = await run('agents', context);
  assert.deepStrictEqual(res.body.agents.map((a) => a.name), ['Fergus', 'Merida']);
});

// ------------------------------------------------------------------ ending calls (unchanged behaviour)

test('/end?agent ends only that agent\'s call and remembers you ended it', async () => {
  const s = setup({ calls: { [sid('a')]: call('in-progress', { startTime: new Date(NOW - 60e3) }), [sid('b')]: call('in-progress', { to: '+15550002222' }) } });
  withConvs(s.routes, [conv('conv_m', 'agent_m', 'in-progress', T - 50, { call_sid: sid('a') })]);
  const res = await run('end', s.context, { agent: 'agent_m' });
  assert.strictEqual(res.body.message, 'Ended 1 call for Merida.');
  assert.deepStrictEqual(s.updates, [{ sid: sid('a'), status: 'completed' }]);
  assert.ok(s.syncData.agentcontrol_ended_by_me.has(sid('a')));
  assert.strictEqual(s.syncData.agentcontrol_conv_index.get('conv_m').callSid, sid('a'));
});

test('/end?agent falls back to matching the phone numbers', async () => {
  const s = setup({ calls: { [sid('c')]: call('ringing', { from: '+15559990000', to: '+15550003333' }) } });
  withConvs(s.routes, [conv('conv_f', 'agent_f', 'initiated', T - 5, { direction: 'outbound', agent_number: '+15559990000', external_number: '+15550003333' })]);
  const res = await run('end', s.context, { agent: 'agent_f' });
  assert.strictEqual(res.body.message, 'Ended 1 call for Fergus.');
  assert.deepStrictEqual(s.updates, [{ sid: sid('c'), status: 'canceled' }]);
});

test('/end?agent=all ends every live call', async () => {
  const s = setup({ calls: { [sid('a')]: call('in-progress'), [sid('b')]: call('queued'), [sid('d')]: call('completed') } });
  const res = await run('end', s.context, { agent: 'all' });
  assert.strictEqual(res.body.message, 'Ended 2 calls (all agents).');
});

test('/end?call ends one specific call', async () => {
  const s = setup({ calls: { [sid('a')]: call('ringing'), [sid('b')]: call('in-progress') } });
  const res = await run('end', s.context, { call: sid('a') });
  assert.strictEqual(res.body.message, 'Ended the call.');
  assert.deepStrictEqual(s.updates, [{ sid: sid('a'), status: 'canceled' }]);
  assert.strictEqual((await run('end', s.context, { call: '../x' })).statusCode, 400);
});

// ------------------------------------------------------------------ live

test('/live shows status, agent, number and timers; unmatched calls say Unknown agent', async () => {
  const s = setup({ calls: {
    [sid('a')]: call('in-progress', { startTime: new Date(NOW - 90e3) }),
    [sid('b')]: call('ringing', { to: '+15550002222', startTime: new Date(NOW - 8e3) }),
  } });
  withConvs(s.routes, [conv('conv_m', 'agent_m', 'in-progress', T - 80, { call_sid: sid('a') })]);
  const res = await run('live', s.context);
  const a = res.body.calls.find((c) => c.callSid === sid('a'));
  const b = res.body.calls.find((c) => c.callSid === sid('b'));
  assert.strictEqual(a.agentName, 'Merida');
  assert.strictEqual(a.status, 'in-progress');
  assert.strictEqual(a.dialedAt, T - 90);
  assert.strictEqual(a.answeredAt, T - 80); // rang 10 s, talking 80 s
  assert.strictEqual(a.number, '+15550001111');
  assert.strictEqual(b.agentName, 'Unknown agent');
  assert.strictEqual(b.status, 'ringing');
  assert.strictEqual(b.answeredAt, null);
  assert.strictEqual(res.body.serverTime, T);
});

// ------------------------------------------------------------------ history

function historySetup() {
  const s = setup({ calls: {
    [sid('1')]: call('completed', { startTime: new Date(NOW - 7200e3), endTime: new Date(NOW - 7200e3 + 70e3), duration: '60', price: '-0.0280' }),
    [sid('2')]: call('no-answer', { to: '+15550002222', startTime: new Date(NOW - 3600e3), endTime: new Date(NOW - 3600e3 + 30e3) }),
    [sid('3')]: call('completed', { to: '+15550003333', startTime: new Date(NOW - 1800e3), endTime: new Date(NOW - 1800e3 + 50e3), duration: '45' }),
    [sid('4')]: call('busy', { startTime: new Date(NOW - 900e3), endTime: new Date(NOW - 900e3 + 2e3) }),
  } });
  withConvs(s.routes, [
    conv('conv_1', 'agent_m', 'done', T - 7200 + 10, { call_sid: sid('1') }),
    conv('conv_3', 'agent_f', 'done', T - 1800 + 5, { call_sid: sid('3') }, { cost: 900, cost_fiat: 0.18 }),
  ]);
  s.syncData.agentcontrol_ended_by_me.set(sid('3'), { at: T - 1750 });
  return s;
}

test('/calls merges Twilio and ElevenLabs, newest first, with outcomes, timing and costs', async () => {
  const s = historySetup();
  const res = await run('calls', s.context);
  const items = res.body.items;
  assert.deepStrictEqual(items.map((i) => i.callSid), [sid('4'), sid('3'), sid('2'), sid('1')]);
  const [busy, mine, noAnswer, done] = items;
  assert.strictEqual(busy.outcome, 'busy');
  assert.strictEqual(busy.agentName, 'Unknown agent');
  assert.strictEqual(mine.outcome, 'ended_by_you');
  assert.strictEqual(mine.agentName, 'Fergus');
  assert.strictEqual(mine.credits, 900);
  assert.strictEqual(mine.twilioPricePending, true); // no price yet
  assert.strictEqual(noAnswer.outcome, 'no_answer');
  assert.strictEqual(noAnswer.ringSecs, 30);
  assert.strictEqual(done.outcome, 'completed');
  assert.strictEqual(done.agentName, 'Merida');
  assert.strictEqual(done.ringSecs, 10);
  assert.strictEqual(done.talkSecs, 60);
  assert.strictEqual(done.twilioUsd, 0.028);
  assert.strictEqual(done.twilioPricePending, false);
  // The index learned both SIDs for next time.
  assert.strictEqual(s.syncData.agentcontrol_conv_index.get('conv_1').callSid, sid('1'));
});

test('/calls filters by agent; bad dates are refused', async () => {
  const s = historySetup();
  const res = await run('calls', s.context, { agent: 'agent_m' });
  assert.deepStrictEqual(res.body.items.map((i) => i.callSid), [sid('1')]);
  assert.strictEqual((await run('calls', s.context, { from: '2026-13-45' })).statusCode, 400);
});

test('/analytics counts outcomes, answer rate, hours and costs by agent and period', async () => {
  const s = historySetup();
  const res = await run('analytics', s.context);
  const b = res.body;
  assert.strictEqual(b.totalCalls, 4);
  assert.strictEqual(b.answeredCalls, 2);
  assert.strictEqual(b.answerRate, 0.5);
  assert.strictEqual(b.avgTalkSecs, 53);
  assert.deepStrictEqual(b.outcomes, { completed: 1, no_answer: 1, busy: 1, failed: 0, ended_by_you: 1, in_progress: 0 });
  assert.strictEqual(b.busiestHours.reduce((n, h) => n + h.calls, 0), 4);
  assert.strictEqual(b.busiestHours[10].calls, 1); // 10:00 Eastern
  const merida = b.byAgent.find((a) => a.agentName === 'Merida');
  assert.strictEqual(merida.credits, 400);
  assert.strictEqual(merida.twilioUsd, 0.03);
  assert.strictEqual(b.totals.byDay[0].period, '2026-11-27');
  assert.strictEqual(b.totals.byDay[0].calls, 4);
  assert.strictEqual(b.totals.byMonth[0].period, '2026-11');
  assert.strictEqual(b.twilioPricesPending, 3);
  assert.strictEqual(b.callsPerDay.at(-1).day, '2026-11-27');
});

// ------------------------------------------------------------------ detail + recording

test('/call returns summary, transcript and evaluations', async () => {
  const s = historySetup();
  s.routes['/v1/convai/conversations/conv_1'] = {
    ...s.routes['/v1/convai/conversations/conv_1'],
    agent_name: 'Merida',
    analysis: {
      transcript_summary: 'Private summary', call_successful: 'success', call_summary_title: 'Booked',
      evaluation_criteria_results_list: [{ criteria_id: 'stayed_in_window', result: 'success', rationale: 'ok' }],
      data_collection_results_list: [{ data_collection_id: 'appointment_time', value: 'Mon 9am', rationale: 'r' }],
    },
    transcript: [{ role: 'agent', message: 'Hi', time_in_call_secs: 0 }, { role: 'user', message: 'SECRET TRANSCRIPT', time_in_call_secs: 3 }, { role: 'agent', time_in_call_secs: 4 }],
  };
  const res = await run('call', s.context, { id: sid('1') });
  const c = res.body.conversation;
  assert.strictEqual(c.summary, 'Private summary');
  assert.strictEqual(c.transcript.length, 2);
  assert.deepStrictEqual(c.evaluations, [{ id: 'stayed_in_window', result: 'success', rationale: 'ok' }]);
  assert.strictEqual(c.dataCollection[0].value, 'Mon 9am');
  assert.strictEqual(res.body.call.ringSecs, 10);
  assert.strictEqual(res.body.call.outcome, 'completed');
});

test('/call never shows another call\'s transcript when a time match is wrong', async () => {
  const s = setup({ calls: { [sid('5')]: call('completed', { startTime: new Date(NOW - 100e3), endTime: new Date(NOW - 40e3), duration: '50' }) } });
  withConvs(s.routes, [conv('conv_x', 'agent_m', 'done', T - 95, { call_sid: sid('9') })]);
  const res = await run('call', s.context, { id: sid('5') });
  assert.strictEqual(res.body.conversation, null);
});

test('/recording streams audio in base64 parts that reassemble exactly', async () => {
  const s = historySetup();
  const first = (await run('recording', s.context, { conversation: 'conv_1', part: '0' })).body;
  assert.strictEqual(first.parts, 3);
  let total = 0;
  for (let p = 0; p < first.parts; p++) {
    const r = (await run('recording', s.context, { conversation: 'conv_1', part: String(p) })).body;
    total += Buffer.from(r.data, 'base64').length;
  }
  assert.strictEqual(total, 3_200_000);
  assert.strictEqual((await run('recording', s.context, { conversation: '../../x' })).statusCode, 400);
});

// ------------------------------------------------------------------ usage, health, permissions, rate limits

test('/usage reports credits and Twilio balance and spend', async () => {
  const { context } = setup();
  const b = (await run('usage', context)).body;
  assert.deepStrictEqual(b.elevenlabs, { plan: 'creator', creditsUsed: 12000, creditsTotal: 100000, creditsLeft: 88000, resetsAt: T + 864000 });
  assert.strictEqual(b.twilio.balance, 23.45);
  assert.strictEqual(b.twilio.spendThisMonth, 6.7);
});

test('a missing ElevenLabs permission is named exactly', async () => {
  const { context } = setup({ elStatus: { '/v1/user/subscription': { code: 401, body: { detail: { status: 'missing_permissions', message: 'The API key you used is missing the permission user_read to execute this operation.' } } } } });
  const usage = (await run('usage', context)).body;
  assert.strictEqual(usage.elevenlabs.missingPermission, 'user_read');
  const health = (await run('health', context)).body;
  assert.deepStrictEqual(health.missingElevenLabsPermissions, ['user_read']);
  assert.match(health.message, /missing: user_read/);
});

test('health passes when everything is set up; flags missing Sync', async () => {
  assert.strictEqual((await run('health', setup().context)).body.ok, true);
  const h = (await run('health', setup({ sync: false }).context)).body;
  assert.strictEqual(h.ok, false);
  assert.ok(h.checks.find((c) => c.name.startsWith('Twilio Sync') && !c.ok));
});

test('rate limits: retries once, then serves the last good data marked stale', async () => {
  const s = historySetup();
  const fresh = await run('calls', s.context);
  assert.strictEqual(fresh.body.items.length, 4);
  lib.deps.now = () => NOW + 120e3; // cache is now old
  const realFetch = lib.deps.fetch;
  let hits = 0;
  lib.deps.fetch = async (url, o) => (url.includes('/conversations') ? (hits++, { ok: false, status: 429, headers: new Map() }) : realFetch(url, o));
  const res = await run('calls', s.context);
  assert.strictEqual(res.statusCode, 200);
  assert.strictEqual(res.body.stale, true);
  assert.strictEqual(res.body.items.length, 4);
  assert.strictEqual(hits, 2); // one retry
});

test('works without Sync (memory only)', async () => {
  const s = historySetup();
  s.context.SYNC_SERVICE_SID = undefined;
  const res = await run('calls', s.context);
  assert.strictEqual(res.body.items.length, 4);
});

test('Eastern time conversions handle daylight saving', () => {
  assert.strictEqual(lib.easternMidnight('2026-11-27'), Date.UTC(2026, 10, 27, 5) / 1000); // EST
  assert.strictEqual(lib.easternMidnight('2026-07-04'), Date.UTC(2026, 6, 4, 4) / 1000); // EDT
  assert.strictEqual(lib.weekStart('2026-11-27'), '2026-11-23');
});

test('nothing private was ever logged', () => {
  for (const line of logged) for (const p of PRIVATE) assert.ok(!line.includes(p), `logged private data: ${line}`);
});

// Writes real responses for the Android app's contract test (android/app/src/test/resources/contract).
test('write contract fixtures for the Android app', async () => {
  const fs = require('fs');
  const dir = path.join(__dirname, '..', '..', 'android', 'app', 'src', 'test', 'resources', 'contract');
  fs.mkdirSync(dir, { recursive: true });
  const save = (name, body) => fs.writeFileSync(path.join(dir, `${name}.json`), JSON.stringify(body, null, 2) + '\n');

  let s = historySetup();
  save('calls', (await run('calls', s.context)).body);
  save('analytics', (await run('analytics', s.context)).body);
  s.routes['/v1/convai/conversations/conv_1'] = {
    ...s.routes['/v1/convai/conversations/conv_1'], agent_name: 'Merida',
    analysis: { transcript_summary: 'Booked a cleaning.', call_successful: 'success', call_summary_title: 'Booked',
      evaluation_criteria_results_list: [{ criteria_id: 'stayed_in_window', result: 'success', rationale: 'ok' }] },
    transcript: [{ role: 'agent', message: 'Hi', time_in_call_secs: 0 }, { role: 'user', message: 'Hello', time_in_call_secs: 2 }],
  };
  save('call', (await run('call', s.context, { id: sid('1') })).body);
  save('usage', (await run('usage', s.context)).body);
  const t = setup({ calls: { [sid('5')]: call('completed', { startTime: new Date(NOW - 100e3), endTime: new Date(NOW - 40e3), duration: '50' }) } });
  withConvs(t.routes, [conv('conv_y', 'agent_m', 'done', T - 95, {})]);
  save('call_time', (await run('call', t.context, { id: sid('5') })).body);
  const rec = (await run('recording', s.context, { conversation: 'conv_1', part: '2' })).body;
  save('recording', { ...rec, data: rec.data.slice(0, 40) });

  s = setup({ calls: {
    [sid('a')]: call('in-progress', { startTime: new Date(NOW - 90e3) }),
    [sid('b')]: call('ringing', { to: '+15550002222', startTime: new Date(NOW - 8e3) }),
  } });
  withConvs(s.routes, [conv('conv_m', 'agent_m', 'in-progress', T - 80, { call_sid: sid('a') })]);
  save('live', (await run('live', s.context)).body);
  save('health', (await run('health', setup().context)).body);
});

// ------------------------------------------------------------------ red-team attacks (pen test, Nov 2026)

function runRaw(name, context, event) {
  return new Promise((resolve, reject) => fn(name).handler(context, event, (err, res) => (err ? reject(err) : resolve(res))));
}
function spyUpstream(s) {
  const seen = [];
  const realFetch = lib.deps.fetch;
  lib.deps.fetch = async (url, o) => { seen.push(url); return realFetch(url, o); };
  const client = s.context.getTwilioClient();
  const realList = client.calls.list;
  client.calls.list = async (f) => { seen.push(`twilio:list:${JSON.stringify(f)}`); return realList(f); };
  return seen;
}
const ALL = ['agents', 'end', 'live', 'calls', 'call', 'recording', 'usage', 'analytics', 'health'];

test('ATTACK: a forged "request" parameter cannot fake the access-key header', async () => {
  const s = setup();
  for (const name of ALL) {
    // The Twilio runtime copies URL/body parameters OVER event.request, so try to replace it.
    const forged = { request: { headers: { 'x-agent-control-key': 'guess'.repeat(10) } } };
    assert.strictEqual((await runRaw(name, s.context, forged)).statusCode, 401, name);
    assert.strictEqual((await runRaw(name, s.context, { request: 'x' })).statusCode, 401, name);
    assert.strictEqual((await runRaw(name, s.context, { request: { headers: { 'x-agent-control-key': [KEY] } } })).statusCode, 401, name);
  }
});

test('ATTACK: a header sent twice, or with the right key plus junk, is refused', async () => {
  const s = setup();
  assert.strictEqual((await runRaw('agents', s.context, { request: { headers: { 'x-agent-control-key': [KEY, 'x'] } } })).statusCode, 401);
  assert.strictEqual((await runRaw('agents', s.context, { request: { headers: { 'x-agent-control-key': KEY + ' ' } } })).statusCode, 401);
  assert.strictEqual((await runRaw('agents', s.context, { request: { headers: { 'x-agent-control-key': KEY.slice(0, -1) } } })).statusCode, 401);
  assert.strictEqual((await runRaw('agents', s.context, { key: KEY, request: { headers: {} } })).statusCode, 401); // key in URL is ignored
});

test('ATTACK: refused requests never reach Twilio or ElevenLabs (no data, no cost)', async () => {
  const s = setup({ calls: { [sid('a')]: call('in-progress') } });
  const seen = spyUpstream(s);
  for (const name of ALL) await run(name, s.context, { agent: 'all', call: sid('a'), id: sid('a'), conversation: 'conv_1' }, 'wrong');
  assert.deepStrictEqual(seen, []);
  assert.strictEqual(s.calls[sid('a')].status, 'in-progress');
});

test('ATTACK: an unconfigured server says nothing about which secret is missing', async () => {
  const s = setup();
  s.context.ELEVENLABS_API_KEY = '';
  const r = await run('agents', s.context, {}, 'wrong');
  assert.ok(!/ELEVENLABS|AGENT_CONTROL_KEY/.test(JSON.stringify(r.body)), JSON.stringify(r.body));
});

test('ATTACK: injection and path-traversal payloads in every parameter are refused or harmless', async () => {
  const payloads = ['../../v1/user', '..%2F..%2Fv1', 'agent_m?x=1', 'agent_m#', 'a/b', '\u0000', '<script>', 'agent_m%00',
    "' OR 1=1 --", 'x'.repeat(5000), { toString: null }, ['agent_m', 'x'], { $gt: '' }];
  const s = historySetup();
  const seen = spyUpstream(s);
  for (const p of payloads) {
    for (const [name, key] of [['end', 'agent'], ['end', 'call'], ['call', 'id'], ['call', 'conversation'], ['recording', 'conversation'], ['calls', 'from'], ['analytics', 'to'], ['recording', 'part']]) {
      const q = { [key]: p };
      if (name === 'call' && key === 'conversation') q.id = sid('1');
      const r = await run(name, s.context, q);
      assert.ok([200, 400, 404].includes(r.statusCode), `${name}.${key}=${typeof p === 'string' ? p.slice(0, 20) : typeof p} -> ${r.statusCode}`);
      assert.ok(!/\n\s+at |\.js:\d+/.test(JSON.stringify(r.body || {})), 'no stack traces');
    }
  }
  for (const u of seen) {
    const path = String(u).replace(/^https:\/\/api\.elevenlabs\.io/, '');
    assert.ok(!/\.\.|%2e|%2f|%00|<|'|\s/i.test(path.split('?')[0]), `unsafe upstream path: ${u}`);
  }
  assert.ok(Object.values(s.calls).every((c) => c.status !== 'completed' || c.duration), 'no call was ended by a payload');
});

test('ATTACK: responses are never cached and allow no other websites to read them', async () => {
  const s = setup();
  for (const name of ALL) {
    const r = await run(name, s.context, { agent: 'all' });
    assert.strictEqual(r.headers['Cache-Control'], 'no-store', name);
    assert.ok(!Object.keys(r.headers).some((h) => /access-control-allow/i.test(h)), name);
  }
});

test('ATTACK: a transcript found only by time is labelled as such (never silently shown as exact)', async () => {
  const s = setup({ calls: { [sid('5')]: call('completed', { startTime: new Date(NOW - 100e3), endTime: new Date(NOW - 40e3), duration: '50' }) } });
  withConvs(s.routes, [conv('conv_y', 'agent_m', 'done', T - 95, {})]); // no call SID on the conversation
  const c = (await run('call', s.context, { id: sid('5') })).body.conversation;
  assert.strictEqual(c.matchedBy, 'time');
});

test('nothing private was logged during the attack tests either', () => {
  for (const line of logged) for (const p of PRIVATE) assert.ok(!line.includes(p), `logged private data: ${line}`);
});
