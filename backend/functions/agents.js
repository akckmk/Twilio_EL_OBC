// GET /agents  ->  { ok, agents: [{ id, name }] }
// Read live from ElevenLabs every time, so new agents show up on their own.

exports.handler = async function (context, event, callback) {
  const lib = require(Runtime.getFunctions()['common'].path);
  const denied = lib.checkAccess(context, event);
  if (denied) return callback(null, denied);

  try {
    const agents = await lib.listAgents(context);
    return callback(null, lib.jsonResponse(200, {
      ok: true,
      message: `Found ${lib.plural(agents.length, 'agent')}.`,
      agents,
    }));
  } catch (e) {
    console.error('agents failed:', e.message);
    return callback(null, lib.jsonResponse(502, {
      ok: false,
      message: `Could not read agents from ElevenLabs (${e.message}).`,
    }));
  }
};
