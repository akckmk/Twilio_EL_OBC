package com.agentcontrol.app

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Contract test: the app reads the exact JSON the backend's own tests produce
 * (android/app/src/test/resources/contract, written by backend/test). If the two
 * sides drift apart, this fails. Plus security checks on the HTTP client.
 */
class ApiTest {
    private lateinit var server: MockWebServer
    private lateinit var evil: MockWebServer
    private val key = "k".repeat(40)
    private val requests = mutableListOf<RecordedRequest>()

    private fun fixture(name: String) = File("src/test/resources/contract/$name.json").readText()

    @Before fun start() {
        server = MockWebServer(); evil = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                val name = request.requestUrl!!.pathSegments.last()
                return if (File("src/test/resources/contract/$name.json").exists()) MockResponse().setBody(fixture(name))
                else MockResponse().setResponseCode(404).setBody("""{"ok":false,"message":"nope"}""")
            }
        }
        server.start(); evil.start()
    }
    @After fun stop() { server.shutdown(); evil.shutdown() }

    private fun api() = Api(SecureStore.Settings(server.url("/").toString().trimEnd('/'), key))

    @Test fun sendsTheAccessKeyOnEveryRequest() {
        api().live(); api().calls("2026-10-27", "2026-11-27")
        assertTrue(requests.all { it.getHeader("X-Agent-Control-Key") == key })
        assertEquals("2026-10-27", requests[1].requestUrl!!.queryParameter("from"))
    }

    @Test fun readsLiveCalls() {
        val live = api().live()
        assertEquals(2, live.calls.size)
        val talking = live.calls.first { it.status == "in-progress" }
        assertEquals("Merida", talking.agentName)
        assertEquals(10.0, talking.answeredAt!! - talking.dialedAt!!, 0.001) // rang 10 s
        val ringing = live.calls.first { it.status == "ringing" }
        assertEquals("Unknown agent", ringing.agentName)
        assertNull(ringing.answeredAt)
    }

    @Test fun readsHistory() {
        val h = api().calls("2026-10-27", "2026-11-27")
        assertEquals(4, h.items.size)
        val done = h.items.last()
        assertEquals("completed", done.outcome)
        assertEquals("Merida", done.agentName)
        assertEquals(10, done.ringSecs)
        assertEquals(60, done.talkSecs)
        assertEquals(0.028, done.twilioUsd!!, 1e-9)
        assertFalse(done.twilioPricePending)
        val mine = h.items[1]
        assertEquals("ended_by_you", mine.outcome)
        assertTrue(mine.twilioPricePending)
        assertNull(mine.twilioUsd)
    }

    @Test fun readsCallDetail() {
        val d = api().call("CA" + "1".repeat(32), null)
        assertEquals("Booked a cleaning.", d.summary)
        assertEquals(2, d.transcript.size)
        assertEquals("stayed_in_window", d.evaluations[0].id)
        assertEquals("success", d.evaluations[0].value)
        assertEquals("Merida", d.call.agentName)
        assertTrue(d.hasAudio)
    }

    @Test fun readsRecordingPart() {
        val p = api().recordingPart("conv_1", 2)
        assertEquals(3, p.parts)
        assertTrue(p.bytes.isNotEmpty())
    }

    @Test fun usageAnalyticsAndHealthHaveTheFieldsTheScreensUse() {
        val u = api().usage()
        assertEquals(88000.0, u.getJSONObject("elevenlabs").getDouble("creditsLeft"), 0.0)
        assertEquals(23.45, u.getJSONObject("twilio").getDouble("balance"), 0.0)
        val a = api().analytics("2026-10-27", "2026-11-27")
        for (k in listOf("totalCalls", "answerRate", "avgTalkSecs", "callsPerDay", "busiestHours", "outcomes", "byAgent", "totals")) assertTrue(k, a.has(k))
        for (k in listOf("byDay", "byWeek", "byMonth")) assertTrue(a.getJSONObject("totals").getJSONArray(k).getJSONObject(0).has("totalUsd"))
        for (o in Format.OUTCOMES) assertTrue(o, a.getJSONObject("outcomes").has(o))
        assertTrue(api().health().getJSONArray("checks").length() > 0)
    }

    @Test fun backendErrorsComeThroughAsPlainMessages() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(502)
                .setBody("""{"ok":false,"message":"The ElevenLabs API key is missing the \"user_read\" permission.","missingPermission":"user_read"}""")
        }
        val e = runCatching { api().usage() }.exceptionOrNull() as Api.ApiException
        assertEquals("user_read", e.missingPermission)
        assertTrue(e.message!!.contains("user_read"))
    }

    @Test fun wrongKeySaysSo() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(401).setBody("""{"ok":false}""")
        }
        assertEquals("Wrong access key. Check Settings.", api().end("all").message)
    }

    @Test fun neverFollowsRedirectsWithTheKey() {
        evil.enqueue(MockResponse().setBody("{}"))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(302).setHeader("Location", evil.url("/steal"))
        }
        runCatching { api().live() }
        assertNull(evil.takeRequest(1, TimeUnit.SECONDS))
    }

    @Test fun formattingIsEasternAndReadable() {
        assertEquals("1:15", Format.duration(75))
        assertEquals("1:01:40", Format.duration(3700))
        assertEquals("Thu Nov 26, 7:37 AM ET", Format.dateTime(1795696624.0))
        assertEquals("Hung up by you", Format.outcome("ended_by_you"))
        assertEquals(Contacts.key("+1 (315) 555-0100"), Contacts.key("3155550100"))
    }
}
