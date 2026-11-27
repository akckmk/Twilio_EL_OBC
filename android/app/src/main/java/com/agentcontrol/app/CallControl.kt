package com.agentcontrol.app

import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Talks straight to ElevenLabs and Twilio from the phone. No backend.
 * Call these off the main thread.
 *
 * Ending one agent's call:
 *  1. Ask ElevenLabs for that agent's live conversations.
 *  2. Read each one's Twilio call SID (metadata.phone_call.call_sid) and hang it up in Twilio.
 *  3. No SID? Find the live Twilio call between the two phone numbers ElevenLabs recorded.
 *  4. Still nothing? Ask ElevenLabs to end the conversation itself (best effort).
 */
class CallControl(
    private val settings: SecureStore.Settings,
    private val http: OkHttpClient = defaultClient,
    private val elevenLabsBase: String = "https://api.elevenlabs.io",
    private val twilioBase: String = "https://api.twilio.com",
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
) {
    data class Agent(val id: String, val name: String)
    data class Outcome(val ok: Boolean, val message: String)
    open class ApiException(message: String) : Exception(message)

    private data class TwilioCall(val sid: String, val status: String, val from: String, val to: String)
    private data class CallInfo(
        val callSid: String?, val direction: String?, val agentNumber: String?, val externalNumber: String?
    )

    // ---------------- public ----------------

    /** All ElevenLabs agents, sorted by name. */
    fun agents(): List<Agent> {
        val out = mutableListOf<Agent>()
        var cursor: String? = null
        for (page in 0 until 10) {
            val url = el("/v1/convai/agents").newBuilder().addQueryParameter("page_size", "100")
                .apply { cursor?.let { addQueryParameter("cursor", it) } }.build()
            val json = elGet(url)
            val list = json.optJSONArray("agents") ?: break
            for (i in 0 until list.length()) {
                val a = list.getJSONObject(i)
                out.add(Agent(a.getString("agent_id"), a.optString("name", a.getString("agent_id"))))
            }
            if (!json.optBoolean("has_more") || json.isNull("next_cursor")) break
            cursor = json.getString("next_cursor")
        }
        return out.sortedBy { it.name.lowercase() }
    }

    /** Throws ApiException with a plain message if the Twilio key doesn't work. */
    fun checkTwilio() {
        twilioGet(twilio("/Calls.json").newBuilder().addQueryParameter("PageSize", "1").build())
    }

    /** agentId is an ElevenLabs agent id, or "all". Never throws. */
    fun end(agentId: String): Outcome = try {
        if (agentId == "all") endAll() else endForAgent(agentId)
    } catch (e: ApiException) {
        Outcome(false, e.message ?: "Something went wrong.")
    } catch (e: IOException) {
        Outcome(false, "Couldn't reach Twilio or ElevenLabs. Check your internet connection.")
    } catch (e: Exception) {
        Outcome(false, "Something went wrong: ${e.message}")
    }

    // ---------------- ending calls ----------------

    private fun endAll(): Outcome {
        val results = liveTwilioCalls().map { endCall(it) }
        val ended = results.count { it == Result.ENDED }
        val failed = results.count { it == Result.FAILED }
        var msg = if (ended > 0) "Ended ${plural(ended, "call")} (all agents)." else "No live calls to end."
        if (failed > 0) msg += " Couldn't end $failed."
        return Outcome(failed == 0, msg)
    }

    private fun endForAgent(agentId: String): Outcome {
        require(Regex("^[A-Za-z0-9_-]{1,100}$").matches(agentId)) { "That agent id doesn't look right." }
        val name = agentName(agentId)
        val handled = mutableSetOf<String>()
        var ended = 0
        var failed = 0
        for (conversationId in liveConversationIds(agentId)) {
            when (endConversation(conversationId, handled)) {
                Result.ENDED -> ended++
                Result.FAILED -> failed++
                Result.ALREADY_OVER -> {}
            }
        }
        var msg = if (ended > 0) "Ended ${plural(ended, "call")} for $name." else "No live calls found for $name."
        if (failed > 0) msg += " Couldn't end $failed. Try \"End all calls\"."
        return Outcome(failed == 0, msg)
    }

    private enum class Result { ENDED, ALREADY_OVER, FAILED }

    private fun endConversation(conversationId: String, handled: MutableSet<String>): Result {
        val info = try { callInfo(conversationId) } catch (e: Exception) { CallInfo(null, null, null, null) }

        // 1) The Twilio call SID ElevenLabs stored.
        if (info.callSid != null && handled.add(info.callSid)) {
            val call = try { fetchCall(info.callSid) } catch (e: ApiException) { null }
            if (call != null) {
                val r = endCall(call)
                if (r != Result.FAILED) return r
            }
        }

        // 2) Find the live Twilio call by its two phone numbers.
        if (info.agentNumber != null && info.externalNumber != null) {
            val inbound = info.direction == "inbound"
            val from = if (inbound) info.externalNumber else info.agentNumber
            val to = if (inbound) info.agentNumber else info.externalNumber
            val matches = try { liveTwilioCalls(from, to).filter { handled.add(it.sid) } } catch (e: ApiException) { emptyList() }
            if (matches.map { endCall(it) }.any { it == Result.ENDED }) return Result.ENDED
        }

        // 3) Last resort: ask ElevenLabs to end the conversation (not in their public SDK).
        return if (endOnElevenLabs(conversationId)) Result.ENDED else Result.FAILED
    }

    /** In-progress calls are hung up with "completed"; ringing or queued ones with "canceled". */
    private fun endCall(call: TwilioCall): Result {
        if (call.status !in LIVE_TWILIO) return Result.ALREADY_OVER
        val newStatus = if (call.status == "in-progress") "completed" else "canceled"
        return try {
            twilioPost(twilio("/Calls/${call.sid}.json"), mapOf("Status" to newStatus))
            Result.ENDED
        } catch (e: ApiException) {
            Result.FAILED
        }
    }

    // ---------------- ElevenLabs ----------------

    private fun agentName(agentId: String): String {
        return try {
            elGet(el("/v1/convai/agents/$agentId")).optString("name", agentId)
        } catch (e: NotFound) {
            throw ApiException("ElevenLabs has no agent with that id. Pull down to refresh the list.")
        }
    }

    private fun liveConversationIds(agentId: String): List<String> {
        val since = System.currentTimeMillis() / 1000 - 6 * 3600
        val ids = mutableListOf<String>()
        var cursor: String? = null
        for (page in 0 until 3) {
            val url = el("/v1/convai/conversations").newBuilder()
                .addQueryParameter("agent_id", agentId)
                .addQueryParameter("page_size", "100")
                .addQueryParameter("call_start_after_unix", since.toString())
                .apply { cursor?.let { addQueryParameter("cursor", it) } }.build()
            val json = elGet(url)
            val list = json.optJSONArray("conversations") ?: break
            for (i in 0 until list.length()) {
                val c = list.getJSONObject(i)
                if (c.optString("status") in LIVE_ELEVENLABS) ids.add(c.getString("conversation_id"))
            }
            if (!json.optBoolean("has_more") || json.isNull("next_cursor")) break
            cursor = json.getString("next_cursor")
        }
        return ids
    }

    // Field names checked against a real call: metadata.phone_call.call_sid,
    // with the same SID also in the dynamic variable system__call_sid.
    private fun callInfo(conversationId: String): CallInfo {
        val c = elGet(el("/v1/convai/conversations/$conversationId"))
        val pc = c.optJSONObject("metadata")?.optJSONObject("phone_call") ?: JSONObject()
        val dyn = c.optJSONObject("conversation_initiation_client_data")?.optJSONObject("dynamic_variables") ?: JSONObject()
        val sid = pc.optString("call_sid").ifEmpty { dyn.optString("system__call_sid") }
            .takeIf { CALL_SID.matches(it) }
        return CallInfo(
            sid,
            pc.optString("direction").ifEmpty { null },
            pc.optString("agent_number").ifEmpty { null },
            pc.optString("external_number").ifEmpty { null },
        )
    }

    private fun endOnElevenLabs(conversationId: String): Boolean {
        val wsUrl = elevenLabsBase.replaceFirst("http", "ws") + "/v1/convai/conversations/$conversationId/monitor"
        val done = CountDownLatch(1)
        var sent = false
        http.newWebSocket(
            Request.Builder().url(wsUrl).header("xi-api-key", settings.elevenLabsKey).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    sent = webSocket.send("""{"command_type":"end_call"}""")
                    webSocket.close(1000, null)
                    done.countDown()
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    done.countDown()
                }
            }
        )
        if (!done.await(5, TimeUnit.SECONDS) || !sent) return false
        pause(1500)
        // Only count it as ended if ElevenLabs now says so.
        return try {
            elGet(el("/v1/convai/conversations/$conversationId")).optString("status") !in LIVE_ELEVENLABS
        } catch (e: Exception) {
            false
        }
    }

    // ---------------- Twilio ----------------

    private fun fetchCall(sid: String): TwilioCall = twilioGet(twilio("/Calls/$sid.json")).toCall()

    private fun liveTwilioCalls(from: String? = null, to: String? = null): List<TwilioCall> =
        LIVE_TWILIO.flatMap { status ->
            val url = twilio("/Calls.json").newBuilder()
                .addQueryParameter("Status", status)
                .addQueryParameter("PageSize", "1000")
                .apply {
                    from?.let { addQueryParameter("From", it) }
                    to?.let { addQueryParameter("To", it) }
                }.build()
            val list = twilioGet(url).optJSONArray("calls")
            (0 until (list?.length() ?: 0)).map { list!!.getJSONObject(it).toCall() }
        }

    private fun JSONObject.toCall() =
        TwilioCall(getString("sid"), optString("status"), optString("from"), optString("to"))

    // ---------------- HTTP ----------------

    private class NotFound : ApiException("Not found.")

    private fun el(path: String): HttpUrl = (elevenLabsBase + path).toHttpUrl()
    private fun twilio(path: String): HttpUrl =
        "$twilioBase/2010-04-01/Accounts/${settings.accountSid}$path".toHttpUrl()

    private fun elGet(url: HttpUrl): JSONObject =
        send(Request.Builder().url(url).header("xi-api-key", settings.elevenLabsKey).build(), "ElevenLabs")

    private fun twilioAuth() = Credentials.basic(settings.apiKeySid, settings.apiKeySecret)

    private fun twilioGet(url: HttpUrl): JSONObject =
        send(Request.Builder().url(url).header("Authorization", twilioAuth()).build(), "Twilio")

    private fun twilioPost(url: HttpUrl, form: Map<String, String>): JSONObject {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        return send(Request.Builder().url(url).header("Authorization", twilioAuth()).post(body).build(), "Twilio")
    }

    private fun send(request: Request, service: String): JSONObject {
        http.newCall(request).execute().use { res ->
            val text = res.body?.string().orEmpty()
            when {
                res.code == 401 || res.code == 403 ->
                    throw ApiException("$service rejected your key. Check it in Settings.")
                res.code == 404 -> throw NotFound()
                !res.isSuccessful -> throw ApiException("$service had a problem (error ${res.code}). Try again.")
            }
            return try { JSONObject(text) } catch (e: Exception) { throw ApiException("$service sent an odd reply.") }
        }
    }

    companion object {
        private val LIVE_ELEVENLABS = setOf("initiated", "in-progress")
        private val LIVE_TWILIO = listOf("in-progress", "ringing", "queued")
        private val CALL_SID = Regex("^CA[0-9a-fA-F]{32}$")

        private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

        val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
