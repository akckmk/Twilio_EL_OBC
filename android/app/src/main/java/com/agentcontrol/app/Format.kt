package com.agentcontrol.app

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Everything is shown in Eastern time, as asked. */
object Format {
    val EASTERN: ZoneId = ZoneId.of("America/New_York")
    private val dateTime = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.US).withZone(EASTERN)
    private val date = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US).withZone(EASTERN)
    val isoDate: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(EASTERN)

    fun dateTime(unix: Double) = dateTime.format(Instant.ofEpochMilli((unix * 1000).toLong())) + " ET"
    fun date(unix: Double) = date.format(Instant.ofEpochMilli((unix * 1000).toLong()))
    fun today(): String = isoDate.format(Instant.now())
    fun daysAgo(n: Long): String = isoDate.format(Instant.now().minusSeconds(n * 86400))

    /** 75 -> "1:15", 3700 -> "1:01:40". */
    fun duration(secs: Int?): String {
        if (secs == null) return "—"
        val h = secs / 3600; val m = (secs % 3600) / 60; val s = secs % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun usd(v: Double?): String = if (v == null) "—" else String.format(Locale.US, "$%.2f", v)
    fun usdPrecise(v: Double?): String = if (v == null) "—" else String.format(Locale.US, "$%.4f", v)
    fun credits(v: Double?): String = if (v == null) "—" else String.format(Locale.US, "%,d", v.toLong())

    fun outcome(o: String) = when (o) {
        "completed" -> "Completed"
        "no_answer" -> "No answer"
        "busy" -> "Busy"
        "failed" -> "Failed"
        "ended_by_you" -> "Hung up by you"
        "in_progress" -> "In progress"
        else -> o
    }
    val OUTCOMES = listOf("completed", "no_answer", "busy", "failed", "ended_by_you", "in_progress")

    fun status(s: String) = when (s) {
        "queued" -> "Queued"
        "ringing" -> "Ringing"
        "in-progress" -> "Answered · talking"
        else -> s
    }

    fun ago(secs: Long): String = when {
        secs < 2 -> "just now"
        secs < 60 -> "$secs seconds ago"
        secs < 3600 -> "${secs / 60} min ago"
        else -> "${secs / 3600} h ago"
    }
}
