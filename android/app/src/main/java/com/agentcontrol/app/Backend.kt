package com.agentcontrol.app

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Talks to the "agentcontrol" Twilio backend. Call these off the main thread. */
class Backend(private val settings: SecureStore.Settings) {

    data class Agent(val id: String, val name: String)
    data class Result(val ok: Boolean, val message: String)

    class BackendException(message: String) : Exception(message)

    fun agents(): List<Agent> {
        val json = get("/agents")
        if (!json.optBoolean("ok", false)) {
            throw BackendException(json.optString("message", "Could not load agents."))
        }
        val list = json.optJSONArray("agents") ?: return emptyList()
        return (0 until list.length()).map {
            val a = list.getJSONObject(it)
            Agent(a.getString("id"), a.optString("name", a.getString("id")))
        }
    }

    /** agentId is an ElevenLabs agent id, or "all". */
    fun end(agentId: String): Result {
        return try {
            val json = get("/end?agent=" + URLEncoder.encode(agentId, "UTF-8"))
            Result(json.optBoolean("ok", false), json.optString("message", "Done."))
        } catch (e: BackendException) {
            Result(false, e.message ?: "Something went wrong.")
        }
    }

    private fun get(path: String): JSONObject {
        val conn = try {
            (URL(settings.baseUrl + path).openConnection() as HttpURLConnection)
        } catch (e: Exception) {
            throw BackendException("The backend URL looks wrong. Check Settings.")
        }
        conn.connectTimeout = 10_000
        conn.readTimeout = 25_000
        conn.setRequestProperty("X-Agent-Control-Key", settings.accessKey)
        conn.setRequestProperty("Accept", "application/json")
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val json = try { JSONObject(text) } catch (e: Exception) { null }
            if (code == 401) throw BackendException("Wrong access key. Check Settings.")
            if (json == null) throw BackendException("Backend error (HTTP $code).")
            return json
        } catch (e: IOException) {
            throw BackendException("Could not reach the backend. Check your internet and the URL.")
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** Tidies what the user typed: adds https://, drops a trailing slash. */
        fun normalizeUrl(input: String): String {
            var url = input.trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
            return url.trimEnd('/')
        }
    }
}
