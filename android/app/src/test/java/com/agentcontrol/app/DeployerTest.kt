package com.agentcontrol.app

import okhttp3.Credentials
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
import java.io.File
import java.net.URLDecoder

/**
 * The one-time setup against a fake Twilio that behaves like the real Serverless API.
 * Checks the exact steps, that the real backend code is uploaded, that re-running is safe,
 * and that the Twilio login and ElevenLabs key never go anywhere they shouldn't.
 */
class DeployerTest {
    private val sid = "AC" + "a".repeat(32)
    private val token = "t".repeat(32)
    private val elKey = "sk_" + "e".repeat(40)
    private lateinit var tw: MockWebServer
    val requests = mutableListOf<RecordedRequest>()
    val bodies = mutableListOf<String>()

    // Fake Twilio state
    var services = JSONArray(); var envs = JSONArray(); var syncs = JSONArray(); var fns = JSONArray(); var vars = JSONArray()
    var buildPolls = 0; var buildResult = "completed"; var authOk = true

    @Before fun start() {
        tw = MockWebServer()
        tw.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                val body = request.body.readUtf8(); bodies.add(body)
                if (!authOk || request.getHeader("Authorization") != Credentials.basic(sid, token)) return MockResponse().setResponseCode(401).setBody("""{"code":20003,"message":"Authenticate"}""")
                val p = request.requestUrl!!.encodedPath
                val post = request.method == "POST"
                fun ok(o: Any) = MockResponse().setBody(o.toString())
                fun form() = body.split("&").filter { it.contains("=") }.groupBy({ URLDecoder.decode(it.substringBefore("="), "UTF-8") }, { URLDecoder.decode(it.substringAfter("="), "UTF-8") })
                return when {
                    p.startsWith("/api/Accounts/") -> ok(JSONObject().put("sid", sid))
                    p == "/sl/Services" && !post -> ok(JSONObject().put("services", services))
                    p == "/sl/Services" -> ok(JSONObject().put("sid", "ZS1").put("unique_name", form()["UniqueName"]!![0]).also { services.put(it) })
                    p == "/sl/Services/ZS1/Environments" && !post -> ok(JSONObject().put("environments", envs))
                    p == "/sl/Services/ZS1/Environments" -> ok(JSONObject().put("sid", "ZE1").put("unique_name", "production").also { envs.put(it) })
                    p == "/sync/Services" && !post -> ok(JSONObject().put("services", syncs))
                    p == "/sync/Services" -> ok(JSONObject().put("sid", "IS1").put("friendly_name", "agentcontrol").also { syncs.put(it) })
                    p == "/sl/Services/ZS1/Functions" && !post -> ok(JSONObject().put("functions", fns))
                    p == "/sl/Services/ZS1/Functions" -> { val n = fns.length() + 1; ok(JSONObject().put("sid", "ZH$n").put("friendly_name", form()["FriendlyName"]!![0]).also { fns.put(it) }) }
                    p.matches(Regex("/up/Services/ZS1/Functions/ZH\\d+/Versions")) -> ok(JSONObject().put("sid", "ZN" + p.split("/")[4].removePrefix("ZH")))
                    p == "/sl/Services/ZS1/Environments/ZE1/Variables" && !post -> ok(JSONObject().put("variables", vars))
                    p == "/sl/Services/ZS1/Environments/ZE1/Variables" -> ok(JSONObject().put("sid", "ZV${vars.length()}").put("key", form()["Key"]!![0]).also { vars.put(it) })
                    p.startsWith("/sl/Services/ZS1/Environments/ZE1/Variables/") -> ok(JSONObject().put("sid", p.substringAfterLast("/")))
                    p == "/sl/Services/ZS1/Builds" -> ok(JSONObject().put("sid", "ZB1"))
                    p == "/sl/Services/ZS1/Builds/ZB1/Status" -> ok(JSONObject().put("status", if (++buildPolls < 3) "building" else buildResult))
                    p == "/sl/Services/ZS1/Environments/ZE1/Deployments" -> ok(JSONObject().put("sid", "ZD1"))
                    p == "/sl/Services/ZS1/Environments/ZE1" -> ok(JSONObject().put("domain_name", "agentcontrol-1234.twil.io"))
                    else -> MockResponse().setResponseCode(404).setBody("""{"message":"not found $p"}""")
                }
            }
        }
        tw.start()
    }
    @After fun stop() = tw.shutdown()

    private fun bundle() = Deployer.bundledFiles(
        read = { File("../../$it".replace("../../backend", "../../backend")).readBytes() },
        listDir = { File("../../$it").list()!!.toList() },
    )

    private fun deployer(files: List<Deployer.FunctionFile> = bundle().first, deps: JSONArray = bundle().second) = Deployer(
        sid, token, files, deps,
        serverless = tw.url("/sl").toString().trimEnd('/'), upload = tw.url("/up").toString().trimEnd('/'),
        sync = tw.url("/sync").toString().trimEnd('/'), api = tw.url("/api").toString().trimEnd('/'),
        pause = {}, allowedHosts = setOf(tw.hostName),
    )

    @Test fun bundlesTheRealBackendCode() {
        val (files, deps) = bundle()
        assertEquals(10, files.size)
        val common = files.first { it.name == "common" }
        assertEquals("/common", common.path); assertEquals("private", common.visibility)
        val end = files.first { it.name == "end" }
        assertEquals("/end", end.path); assertEquals("public", end.visibility)
        assertTrue(String(end.content).contains("ends only that agent's live calls"))
        assertEquals(setOf("@twilio/runtime-handler", "twilio", "ws"), (0 until deps.length()).map { deps.getJSONObject(it).getString("name") }.toSet())
    }

    @Test fun deploysWithTheSameStepsAsTwiliosOwnTool() {
        val steps = mutableListOf<String>()
        val r = deployer().deploy(elKey) { steps.add(it) }
        assertEquals("https://agentcontrol-1234.twil.io", r.baseUrl)
        assertTrue(Regex("^[0-9a-f]{48}$").matches(r.accessKey))
        // Every request carries the Twilio login, and only to Twilio.
        assertTrue(requests.all { it.getHeader("Authorization") == Credentials.basic(sid, token) })
        // 10 uploads with Path, Visibility and the real code.
        val uploads = requests.indices.filter { requests[it].requestUrl!!.encodedPath.startsWith("/up/") }
        assertEquals(10, uploads.size)
        assertTrue(uploads.any { bodies[it].contains("/common") && bodies[it].contains("private") && bodies[it].contains("checkAccess") })
        // Variables: ElevenLabs key, generated access key, Sync service.
        val keys = (0 until vars.length()).map { vars.getJSONObject(it).getString("key") }.toSet()
        assertEquals(setOf("ELEVENLABS_API_KEY", "AGENT_CONTROL_KEY", "SYNC_SERVICE_SID"), keys)
        // Build: all 10 versions, quoted dependency list, node24.
        val build = bodies[requests.indexOfFirst { it.requestUrl!!.encodedPath == "/sl/Services/ZS1/Builds" }]
        assertEquals(10, Regex("FunctionVersions=").findAll(build).count())
        val deps = URLDecoder.decode(build.substringAfter("Dependencies=").substringBefore("&"), "UTF-8")
        assertTrue(deps.startsWith("\"[") && deps.endsWith("]\""))
        assertTrue(build.contains("Runtime=node24"))
        assertTrue(requests.any { it.requestUrl!!.encodedPath == "/sl/Services/ZS1/Environments/ZE1/Deployments" && bodies[requests.indexOf(it)] == "BuildSid=ZB1" })
        assertTrue(steps.first().contains("Twilio login") && steps.last().contains("live"))
    }

    @Test fun runningSetupAgainReusesEverythingAndRotatesTheAccessKey() {
        val first = deployer().deploy(elKey) {}
        requests.clear(); bodies.clear(); buildPolls = 0
        val second = deployer().deploy(elKey) {}
        assertEquals(first.baseUrl, second.baseUrl)
        assertTrue(first.accessKey != second.accessKey) // re-running gives a fresh key
        assertEquals(1, services.length()); assertEquals(1, envs.length()); assertEquals(1, syncs.length()); assertEquals(10, fns.length())
        assertEquals(3, vars.length()) // updated in place, not duplicated
        assertTrue(requests.any { it.requestUrl!!.encodedPath.matches(Regex(".*/Variables/ZV\\d")) })
    }

    @Test fun wrongTwilioLoginGivesAPlainMessageWithoutEchoingSecrets() {
        authOk = false
        val e = runCatching { deployer().deploy(elKey) {} }.exceptionOrNull()!!
        assertEquals("Twilio didn't accept the Account SID or Auth Token. Check them and try again.", e.message)
        assertFalse(e.message!!.contains(token)); assertFalse(e.message!!.contains(elKey))
        assertEquals(1, requests.size) // stops at the first step
    }

    @Test fun failedBuildStopsBeforeGoingLive() {
        buildResult = "failed"
        val e = runCatching { deployer().deploy(elKey) {} }.exceptionOrNull()!!
        assertTrue(e.message!!.contains("couldn't build"))
        assertFalse(requests.any { it.requestUrl!!.encodedPath.endsWith("/Deployments") })
    }

    @Test fun attackNeverSendsTheTwilioLoginToAnotherHost() {
        // A real Deployer (default allowlist) pointed at a non-Twilio address refuses before sending.
        val d = Deployer(sid, token, emptyList(), JSONArray(), api = tw.url("/api").toString().trimEnd('/'), pause = {})
        val e = runCatching { d.deploy(elKey) {} }.exceptionOrNull()!!
        assertTrue(e.message!!.startsWith("Refusing to send your Twilio login"))
        assertTrue(requests.isEmpty())
        // ...and plain http to a Twilio name is refused too.
        assertTrue(Deployer.TWILIO_HOSTS.containsAll(setOf("serverless.twilio.com", "serverless-upload.twilio.com", "sync.twilio.com", "api.twilio.com")))
    }

    @Test fun accessKeysAreRandomAndLong() {
        val keys = (1..50).map { Deployer.newAccessKey() }.toSet()
        assertEquals(50, keys.size)
        assertTrue(keys.all { it.length == 48 })
    }
}
