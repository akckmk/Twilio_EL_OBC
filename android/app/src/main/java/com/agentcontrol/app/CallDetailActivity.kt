package com.agentcontrol.app

import android.graphics.Typeface
import android.media.MediaPlayer
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.agentcontrol.app.Ui.row
import com.agentcontrol.app.Ui.small
import com.agentcontrol.app.Ui.text
import com.agentcontrol.app.Ui.title
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One call: summary, full transcript, recording player, and ElevenLabs' analysis.
 * Player: slider, current time / length, jump 15 s back or forward, speed 1x/1.5x/2x,
 * and tap any transcript line to jump there (the line being spoken is highlighted).
 * The recording is fetched through the backend into a private temporary file and
 * deleted when you leave this screen.
 */
class CallDetailActivity : SecureActivity() {
    private lateinit var body: LinearLayout
    private var player: MediaPlayer? = null
    private var audioFile: File? = null
    private var loaded = false
    private var loading = false
    private var conversationId: String? = null

    // Player controls
    private var playButton: MaterialButton? = null
    private var seek: SeekBar? = null
    private var timeLabel: TextView? = null
    private var status: TextView? = null
    private var speedButton: MaterialButton? = null
    private var userDragging = false
    private var speed = 1f
    private var pendingSeekMs: Int? = null

