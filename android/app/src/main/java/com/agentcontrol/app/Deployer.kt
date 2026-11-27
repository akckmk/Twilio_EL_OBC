package com.agentcontrol.app

import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * One-time setup: deploys the "agentcontrol" backend into YOUR Twilio account, straight
 * from the phone, using the same steps as Twilio's own deploy tool (@twilio-labs/serverless-api):
 * service -> environment -> upload functions -> variables -> build -> deploy.
 *
 * The Twilio Auth Token and ElevenLabs key are used only in memory, during this one run.
 * They are never written to the phone. The ElevenLabs key is handed to the backend
 * (as a private Twilio environment variable) and the app keeps only the backend URL
 * and a newly generated access key.
 */
class Deployer(
    private val accountSid: String,
    private val authToken: String,
    private val files: List<FunctionFile>,
    private val dependencies: JSONArray,
    private val http: OkHttpClient = defaultClient,
    private val serverless: String = "https://serverless.twilio.com/v1",
    private val upload: String = "https://serverless-upload.twilio.com/v1",
    private val sync: String = "https://sync.twilio.com/v1",
    private val api: String = "https://api.twilio.com/2010-04-01",
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    private val allowedHosts: Set<String> = TWILIO_HOSTS,
) {
    data class FunctionFile(val name: String, val path: String, val visibility: String, val content: ByteArray)
    data class Result(val baseUrl: String, val accessKey: String)
    class DeployException(message: String) : Exception(message)

    private val auth = Credentials.basic(accountSid, authToken)

    /** [progress] gets plain-English steps for the screen. */
    fun deploy(elevenLabsKey: String, progress: (String) -> Unit): Result {
        require(Regex("^AC[0-9a-fA-F]{32}$").matches(accountSid)) { "Bad Account SID" }

        progress("Checking your Twilio login…")
        get("$api/Accounts/$accountSid.json".toHttpUrl())

        progress("Creating the Agent Control service…")
        val service = findOrCreate("$serverless/Services", "services", { it.optString("unique_name") == SERVICE }) {
            add("UniqueName", SERVICE); add("FriendlyName", SERVICE); add("IncludeCredentials", "true"); add("UiEditable", "false")
        }.getString("sid")
        val environment = findOrCreate("$serverless/Services/$service/Environments", "environments", { it.optString("unique_name") == "production" }) {
            add("UniqueName", "production"); add("FriendlyName", "production Environment")
        }
        val envSid = environment.getString("sid")

        progress("Creating storage for call matches…")
        val syncSid = findOrCreate("$sync/Services", "services", { it.optString("friendly_name") == SERVICE }) {
            add("FriendlyName", SERVICE)
        }.getString("sid")

        val versions = mutableListOf<String>()
        val existing = list("$serverless/Services/$service/Functions", "functions")
        files.forEachIndexed { i, f ->
            progress("Uploading backend code (${i + 1} of ${files.size})…")
            val fn = existing.firstOrNull { it.optString("friendly_name") == f.name }
                ?: post("$serverless/Services/$service/Functions".toHttpUrl(), FormBody.Builder().add("FriendlyName", f.name).build())
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("Path", f.path)
                .addFormDataPart("Visibility", f.visibility)
                .addFormDataPart("Content", "${f.name}.js", f.content.toRequestBody("application/javascript".toMediaType()))
                .build()
            versions.add(post("$upload/Services/$service/Functions/${fn.getString("sid")}/Versions".toHttpUrl(), body).getString("sid"))
        }

        progress("Saving private settings…")
        val accessKey = newAccessKey()
        val vars = list("$serverless/Services/$service/Environments/$envSid/Variables", "variables")
        for ((key, value) in listOf("ELEVENLABS_API_KEY" to elevenLabsKey, "AGENT_CONTROL_KEY" to accessKey, "SYNC_SERVICE_SID" to syncSid)) {
            val form = FormBody.Builder().add("Key", key).add("Value", value).build()
            val found = vars.firstOrNull { it.optString("key") == key }
            if (found != null) post("$serverless/Services/$service/Environments/$envSid/Variables/${found.getString("sid")}".toHttpUrl(), form)
            else post("$serverless/Services/$service/Environments/$envSid/Variables".toHttpUrl(), form)
        }

        progress("Building (this takes about a minute)…")
        val build = FormBody.Builder().apply {
            versions.forEach { add("FunctionVersions", it) }
            // Exactly as Twilio's tool sends it: the JSON list wrapped in quotes.
            add("Dependencies", "\"$dependencies\"")
            add("Runtime", RUNTIME)
        }.build()
        val buildSid = post("$serverless/Services/$service/Builds".toHttpUrl(), build).getString("sid")
        val started = System.currentTimeMillis()
        while (true) {
            val status = get("$serverless/Services/$service/Builds/$buildSid/Status".toHttpUrl()).optString("status")
            if (status == "completed") break
            if (status == "failed") throw DeployException("Twilio couldn't build the backend. Try again in a few minutes.")
            if (System.currentTimeMillis() - started > 5 * 60_000) throw DeployException("Twilio's build is taking too long. Try again later.")
            pause(3000)
        }

        progress("Going live…")
        post("$serverless/Services/$service/Environments/$envSid/Deployments".toHttpUrl(), FormBody.Builder().add("BuildSid", buildSid).build())
        val domain = get("$serverless/Services/$service/Environments/$envSid".toHttpUrl()).getString("domain_name")
        return Result("https://$domain", accessKey)
    }

    // ------------------------------------------------------------ helpers

    private fun list(url: String, key: String): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        var next: String? = "$url?PageSize=50"
        var pages = 0
        while (next != null && pages++ < 10) {
            val o = get(next.toHttpUrl())
            val a = o.optJSONArray(key) ?: JSONArray()
            for (i in 0 until a.length()) out.add(a.getJSONObject(i))
            next = o.optJSONObject("meta")?.optString("next_page_url")?.takeIf { it.isNotEmpty() && it != "null" }
        }
        return out
    }

    private fun findOrCreate(url: String, key: String, match: (JSONObject) -> Boolean, form: FormBody.Builder.() -> Unit): JSONObject =
        list(url, key).firstOrNull(match) ?: post(url.toHttpUrl(), FormBody.Builder().apply(form).build())

    private fun get(url: HttpUrl) = send(Request.Builder().url(url).header("Authorization", auth).build())
    private fun post(url: HttpUrl, body: RequestBody) = send(Request.Builder().url(url).header("Authorization", auth).post(body).build())

    private fun send(req: Request): JSONObject {
        // Only Twilio's own hosts, over HTTPS.
        if (req.url.host !in allowedHosts || (allowedHosts === TWILIO_HOSTS && !req.url.isHttps)) {
            throw DeployException("Refusing to send your Twilio login to ${req.url.host}.")
        }
        try {
            http.newCall(req).execute().use { res ->
                val text = res.body?.string().orEmpty()
                val json = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
                when {
                    res.code == 401 || res.code == 403 -> throw DeployException("Twilio didn't accept the Account SID or Auth Token. Check them and try again.")
                    res.code in 300..399 -> throw DeployException("Twilio sent an unexpected redirect. Nothing more was done.")
                    !res.isSuccessful -> throw DeployException("Twilio said: ${json.optString("message", "error ${res.code}").take(200)}")
                }
                return json
            }
        } catch (e: IOException) {
            throw DeployException("Couldn't reach Twilio. Check your internet connection.")
        }
    }

    companion object {
        const val SERVICE = "agentcontrol"
        const val RUNTIME = "node24"
        val TWILIO_HOSTS = setOf("serverless.twilio.com", "serverless-upload.twilio.com", "sync.twilio.com", "api.twilio.com")

        val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS)
            .build()

        /** 48 random hex characters (192 bits) from the phone's secure random generator. */
        fun newAccessKey(): String {
            val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /** Reads the backend code bundled into the APK (assets/backend). */
        fun bundledFiles(read: (String) -> ByteArray, listDir: (String) -> List<String>): Pair<List<FunctionFile>, JSONArray> {
            val files = listDir("backend/functions").filter { it.endsWith(".js") }.sorted().map { file ->
                val private = file.endsWith(".private.js")
                val name = file.removeSuffix(".js").removeSuffix(".private")
                FunctionFile(name, "/$name", if (private) "private" else "public", read("backend/functions/$file"))
            }
            val pkg = JSONObject(String(read("backend/package.json")))
            val deps = JSONArray()
            pkg.optJSONObject("dependencies")?.let { d -> for (k in d.keys()) deps.put(JSONObject().put("name", k).put("version", d.getString(k))) }
            return files to deps
        }
    }
}
