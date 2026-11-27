package com.agentcontrol.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.bottomnavigation.BottomNavigationView
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.File
import javax.crypto.KeyGenerator

/**
 * Opens every screen of the real app on a simulated phone (Robolectric), talking to a
 * fake backend that serves the backend's own contract data. Catches crashes and
 * screens that don't show what they should. No real calls, no real keys.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AgentControlApp::class)
@org.robolectric.annotation.SQLiteMode(org.robolectric.annotation.SQLiteMode.Mode.LEGACY)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.LEGACY)
class ScreensTest {
    private lateinit var server: MockWebServer
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val paths = mutableListOf<String>()

    @Before fun start() {
        // A real phone grants these at install; the simulator doesn't unless told.
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(android.Manifest.permission.HIDE_OVERLAY_WINDOWS, android.Manifest.permission.POST_NOTIFICATIONS)
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        SecureStore.keyProvider = { key } // the simulator has no security chip
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                paths.add(url.encodedPath + (url.query?.let { "?$it" } ?: ""))
                val time = url.queryParameter("id") == "CA" + "5".repeat(32)
                return when (val name = if (time) "call_time" else url.pathSegments.last()) {
                    "agents" -> MockResponse().setBody("""{"ok":true,"agents":[{"id":"agent_f","name":"Fergus"},{"id":"agent_m","name":"Merida"}]}""")
                    "end" -> MockResponse().setBody("""{"ok":true,"message":"Ended 1 call for Merida."}""")
                    else -> File("src/test/resources/contract/$name.json").takeIf { it.exists() }
                        ?.let { MockResponse().setBody(it.readText()) } ?: MockResponse().setResponseCode(404).setBody("{}")
                }
            }
        }
        server.start()
        SecureStore.save(ctx, SecureStore.Settings(server.url("/").toString().trimEnd('/'), "k".repeat(40)))
    }

    @After fun stop() = server.shutdown()

    // ---- helpers

    private fun texts(v: View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun screen(a: Activity) = texts(a.window.decorView).joinToString("\n")

    /** Lets background network work finish and the UI update. */
    private fun waitFor(a: Activity, what: String, timeoutMs: Long = 10_000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            if (screen(a).contains(what)) return
            Thread.sleep(50)
        }
        throw AssertionError("'$what' never appeared. Screen was:\n${screen(a)}")
    }

    private fun findButton(v: View, label: String): View? = when {
        v is TextView && v.text.toString() == label -> v
        v is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { findButton(v.getChildAt(it), label) }
        else -> null
    }

    // ---- tests

    @Test fun agentsTabStillEndsCallsLikeBefore() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        waitFor(a, "Merida")
        assertTrue(screen(a).contains("End all calls"))
        // Tap Merida's red button (the second "End call" button; Fergus is first).
        val root = a.window.decorView
        val buttons = mutableListOf<View>()
        fun collect(v: View) { if (v is TextView && v.text == "End call") buttons.add(v); if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i)) }
        collect(root)
        assertEquals(2, buttons.size)
        buttons[1].performClick()
        waitFor(a, "Ended 1 call for Merida.")
        assertEquals("Ended 1 call for Merida.", ShadowToast.getTextOfLatestToast())
        assertTrue(paths.any { it == "/end?agent=agent_m" })
    }

    @Test fun liveTabShowsCardsTimersAndUnknownAgent() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.findViewById<BottomNavigationView>(R.id.bottom_nav).selectedItemId = R.id.nav_live
        waitFor(a, "Talking")
        val s = screen(a)
        assertTrue(s.contains("Merida"))
        assertTrue(s.contains("Unknown agent"))
        assertTrue(s.contains("Ringing for"))
        assertTrue(s.contains("Answered · talking"))
        assertTrue(s.contains("rang 0:10"))
        assertTrue(s.contains("Last updated"))
    }

    @Test fun liveShowsSavedContactNames() {
        Contacts.put(ctx, "+1 555 000 2222", "Dustin")
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.findViewById<BottomNavigationView>(R.id.bottom_nav).selectedItemId = R.id.nav_live
        waitFor(a, "Calling Dustin")
    }

    // A tall screen so the scrolling list draws all 4 cards at once.
    @Config(qualifiers = "w411dp-h3000dp")
    @Test fun historyTabListsCallsWithOutcomesAndPendingPrices() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.findViewById<BottomNavigationView>(R.id.bottom_nav).selectedItemId = R.id.nav_history
        waitFor(a, "Hung up by you")
        val s = screen(a)
        assertTrue(s.contains("4 of 4 calls"))
        assertTrue(s.contains("2 answered (50%)"))   // summary of what's shown
        assertTrue(s.contains("Today") && s.contains("7 days") && s.contains("30 days"))
        assertTrue(s.contains("Busy"))
        assertTrue(s.contains("No answer"))
        assertTrue(s.contains("Twilio pending"))
        assertTrue(s.contains("Twilio $0.0280"))
        assertTrue(s.contains(" ET"))
        // The list is saved encrypted on the phone for instant display next time.
        assertTrue(SecureStore.readFile(ctx, "history")!!.contains("ended_by_you"))
        assertTrue(!File(ctx.filesDir, "history.enc").readText().contains("ended_by_you"))
    }

    @Test fun usageTabShowsCreditsBalanceAndTotals() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.findViewById<BottomNavigationView>(R.id.bottom_nav).selectedItemId = R.id.nav_usage
        waitFor(a, "88,000 left")
        val s = screen(a)
        assertTrue(s.contains("$23.45"))
        assertTrue(s.contains("$6.70"))
        assertTrue(s.contains("Cost by agent"))
        assertTrue(s.contains("By week"))
    }

    @Test fun analyticsTabShowsChartsAndOutcomes() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.findViewById<BottomNavigationView>(R.id.bottom_nav).selectedItemId = R.id.nav_analytics
        waitFor(a, "4 calls")
        val s = screen(a)
        assertTrue(s.contains("50%"))
        assertTrue(s.contains("Busiest times of day"))
        assertTrue(s.contains("Hung up by you"))
    }

    @Test fun callDetailShowsSummaryTranscriptAndAnalysis() {
        val i = Intent(ctx, CallDetailActivity::class.java)
            .putExtra(CallDetailActivity.EXTRA_SID, "CA" + "1".repeat(32))
        val a = Robolectric.buildActivity(CallDetailActivity::class.java, i).setup().get()
        waitFor(a, "Booked a cleaning.")
        val s = screen(a)
        assertTrue(s.contains("Transcript"))
        assertTrue(s.contains("Hello"))
        assertTrue(s.contains("stayed_in_window: success"))
        assertTrue(s.contains("Play"))
        // Player tools: slider + time, jump 15 s, speed, tap a line to jump, name this number.
        assertTrue(s.contains("« 15s") && s.contains("15s »") && s.contains("1×"))
        assertTrue(s.contains("0:00 / —"))
        assertTrue(s.contains("Tap a line to hear that moment."))
        assertTrue(s.contains("Add a name for this number"))
        val speed = findButton(a.window.decorView, "1×")!!
        speed.performClick(); assertTrue(screen(a).contains("1.5×"))
        speed.performClick(); assertTrue(screen(a).contains("2×"))
    }

    @Test fun settingsContactsAndWidgetSetupOpen() {
        val st = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        assertTrue(screen(st).contains("Saved (hidden)"))
        assertTrue(!screen(st).contains("k".repeat(40))) // the access key is never shown
        findButton(st.window.decorView, "Check backend setup")!!.performClick()
        waitFor(st, "Everything is set up.")

        val c = Robolectric.buildActivity(ContactsActivity::class.java).setup().get()
        assertTrue(screen(c).contains("Add a name"))

        // Another app opening widget setup with a widget number that isn't ours is refused.
        val w = Robolectric.buildActivity(WidgetConfigActivity::class.java,
            Intent().putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, 4242)).setup().get()
        assertTrue(w.isFinishing)
    }

    @Test fun upgradeFromTheOldVersionWipesProviderKeys() {
        // Simulate settings saved by the previous version (Twilio/ElevenLabs keys on the phone).
        val prefs = ctx.getSharedPreferences("secure_settings", Context.MODE_PRIVATE)
        SecureStore.writeFile(ctx, "probe", """{"accountSid":"AC1","apiKeySid":"SK1","apiKeySecret":"s","elevenLabsKey":"e"}""")
        val sealed = File(ctx.filesDir, "probe.enc").readText()
        prefs.edit().putString("blob_v2", sealed).commit()
        assertNull(SecureStore.load(ctx))
        assertNull(prefs.getString("blob_v2", null)) // wiped, not just ignored
        // ...and the app sends you to Settings instead of crashing.
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertTrue(a.isFinishing)
        assertEquals(SettingsActivity::class.java.name, shadowOf(a).nextStartedActivity.component!!.className)
    }

    // ---------------------------------------------------------------- red-team (pen test)

    @Test fun attackOtherAppsCannotCrashTheAppWithABogusTab() {
        val i = Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, 0x7f0bdead)
        val a = Robolectric.buildActivity(MainActivity::class.java, i).setup().get()
        waitFor(a, "Merida") // falls back to the Agents tab
        val wrongType = Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, "evil")
        assertEquals(R.id.nav_agents, MainActivity.safeTab(wrongType))
    }

    @Test fun attackFingerprintLockHidesEverythingUntilUnlocked() {
        Prefs.setFingerprintLock(ctx, true)
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        repeat(40) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(50) }
        val content = a.findViewById<View>(android.R.id.content)
        assertTrue("content must be hidden or the app closed", content.visibility != View.VISIBLE || a.isFinishing)
        Prefs.setFingerprintLock(ctx, false)
    }

    @Test fun attackNotificationsNeverShowFullPhoneNumbers() {
        assertEquals("number ending 2222", Contacts.privateLabel(ctx, "+15550002222"))
        Contacts.put(ctx, "+15550002222", "Dustin")
        assertEquals("Dustin", Contacts.privateLabel(ctx, "+15550002222"))
    }

    @Test fun attackTimeMatchedTranscriptCarriesAWarning() {
        val i = Intent(ctx, CallDetailActivity::class.java).putExtra(CallDetailActivity.EXTRA_SID, "CA" + "5".repeat(32))
        val a = Robolectric.buildActivity(CallDetailActivity::class.java, i).setup().get()
        waitFor(a, "matched to the call by start time")
    }

    // ---------------------------------------------------------------- one-time setup

    @Test fun setupDeploysThenForgetsTheTwilioAndElevenLabsKeys() {
        val token = "T0k3n".repeat(7) + "xy"        // 37 chars
        val elKey = "sk_secretElevenLabs_" + "q".repeat(30)
        val twilio = MockWebServer()
        val bodies = mutableListOf<String>()
        twilio.dispatcher = object : Dispatcher() {
            var n = 0
            override fun dispatch(request: RecordedRequest): MockResponse {
                bodies.add(request.body.readUtf8())
                val p = request.requestUrl!!.encodedPath
                val ok = { o: String -> MockResponse().setBody(o) }
                return when {
                    request.method == "GET" && (p.endsWith("/Services") || p.endsWith("/Environments") || p.endsWith("/Functions") || p.endsWith("/Variables")) ->
                        ok("""{"services":[],"environments":[],"functions":[],"variables":[]}""")
                    p.endsWith("/Status") -> ok("""{"status":"completed"}""")
                    p.endsWith("/Environments/ZE1") -> ok("""{"domain_name":"agentcontrol-test.invalid"}""")
                    p.endsWith("/Environments") -> ok("""{"sid":"ZE1"}""")
                    else -> ok("""{"sid":"ZX${n++}"}""")
                }
            }
        }
        twilio.start()
        SetupActivity.deployerFactory = { sid, tok, files, deps ->
            Deployer(sid, tok, files, deps,
                serverless = twilio.url("/sl").toString().trimEnd('/'), upload = twilio.url("/up").toString().trimEnd('/'),
                sync = twilio.url("/sync").toString().trimEnd('/'), api = twilio.url("/api").toString().trimEnd('/'),
                pause = {}, allowedHosts = setOf(twilio.hostName))
        }
        try {
            SecureStore.deleteFile(ctx, "history")
            val a = Robolectric.buildActivity(SetupActivity::class.java).setup().get()
            val edits = mutableListOf<android.widget.EditText>()
            fun collect(v: View) { if (v is android.widget.EditText) edits.add(v); if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i)) }
            collect(a.window.decorView)
            assertEquals(3, edits.size)
            edits[0].setText("AC" + "b".repeat(32)); edits[1].setText(token); edits[2].setText(elKey)
            findButton(a.window.decorView, "Set up")!!.performClick()
            val end = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < end && !screen(a).contains("Open Agent Control")) {
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(5)); Thread.sleep(30)
            }
            val s = screen(a)
            assertTrue(s, s.contains("Open Agent Control"))
            assertTrue(s.contains("Uploading backend code (10 of 10)"))
            // The phone now holds only the backend URL and a new access key...
            val saved = SecureStore.load(ctx)!!
            assertEquals("https://agentcontrol-test.invalid", saved.baseUrl) // .invalid never resolves: no real host is contacted
            assertTrue(Regex("^[0-9a-f]{48}$").matches(saved.accessKey))
            // ...the ElevenLabs key went to Twilio as a private variable...
            assertTrue(bodies.any { it.contains("ELEVENLABS_API_KEY") && it.contains(elKey) })
            // ...and neither secret is stored anywhere on the phone, nor left in the boxes.
            val dataDir = ctx.filesDir.parentFile!!
            val stored = dataDir.walkTopDown().filter { it.isFile }.map { it.readBytes().toString(Charsets.ISO_8859_1) }.toList()
            assertTrue(stored.none { it.contains(token) || it.contains(elKey) })
            assertTrue(stored.none { it.contains(saved.accessKey) }) // even the access key is only stored encrypted
            assertEquals("", edits[1].text.toString()); assertEquals("", edits[2].text.toString())
        } finally {
            twilio.shutdown()
        }
    }
}
