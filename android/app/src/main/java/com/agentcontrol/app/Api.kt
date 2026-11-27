package com.agentcontrol.app

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Talks to the "agentcontrol" backend. Every request carries the access key.
 * The app never talks to Twilio or ElevenLabs directly and never holds their keys.
 * Call these off the main thread.
 */
class Api(private val settings: SecureStore.Settings, private val http: OkHttpClient = defaultClient) {

    class ApiException(message: String, val missingPermission: String? = null) : Exception(message)

    // ------------------------------------------------------------ models

    data class Agent(val id: String, val name: String)
    data class Outcome(val ok: Boolean, val message: String)

    data class LiveCall(
        val callSid: String, val conversationId: String?, val agentName: String, val number: String,
        val direction: String, val status: String, val dialedAt: Double?, val answeredAt: Double?,
    )
    data class Live(val serverTime: Double, val calls: List<LiveCall>, val stale: Boolean)

    data class CallItem(
        val callSid: String, val conversationId: String?, val agentId: String?, val agentName: String,
        val number: String, val direction: String, val startTime: Double, val ringSecs: Int?, val talkSecs: Int?,
        val outcome: String, val credits: Double?, val elevenUsd: Double?, val twilioUsd: Double?,
        val twilioPricePending: Boolean, val title: String?,
    )
    data class History(val from: String, val to: String, val items: List<CallItem>, val truncated: Boolean, val stale: Boolean, val cachedAt: Long)

    data class Line(val role: String, val text: String, val t: Double)
    data class Result(val id: String, val value: String, val rationale: String)
    data class Detail(
        val call: CallItem, val conversationId: String?, val title: String?, val summary: String?,
        val callSuccessful: String?, val terminationReason: String?, val evaluations: List<Result>,
        val dataCollection: List<Result>, val transcript: List<Line>, val hasAudio: Boolean,
        /** "call_sid" = exact; "time" = paired by start time only, could be the wrong call. */
        val matchedBy: String?,
    )
    data class AudioPart(val mime: String, val parts: Int, val bytes: ByteArray)

    // ------------------------------------------------------------ endpoints

    fun agents(): List<Agent> {
        val a = get("agents").getJSONArray("agents")
        return (0 until a.length()).map { a.getJSONObject(it).let { o -> Agent(o.getString("id"), o.getString("name")) } }
    }

    /** agentId is an ElevenLabs agent id, or "all". Never throws. */
    fun end(agentId: String): Outcome = safely { get("end", "agent" to agentId) }

    /** Ends one specific Twilio call. Never throws. */
    fun endCall(callSid: String): Outcome = safely { get("end", "call" to callSid) }

    fun live(): Live = parseLive(get("live"))

    fun calls(from: String, to: String): History = parseHistory(get("calls", "from" to from, "to" to to))
    fun callsRaw(from: String, to: String): JSONObject = get("calls", "from" to from, "to" to to)

    fun call(callSid: String, conversationId: String?): Detail =
        parseDetail(get("call", "id" to callSid, "conversation" to conversationId))

    fun recordingPart(conversationId: String, part: Int): AudioPart {
        val o = get("recording", "conversation" to conversationId, "part" to part.toString())
        return AudioPart(o.optString("mime", "audio/mpeg"), o.getInt("parts"), java.util.Base64.getMimeDecoder().decode(o.getString("data")))
    }

    fun usage(): JSONObject = get("usage")
    fun analytics(from: String, to: String): JSONObject = get("analytics", "from" to from, "to" to to)
    fun health(): JSONObject = get("health")

    // ------------------------------------------------------------ HTTP

    private fun safely(block: () -> JSONObject): Outcome = try {
        val o = block()
        Outcome(o.optBoolean("ok", false), o.optString("message", "Done."))
    } catch (e: ApiException) {
        Outcome(false, e.message ?: "Something went wrong.")
    }

    private fun url(path: String, vararg query: Pair<String, String?>): HttpUrl {
        val base = settings.baseUrl.toHttpUrlOrNull() ?: throw ApiException("The backend URL looks wrong. Check Settings.")
        return base.newBuilder().addPathSegment(path).apply {
            for ((k, v) in query) if (v != null) addQueryParameter(k, v)
        }.build()
    }

