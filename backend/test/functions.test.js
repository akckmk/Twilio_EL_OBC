// Runs the functions with fake ElevenLabs and Twilio services. No network.
// Run with: npm test

const test = require('node:test');
const assert = require('node:assert');
const path = require('path');
const { EventEmitter } = require('events');

const FN = path.join(__dirname, '..', 'functions');
const KEY = 'k'.repeat(40);

// Minimal stand-ins for what the Twilio runtime provides.
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
const agentsFn = require(path.join(FN, 'agents.js'));
const endFn = require(path.join(FN, 'end.js'));

const SID_M = 'CA' + 'a'.repeat(32);
const SID_F = 'CA' + 'b'.repeat(32);
const SID_OTHER = 'CA' + 'c'.repeat(32);
const SID_NUMBERS = 'CA' + 'd'.repeat(32);

function setup({ elConversations = {}, twilioCalls = {}, wsWorks = false } = {}) {
  const updates = [];
  const el = {
    '/v1/convai/agents': { agents: [{ agent_id: 'agent_f', name: 'Fergus' }, { agent_id: 'agent_m', name: 'Merida' }], has_more: false },
    '/v1/convai/agents/agent_m': { agent_id: 'agent_m', name: 'Merida' },
    '/v1/convai/agents/agent_f': { agent_id: 'agent_f', name: 'Fergus' },
    ...elConversations,
  };
  lib.deps.fetch = async (url, opts) => {
    assert.strictEqual(opts.headers['xi-api-key'], 'el-key');
    const u = new URL(url);
    let key = u.pathname;
    if (key === '/v1/convai/conversations') key += '?agent_id=' + u.searchParams.get('agent_id');
    const body = el[key];
    return { ok: !!body, status: body ? 200 : 404, json: async () => body };
  };
  lib.deps.sleep = async () => {};
  lib.deps.WebSocket = () => class extends EventEmitter {
    constructor(url) {
      super();
      this.url = url;
      setImmediate(() => (wsWorks ? this.emit('open') : this.emit('error', new Error('refused'))));
    }
    send(msg, cb) {
      assert.deepStrictEqual(JSON.parse(msg), { command_type: 'end_call' });
      const id = this.url.split('/conversations/')[1].split('/')[0];
      el['/v1/convai/conversations/' + id].status = 'done';
      cb();
    }
    terminate() {}
  };

  const calls = twilioCalls;
  const client = {
    calls: Object.assign(
      (sid) => ({
        fetch: async () => {
          if (!calls[sid]) { const e = new Error('not found'); e.code = 20404; throw e; }
          return { sid, ...calls[sid] };
        },
        update: async ({ status }) => { updates.push({ sid, status }); calls[sid].status = status; },
      }),
      {
        list: async ({ status, from, to }) =>
          Object.entries(calls)
            .filter(([, c]) => c.status === status && (!from || c.from === from) && (!to || c.to === to))
            .map(([sid, c]) => ({ sid, ...c })),
      }
    ),
  };
  const context = { AGENT_CONTROL_KEY: KEY, ELEVENLABS_API_KEY: 'el-key', getTwilioClient: () => client };
  return { context, updates };
}

function run(fn, context, query, key = KEY) {
  return new Promise((resolve, reject) => {
    const event = { ...query, request: { headers: key ? { 'x-agent-control-key': key } : {} } };
    fn.handler(context, event, (err, res) => (err ? reject(err) : resolve(res)));
  });
}

const conv = (id, status, phone) => ({ conversation_id: id, status, metadata: { phone_call: phone } });

test('rejects requests without the right key', async () => {
  const { context } = setup();
  assert.strictEqual((await run(agentsFn, context, {}, null)).statusCode, 401);
  assert.strictEqual((await run(endFn, context, { agent: 'all' }, 'wrong')).statusCode, 401);
});

test('refuses to run with a short server key', async () => {
  const { context } = setup();
  context.AGENT_CONTROL_KEY = 'short';
  assert.strictEqual((await run(agentsFn, context, {}, 'short')).statusCode, 500);
});

test('/agents lists agents sorted by name', async () => {
  const { context } = setup();
  const res = await run(agentsFn, context, {});
  assert.strictEqual(res.statusCode, 200);
  assert.deepStrictEqual(res.body.agents, [{ id: 'agent_f', name: 'Fergus' }, { id: 'agent_m', name: 'Merida' }]);
});

