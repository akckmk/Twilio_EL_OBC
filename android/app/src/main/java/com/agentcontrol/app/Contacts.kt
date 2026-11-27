package com.agentcontrol.app

import android.content.Context
import org.json.JSONObject

/**
 * Names you add by hand for phone numbers. Stored encrypted, only in this app.
 * The app never reads your phone's contacts (it doesn't even have that permission).
 */
object Contacts {
    private const val FILE = "contacts"
    @Volatile private var cache: MutableMap<String, String>? = null

    /** Compares numbers by their last 10 digits, so "+1 (315) 555-0100" == "3155550100". */
    fun key(number: String): String = number.filter { it.isDigit() }.takeLast(10)

    fun all(c: Context): Map<String, String> = load(c).toSortedMap()

    fun nameFor(c: Context, number: String): String? = if (number.isBlank()) null else load(c)[key(number)]

    /** For notifications (other apps with notification access can read them): name, or "number ending 0100". */
    fun privateLabel(c: Context, number: String): String =
        nameFor(c, number) ?: number.filter { it.isDigit() }.takeLast(4).let { if (it.isEmpty()) "unknown number" else "number ending $it" }

    /** "Dustin" if saved, otherwise the number. */
    fun label(c: Context, number: String): String = nameFor(c, number) ?: number.ifBlank { "Unknown number" }

    fun put(c: Context, number: String, name: String) {
        val m = load(c); m[key(number)] = name.trim(); save(c, m)
    }

    fun remove(c: Context, number: String) {
        val m = load(c); m.remove(key(number)); save(c, m)
    }

    @Synchronized private fun load(c: Context): MutableMap<String, String> {
        cache?.let { return it }
        val m = mutableMapOf<String, String>()
        SecureStore.readFile(c, FILE)?.let { text ->
            val o = JSONObject(text)
            for (k in o.keys()) m[k] = o.getString(k)
        }
        cache = m
        return m
    }

    @Synchronized private fun save(c: Context, m: Map<String, String>) {
        SecureStore.writeFile(c, FILE, JSONObject(m).toString())
        cache = m.toMutableMap()
    }
}
