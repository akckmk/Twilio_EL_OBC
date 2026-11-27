package com.agentcontrol.app

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Red-team tests: a hostile or compromised API server tries to steal the keys
 * or make the app act on things it shouldn't. Every test must stay green.
 */
class AttackTest {

    private val settings = SecureStore.Settings(
        "AC" + "0".repeat(32), "SK" + "1".repeat(32), "s".repeat(32), "el_" + "k".repeat(30)
    )
    private lateinit var api: MockWebServer
    private lateinit var evil: MockWebServer

    @Before fun start() { api = MockWebServer(); evil = MockWebServer(); api.start(); evil.start() }
    @After fun stop() { api.shutdown(); evil.shutdown() }

    private fun control() = CallControl(
        settings,
        elevenLabsBase = api.url("/el").toString().trimEnd('/'),
        twilioBase = api.url("/tw").toString().trimEnd('/'),
        pause = {},
    )

    private fun respond(handler: (RecordedRequest) -> MockResponse) {
        api.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = handler(request) }
    }

    /** A redirect to another server must not carry the ElevenLabs key along. */
    @Test fun elevenLabsKeyIsNotLeakedByRedirect() {
        evil.enqueue(MockResponse().setBody("""{"agents":[],"has_more":false}"""))
        respond { MockResponse().setResponseCode(302).setHeader("Location", evil.url("/steal").toString()) }
        runCatching { control().agents() }
        val stolen = evil.takeRequest(1, TimeUnit.SECONDS)
        assertNull("request followed the redirect to another server", stolen)
    }

    /** Same for the Twilio key. */
    @Test fun twilioKeyIsNotLeakedByRedirect() {
        evil.enqueue(MockResponse().setBody("""{"calls":[]}"""))
        respond { MockResponse().setResponseCode(307).setHeader("Location", evil.url("/steal").toString()) }
        control().end("all")
        assertNull(evil.takeRequest(1, TimeUnit.SECONDS))
    }

    /** A poisoned conversation id must not let the server steer requests to other API paths. */
    @Test fun poisonedConversationIdIsIgnored() {
        val paths = mutableListOf<String>()
        respond { r ->
            paths.add(r.path!!)
            when (r.requestUrl!!.encodedPath) {
                "/el/v1/convai/agents/agent_m" -> MockResponse().setBody("""{"name":"Merida"}""")
                "/el/v1/convai/conversations" -> MockResponse().setBody(JSONObject().put("has_more", false)
                    .put("conversations", JSONArray().put(JSONObject()
                        .put("conversation_id", "../../agents/agent_m?delete=1").put("status", "in-progress"))).toString())
                else -> MockResponse().setResponseCode(404)
            }
        }
        control().end("agent_m")
        assertTrue("unexpected request: $paths", paths.none { it.contains("delete") || it.contains("..") })
        assertTrue(paths.none { it.startsWith("/el/v1/convai/agents/agent_m?") })
    }

    /** A poisoned call SID in a Twilio list reply must not be used in a URL. */
    @Test fun poisonedCallSidIsIgnored() {
        val posts = mutableListOf<String>()
        respond { r ->
            if (r.method == "POST") posts.add(r.path!!)
            MockResponse().setBody("""{"calls":[{"sid":"../../IncomingPhoneNumbers/PN1","status":"in-progress","from":"+1","to":"+2"}]}""")
        }
        control().end("all")
        assertTrue("posted to: $posts", posts.isEmpty())
    }

    /** Error messages shown on screen must never contain a key. */
    @Test fun errorsNeverShowKeys() {
        respond { MockResponse().setResponseCode(500).setBody("key=" + settings.apiKeySecret) }
        val msg = control().end("all").message
        assertFalse(msg.contains(settings.apiKeySecret))
        assertFalse(msg.contains(settings.elevenLabsKey))
        assertEquals("Twilio had a problem (error 500). Try again.", msg)
    }
}