test('/end for one agent hangs up only that agent\'s call', async () => {
  const { context, updates } = setup({
    elConversations: {
      '/v1/convai/conversations?agent_id=agent_m': {
        conversations: [{ conversation_id: 'conv_m1', status: 'in-progress' }, { conversation_id: 'conv_old', status: 'done' }],
        has_more: false,
      },
      '/v1/convai/conversations/conv_m1': conv('conv_m1', 'in-progress', { type: 'twilio', call_sid: SID_M }),
    },
    twilioCalls: {
      [SID_M]: { status: 'in-progress', from: '+1', to: '+2' },
      [SID_F]: { status: 'in-progress', from: '+1', to: '+3' },
    },
  });
  const res = await run(endFn, context, { agent: 'agent_m' });
  assert.strictEqual(res.body.message, 'Ended 1 call for Merida.');
  assert.deepStrictEqual(updates, [{ sid: SID_M, status: 'completed' }]);
});

test('/end falls back to matching phone numbers when no call SID', async () => {
  const { context, updates } = setup({
    elConversations: {
      '/v1/convai/conversations?agent_id=agent_f': { conversations: [{ conversation_id: 'conv_f1', status: 'initiated' }] },
      '/v1/convai/conversations/conv_f1': conv('conv_f1', 'initiated', {
        type: 'twilio', direction: 'outbound', agent_number: '+1555', external_number: '+1666',
      }),
    },
    twilioCalls: {
      [SID_NUMBERS]: { status: 'ringing', from: '+1555', to: '+1666' },
      [SID_OTHER]: { status: 'in-progress', from: '+1555', to: '+1777' },
    },
  });
  const res = await run(endFn, context, { agent: 'agent_f' });
  assert.strictEqual(res.body.message, 'Ended 1 call for Fergus.');
  assert.strictEqual(res.body.conversations[0].method, 'twilio_number_match');
  assert.deepStrictEqual(updates, [{ sid: SID_NUMBERS, status: 'canceled' }]);
});

test('/end uses ElevenLabs end_call as the last resort', async () => {
  const { context, updates } = setup({
    wsWorks: true,
    elConversations: {
      '/v1/convai/conversations?agent_id=agent_m': { conversations: [{ conversation_id: 'conv_x', status: 'in-progress' }] },
      '/v1/convai/conversations/conv_x': conv('conv_x', 'in-progress', { type: 'sip_trunking' }),
    },
  });
  const res = await run(endFn, context, { agent: 'agent_m' });
  assert.strictEqual(res.body.message, 'Ended 1 call for Merida.');
  assert.strictEqual(res.body.conversations[0].method, 'elevenlabs_end_call');
  assert.deepStrictEqual(updates, []);
});

test('/end reports failure honestly when nothing works', async () => {
  const { context } = setup({
    elConversations: {
      '/v1/convai/conversations?agent_id=agent_m': { conversations: [{ conversation_id: 'conv_y', status: 'in-progress' }] },
      '/v1/convai/conversations/conv_y': conv('conv_y', 'in-progress', {}),
    },
  });
  const res = await run(endFn, context, { agent: 'agent_m' });
  assert.strictEqual(res.body.ok, false);
  assert.strictEqual(res.body.message, 'No live calls found for Merida. Could not end 1; see details.');
});

test('/end says so when the agent has no live calls', async () => {
  const { context } = setup({
    elConversations: { '/v1/convai/conversations?agent_id=agent_f': { conversations: [] } },
  });
  const res = await run(endFn, context, { agent: 'agent_f' });
  assert.strictEqual(res.body.message, 'No live calls found for Fergus.');
});

test('/end?agent=all ends every live Twilio call', async () => {
  const { context, updates } = setup({
    twilioCalls: {
      [SID_M]: { status: 'in-progress', from: '+1', to: '+2' },
      [SID_F]: { status: 'queued', from: '+1', to: '+3' },
      [SID_OTHER]: { status: 'completed', from: '+1', to: '+4' },
    },
  });
  const res = await run(endFn, context, { agent: 'all' });
  assert.strictEqual(res.body.message, 'Ended 2 calls (all agents).');
  assert.deepStrictEqual(
    updates.sort((a, b) => a.sid.localeCompare(b.sid)),
    [{ sid: SID_M, status: 'completed' }, { sid: SID_F, status: 'canceled' }]
  );
});

test('/end rejects a bad agent id', async () => {
  const { context } = setup();
  assert.strictEqual((await run(endFn, context, { agent: '../x' })).statusCode, 400);
  assert.strictEqual((await run(endFn, context, {})).statusCode, 400);
});