    fun get(path: String, vararg query: Pair<String, String?>): JSONObject {
        val req = Request.Builder().url(url(path, *query))
            .header("X-Agent-Control-Key", settings.accessKey)
            .header("Accept", "application/json")
            .build()
        try {
            http.newCall(req).execute().use { res ->
                val text = res.body?.string().orEmpty()
                if (res.code in 300..399) throw ApiException("The backend sent an unexpected redirect. Nothing was done.")
                if (res.code == 401) throw ApiException("Wrong access key. Check Settings.")
                val json = try { JSONObject(text) } catch (e: Exception) { null }
                    ?: throw ApiException("Backend error (HTTP ${res.code}).")
                if (!res.isSuccessful && path != "end") {
                    throw ApiException(json.optString("message", "Backend error (HTTP ${res.code})."), json.optString("missingPermission").ifEmpty { null })
                }
                return json
            }
        } catch (e: IOException) {
            throw ApiException("Could not reach the backend. Check your internet connection.")
        }
    }

    companion object {
        val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false) // a redirect could carry the access key elsewhere
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()

        fun normalizeUrl(input: String): String {
            var url = input.trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
            return url.trimEnd('/')
        }

        private fun JSONObject.dbl(k: String): Double? = if (isNull(k) || !has(k)) null else optDouble(k).takeIf { !it.isNaN() }
        private fun JSONObject.int(k: String): Int? = dbl(k)?.toInt()
        private fun JSONObject.str(k: String): String? = if (isNull(k) || !has(k)) null else optString(k)

        fun parseLive(o: JSONObject): Live {
            val a = o.optJSONArray("calls") ?: JSONArray()
            return Live(o.optDouble("serverTime", System.currentTimeMillis() / 1000.0), (0 until a.length()).map {
                val c = a.getJSONObject(it)
                LiveCall(
                    c.getString("callSid"), c.str("conversationId"), c.optString("agentName", "Unknown agent"),
                    c.optString("number"), c.optString("direction"), c.optString("status"), c.dbl("dialedAt"), c.dbl("answeredAt"),
                )
            }, o.optBoolean("stale"))
        }

        fun parseItem(c: JSONObject) = CallItem(
            c.getString("callSid"), c.str("conversationId"), c.str("agentId"), c.optString("agentName", "Unknown agent"),
            c.optString("number"), c.optString("direction"), c.optDouble("startTime", 0.0), c.int("ringSecs"), c.int("talkSecs"),
            c.optString("outcome"), c.dbl("credits"), c.dbl("elevenUsd"), c.dbl("twilioUsd"),
            c.optBoolean("twilioPricePending"), c.str("title"),
        )

        fun parseHistory(o: JSONObject): History {
            val a = o.optJSONArray("items") ?: JSONArray()
            return History(
                o.optString("from"), o.optString("to"), (0 until a.length()).map { parseItem(a.getJSONObject(it)) },
                o.optBoolean("truncated"), o.optBoolean("stale"), o.optLong("cachedAt", System.currentTimeMillis()),
            )
        }

        fun parseDetail(o: JSONObject): Detail {
            val call = o.getJSONObject("call")
            val conv = o.optJSONObject("conversation")
            val item = parseItem(call.put("agentName", conv?.optString("agentName") ?: "Unknown agent")
                .put("conversationId", conv?.optString("id") ?: JSONObject.NULL)
                .put("credits", conv?.opt("credits") ?: JSONObject.NULL)
                .put("elevenUsd", conv?.opt("elevenUsd") ?: JSONObject.NULL))
            fun results(key: String, valueKey: String): List<Result> {
                val a = conv?.optJSONArray(key) ?: return emptyList()
                return (0 until a.length()).map { a.getJSONObject(it).let { r -> Result(r.optString("id"), r.optString(valueKey), r.optString("rationale")) } }
            }
            val t = conv?.optJSONArray("transcript") ?: JSONArray()
            return Detail(
                item, conv?.optString("id"), conv?.str("title"), conv?.str("summary"), conv?.str("callSuccessful"),
                conv?.str("terminationReason"), results("evaluations", "result"), results("dataCollection", "value"),
                (0 until t.length()).map { t.getJSONObject(it).let { l -> Line(l.optString("role"), l.optString("text"), l.optDouble("t", 0.0)) } },
                conv?.optBoolean("hasAudio", false) ?: false,
                conv?.str("matchedBy"),
            )
        }
    }
}