    // Transcript lines, for tap-to-jump and highlighting
    private val lines = mutableListOf<Pair<TextView, Int>>() // view, start time in ms
    private var highlighted: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.call_details)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 16), Ui.dp(context, 8), Ui.dp(context, 16), Ui.dp(context, 32))
        }
        val scroll = ScrollView(this).apply { addView(body) }
        setContentView(scroll)
        scroll.padForSystemBars()

        // Moves the slider, the time label and the transcript highlight while playing.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) { tick(); delay(250) }
            }
        }
    }

    override fun onUnlocked() {
        if (loaded) return
        loaded = true
        val sid = intent.getStringExtra(EXTRA_SID) ?: return finish()
        val conv = intent.getStringExtra(EXTRA_CONVERSATION)
        val s = SecureStore.load(this) ?: return finish()
        body.small(getString(R.string.loading))
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Api(s).call(sid, conv) } }
            body.removeAllViews()
            r.onSuccess { render(it) }.onFailure { body.text(it.message) }
        }
    }

    private fun render(d: Api.Detail) {
        val c = d.call
        val who = Contacts.nameFor(this, c.number)
        conversationId = d.conversationId
        body.addView(Ui.card(this) {
            title("${c.agentName} → ${who ?: c.number}")
            if (who != null) small(c.number)
            text(Format.dateTime(c.startTime))
            row(getString(R.string.outcome), Format.outcome(c.outcome))
            row(getString(R.string.rang), Format.duration(c.ringSecs) + " " + getString(R.string.estimate))
            row(getString(R.string.lasted), Format.duration(c.talkSecs))
            row(getString(R.string.credits), Format.credits(c.credits))
            row(getString(R.string.elevenlabs_cost), Format.usd(c.elevenUsd))
            row(getString(R.string.twilio_cost), if (c.twilioPricePending) getString(R.string.pending) else Format.usdPrecise(c.twilioUsd))
            d.terminationReason?.let { row(getString(R.string.how_it_ended), it) }
            if (c.number.isNotBlank()) {
                addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = getString(if (who == null) R.string.add_name_for_number else R.string.edit_name_for_number)
                    setOnClickListener { nameDialog(c.number, who) }
                })
            }
        })
        if (d.conversationId == null) {
            body.addView(Ui.card(this) { small(getString(R.string.no_conversation)) })
            return
        }
        if (d.matchedBy == "time") body.addView(Ui.card(this) { small(getString(R.string.matched_by_time)) })
        if (d.hasAudio) body.addView(Ui.card(this) { playerControls(this) })
        if (d.title != null || d.summary != null) body.addView(Ui.card(this) {
            title(d.title ?: getString(R.string.summary))
            d.summary?.let { text(it) }
            d.callSuccessful?.let { small(getString(R.string.call_successful, it)) }
        })
        if (d.evaluations.isNotEmpty() || d.dataCollection.isNotEmpty()) body.addView(Ui.card(this) {
            title(getString(R.string.analysis))
            for (e in d.evaluations) { text("${e.id}: ${e.value}", bold = true); if (e.rationale.isNotBlank()) small(e.rationale) }
            for (e in d.dataCollection) { text("${e.id}: ${e.value.ifBlank { "—" }}", bold = true); if (e.rationale.isNotBlank()) small(e.rationale) }
        })
        lines.clear()
        body.addView(Ui.card(this) {
            title(getString(R.string.transcript))
            if (d.transcript.isEmpty()) small(getString(R.string.no_transcript))
            else if (d.hasAudio) small(getString(R.string.tap_line_to_jump))
            for (l in d.transcript) {
                val speaker = if (l.role == "agent") c.agentName else (who ?: getString(R.string.them))
                val ms = (l.t * 1000).toInt()
                val block = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(Ui.dp(context, 6), Ui.dp(context, 6), Ui.dp(context, 6), Ui.dp(context, 6))
                }
                block.text("$speaker  ${Format.duration(l.t.toInt())}", bold = true)
                val textView = block.text(l.text)
                if (d.hasAudio) {
                    block.isClickable = true
                    block.setOnClickListener { jumpTo(ms) }
                    block.contentDescription = getString(R.string.jump_to, Format.duration(l.t.toInt()))
                }
                addView(block)
                lines.add(textView to ms)
            }
        })
    }

    // ---------------------------------------------------------------- player

    private fun playerControls(card: LinearLayout) = with(card) {
        title(getString(R.string.recording))
        status = small(getString(R.string.recording_tap_play))
        seek = SeekBar(context).apply {
            isEnabled = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) timeLabel?.text = timeText(value, sb.max)
                }
                override fun onStartTrackingTouch(sb: SeekBar) { userDragging = true }
                override fun onStopTrackingTouch(sb: SeekBar) { userDragging = false; player?.seekTo(sb.progress) }
            })
        }
        addView(seek)
        timeLabel = text("0:00 / —")
        val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(label: String, onClick: (MaterialButton) -> Unit) =
            MaterialButton(context).apply { text = label; isAllCaps = false; setOnClickListener { onClick(this) } }
                .also { buttons.addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(context, 4) }) }
        button(getString(R.string.back_15)) { skip(-15_000) }.contentDescription = getString(R.string.back_15_desc)
        playButton = button(getString(R.string.play)) { togglePlay() }
        button(getString(R.string.forward_15)) { skip(15_000) }.contentDescription = getString(R.string.forward_15_desc)
        speedButton = button("1×") { cycleSpeed() }.apply { contentDescription = getString(R.string.speed_desc) }
        addView(buttons)
    }

    private fun timeText(posMs: Int, lenMs: Int) =
        "${Format.duration(posMs / 1000)} / ${if (lenMs > 0) Format.duration(lenMs / 1000) else "—"}"

    private fun tick() {
        val p = player ?: return
        val pos = try { p.currentPosition } catch (e: IllegalStateException) { return }
        if (!userDragging) {
            seek?.progress = pos
            timeLabel?.text = timeText(pos, seek?.max ?: 0)
        }
        // Highlight the transcript line being spoken.
        val current = lines.lastOrNull { it.second <= pos }?.first
        if (current !== highlighted) {
            highlighted?.setTypeface(null, Typeface.NORMAL)
            highlighted?.setBackgroundColor(0)
            current?.setTypeface(null, Typeface.BOLD)
            current?.setBackgroundColor(0x2290A4AE)
            highlighted = current
        }
    }

    private fun togglePlay() {
        val p = player
        if (p == null) { loadAndPlay(); return }
        if (p.isPlaying) { p.pause(); playButton?.text = getString(R.string.play) }
        else { p.start(); applySpeed(); playButton?.text = getString(R.string.pause) }
    }

    private fun skip(deltaMs: Int) {
        val p = player ?: return
        p.seekTo((p.currentPosition + deltaMs).coerceIn(0, p.duration))
        tick()
    }

    /** Tap a transcript line: jump there (loading the recording first if needed). */
    private fun jumpTo(ms: Int) {
        val p = player
        if (p == null) { pendingSeekMs = ms; loadAndPlay(); return }
        p.seekTo(ms.coerceIn(0, p.duration))
        if (!p.isPlaying) { p.start(); applySpeed(); playButton?.text = getString(R.string.pause) }
        tick()
    }

    private fun cycleSpeed() {
        speed = when (speed) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f }
        speedButton?.text = if (speed == 1.5f) "1.5×" else "${speed.toInt()}×"
        applySpeed()
    }

    private fun applySpeed() {
        val p = player ?: return
        try {
            val wasPlaying = p.isPlaying
            p.playbackParams = p.playbackParams.setSpeed(speed)
            if (!wasPlaying) p.pause() // setting the speed starts playback; keep it paused if it was
        } catch (e: Exception) { /* some audio formats don't support speed changes */ }
    }

    private fun loadAndPlay() {
        if (loading) return
        val id = conversationId ?: return
        val s = SecureStore.load(this) ?: return
        loading = true
        playButton?.isEnabled = false
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val api = Api(s)
                    val out = File.createTempFile("rec", ".audio", cacheDir)
                    out.outputStream().use { os ->
                        var part = 0; var parts = 1
                        while (part < parts) {
                            val chunk = api.recordingPart(id, part)
                            parts = chunk.parts
                            os.write(chunk.bytes)
                            part++
                            withContext(Dispatchers.Main) { status?.text = getString(R.string.loading_part, part, parts) }
                        }
                    }
                    out
                }
            }
            loading = false
            playButton?.isEnabled = true
            file.onSuccess { f ->
                audioFile = f
                status?.text = ""
                val p = MediaPlayer().apply {
                    setDataSource(f.absolutePath)
                    setOnCompletionListener { playButton?.text = getString(R.string.play) }
                    prepare()
                }
                player = p
                seek?.max = p.duration
                seek?.isEnabled = true
                pendingSeekMs?.let { p.seekTo(it.coerceIn(0, p.duration)) }
                pendingSeekMs = null
                p.start(); applySpeed()
                playButton?.text = getString(R.string.pause)
                tick()
            }.onFailure { status?.text = it.message ?: getString(R.string.recording_failed) }
        }
    }

    // ---------------------------------------------------------------- name for this number

    private fun nameDialog(number: String, current: String?) {
        val field = EditText(this).apply {
            hint = getString(R.string.name)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText(current ?: "")
        }
        val box = LinearLayout(this).apply { setPadding(Ui.dp(context, 20), Ui.dp(context, 8), Ui.dp(context, 20), 0); addView(field, LinearLayout.LayoutParams(-1, -2)) }
        MaterialAlertDialogBuilder(this)
            .setTitle(number)
            .setView(box)
            .setPositiveButton(R.string.save_short) { _, _ ->
                val name = field.text.toString().trim()
                if (name.isNotEmpty()) Contacts.put(this, number, name)
                loaded = false; player?.release(); player = null
                audioFile?.delete(); audioFile = null
                body.removeAllViews(); onUnlocked()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onStop() {
        super.onStop()
        if (player?.isPlaying == true) { player?.pause(); playButton?.text = getString(R.string.play) }
    }

    override fun onDestroy() {
        player?.release(); player = null
        audioFile?.delete() // never leave the recording on the phone
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SID = "sid"
        const val EXTRA_CONVERSATION = "conversation"
    }
}
