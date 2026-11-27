package com.agentcontrol.app

import okhttp3.Credentials
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Runs CallControl against fake ElevenLabs and Twilio servers. No real calls. */
class CallControlTest {

    private val settings = SecureStore.Settings(
        accountSid = "AC" + "0".repeat(32),
        apiKeySid = "SK" + "1".repeat(32),
        apiKeySecret = "s".repeat(32),
        elevenLabsKey = "el_" + "k".repeat(30),
    )
    private val sidM = "CA" + "a".repeat(32)
    private val sidF = "CA" + "b".repeat(32)
    private val sidOther = "CA" + "c".repeat(32)

    private lateinit var server: MockWebServer
    private val elConversations = mutableMapOf<String, JSONObject>() // id -> full conversation
    private val elLists = mutableMapOf<String, List<String>>()      // agent -> live conversation ids
    private val calls = mutableMapOf<String, JSONObject>()          // Twilio calls by SID
    private val updates = mutableListOf<Pair<String, String>>()
    private var wsWorks = false

    @Before fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = handle(request)
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun control() = CallControl(
        settings,
        elevenLabsBase = server.url("/el").toString().trimEnd('/'),
        twilioBase = server.url("/tw").toString().trimEnd('/'),
        pause = { Thread.sleep(300) }, // the app waits 1.5 s for ElevenLabs to catch up
    )

    private fun json(body: Any) = MockResponse().setBody(body.toString())

    private fun handle(r: RecordedRequest): MockResponse {
        val url = r.requestUrl!!
        val path = url.encodedPath
        if (path.startsWith("/el/")) {
            assertEquals(settings.elevenLabsKey, r.getHeader("xi-api-key"))
            return when {
                path == "/el/v1/convai/agents" -> json(JSONObject().put("has_more", false).put("agents", JSONArray()
                    .put(JSONObject().put("agent_id", "agent_m").put("name", "Merida"))
                    .put(JSONObject().put("agent_id", "agent_f").put("name", "Fergus"))))
                path == "/el/v1/convai/agents/agent_m" -> json(JSONObject().put("name", "Merida"))
                path == "/el/v1/convai/agents/agent_f" -> json(JSONObject().put("name", "Fergus"))
                path == "/el/v1/convai/conversations" -> {
                    val ids = elLists[url.queryParameter("agent_id")] ?: emptyList()
                    val arr = JSONArray()
                    ids.forEach { arr.put(JSONObject().put("conversation_id", it).put("status", elConversations[it]!!.getString("status"))) }
                    arr.put(JSONObject().put("conversation_id", "conv_old").put("status", "done"))
                    json(JSONObject().put("conversations", arr).put("has_more", false))
                }
                path.endsWith("/monitor") -> {
                    if (!wsWorks) return MockResponse().setResponseCode(403)
                    val id = path.split("/")[5]
                    MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            assertEquals("end_call", JSONObject(text).getString("command_type"))
                            elConversations[id]!!.put("status", "done")
                        }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            webSocket.close(1000, null)
                        }
                    })
                }
                path.startsWith("/el/v1/convai/conversations/") ->
                    elConversations[path.substringAfterLast("/")]?.let { json(it) } ?: MockResponse().setResponseCode(404)
                else -> MockResponse().setResponseCode(404)
            }
        }
        // Twilio
        assertEquals(Credentials.basic(settings.apiKeySid, settings.apiKeySecret), r.getHeader("Authorization"))
        val base = "/tw/2010-04-01/Accounts/${settings.accountSid}"
        if (path == "$base/Calls.json") {
            val arr = JSONArray()
            calls.values.filter { c ->
                (url.queryParameter("Status") == null || c.getString("status") == url.queryParameter("Status")) &&
                    (url.queryParameter("From") == null || c.getString("from") == url.queryParameter("From")) &&
                    (url.queryParameter("To") == null || c.getString("to") == url.queryParameter("To"))
            }.forEach { arr.put(it) }
            return json(JSONObject().put("calls", arr))
        }
        val sid = path.removePrefix("$base/Calls/").removeSuffix(".json")
        val call = calls[sid] ?: return MockResponse().setResponseCode(404)
        if (r.method == "POST") {
            val status = r.body.readUtf8().removePrefix("Status=")
            updates.add(sid to status)
            call.put("status", status)
        }
        return json(call)
    }

    private fun call(sid: String, status: String, from: String = "+1", to: String = "+2") {
        calls[sid] = JSONObject().put("sid", sid).put("status", status).put("from", from).put("to", to)
    }

    private fun conversation(id: String, agent: String, status: String, phone: JSONObject) {
        elConversations[id] = JSONObject().put("conversation_id", id).put("status", status)
            .put("metadata", JSONObject().put("phone_call", phone))
        elLists[agent] = (elLists[agent] ?: emptyList()) + id
    }

    @Test fun listsAgentsSortedByName() {
        assertEquals(listOf("Fergus", "Merida"), control().agents().map { it.name })
    }

    @Test fun endsOnlyThatAgentsCall() {
        conversation("conv_m", "agent_m", "in-progress", JSONObject().put("type", "twilio").put("call_sid", sidM))
        call(sidM, "in-progress")
        call(sidF, "in-progress", to = "+3")
        val out = control().end("agent_m")
        assertEquals("Ended 1 call for Merida.", out.message)
        assertTrue(out.ok)
        assertEquals(listOf(sidM to "completed"), updates)
    }

    @Test fun findsRingingCallByPhoneNumbersWhenNoSid() {
        conversation("conv_f", "agent_f", "initiated", JSONObject().put("type", "twilio")
            .put("direction", "outbound").put("agent_number", "+1555").put("external_number", "+1666"))
        call(sidF, "ringing", from = "+1555", to = "+1666")
        call(sidOther, "in-progress", from = "+1555", to = "+1777")
        assertEquals("Ended 1 call for Fergus.", control().end("agent_f").message)
        assertEquals(listOf(sidF to "canceled"), updates)
    }

    @Test fun usesElevenLabsEndCallAsLastResort() {
        wsWorks = true
        conversation("conv_x", "agent_m", "in-progress", JSONObject().put("type", "sip_trunking"))
        assertEquals("Ended 1 call for Merida.", control().end("agent_m").message)
        assertTrue(updates.isEmpty())
    }

    @Test fun saysSoWhenNothingWorks() {
        conversation("conv_y", "agent_m", "in-progress", JSONObject())
        val out = control().end("agent_m")
        assertFalse(out.ok)
        assertEquals("No live calls found for Merida. Couldn't end 1. Try \"End all calls\".", out.message)
    }

    @Test fun noLiveCalls() {
        assertEquals("No live calls found for Fergus.", control().end("agent_f").message)
    }

    @Test fun endAllHangsUpEveryLiveCall() {
        call(sidM, "in-progress")
        call(sidF, "queued", to = "+3")
        call(sidOther, "completed", to = "+4")
        assertEquals("Ended 2 calls (all agents).", control().end("all").message)
        assertEquals(setOf(sidM to "completed", sidF to "canceled"), updates.toSet())
    }

    @Test fun wrongKeyGivesPlainMessage() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(401)
        }
        assertEquals("Twilio rejected your key. Check it in Settings.", control().end("all").message)
    }
}
