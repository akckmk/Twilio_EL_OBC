// GET /recording?conversation=<id>&part=<n>
// Streams the ElevenLabs call recording through the backend, in ~1.5 MB parts sent
// as base64 inside JSON (works whatever Twilio's binary-response support is).
// The phone never sees an ElevenLabs key or a signed link.
const lib = () => require(Runtime.getFunctions()['common'].path);
const PART_BYTES = 1_500_000;
let lastAudio = null; // { id, mime, buf } - one recording kept while the function is warm

exports.handler = (context, event, callback) =>
  lib().endpoint(async (ctx, ev) => {
    const L = lib();
    const id = L.param(ev.conversation);
    const part = Math.min(10_000, Math.max(0, parseInt(L.param(ev.part) || '0', 10) || 0));
    if (!L.SAFE_ID.test(id)) return { httpStatus: 400, ok: false, message: 'Add ?conversation=<id>.' };

    if (!lastAudio || lastAudio.id !== id) {
      const res = await L.elFetch(ctx, `/v1/convai/conversations/${id}/audio`, { raw: true });
      const buf = Buffer.from(await res.arrayBuffer());
      lastAudio = { id, mime: (res.headers.get('content-type') || 'audio/mpeg').split(';')[0], buf };
    }
    const total = lastAudio.buf.length;
    const parts = Math.max(1, Math.ceil(total / PART_BYTES));
    if (part >= parts) return { httpStatus: 416, ok: false, message: 'No such part.' };
    const slice = lastAudio.buf.subarray(part * PART_BYTES, Math.min(total, (part + 1) * PART_BYTES));
    return { ok: true, mime: lastAudio.mime, totalBytes: total, parts, part, data: slice.toString('base64') };
  })(context, event, callback);
