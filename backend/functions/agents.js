// GET /agents -> { ok, agents: [{ id, name }] }, read live from ElevenLabs (cached 60 s).
const lib = () => require(Runtime.getFunctions()['common'].path);

exports.handler = (context, event, callback) =>
  lib().endpoint(async (ctx) => {
    const agents = await lib().listAgents(ctx);
    return { ok: true, message: `Found ${agents.length} agent${agents.length === 1 ? '' : 's'}.`, agents };
  })(context, event, callback);
