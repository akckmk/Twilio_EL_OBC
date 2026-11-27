package com.agentcontrol.app

import android.media.MediaPlayer
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.lifecycle.lifecycleScope
import com.agentcontrol.app.Ui.row
import com.agentcontrol.app.Ui.small
import com.agentcontrol.app.Ui.text
import com.agentcontrol.app.Ui.title
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One call: summary, full transcript, recording, and ElevenLabs' analysis results.
 * The recording is fetched through the backend into a private temporary file,
 * played, and deleted when you leave this screen.
 */
class CallDetailActivity : SecureActivity() {
    private lateinit var body: LinearLayout
    private var player: MediaPlayer? = null
    private var audioFile: File? = null
    private var loaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.call_details)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 16), Ui.dp(context, 8), Ui.dp(context, 16), Ui.dp(context, 32))
        }
        val scroll = ScrollView(this).apply { addView(body); setBackgroundColor(getColor(android.R.color.transparent)) }
        setContentView(scroll)
        scroll.padForSystemBars()
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
        })
        if (d.conversationId == null) {
            body.addView(Ui.card(this) { small(getString(R.string.no_conversation)) })
            return
        }
        if (d.hasAudio) body.addView(Ui.card(this) {
            title(getString(R.string.recording))
            val status = small("")
            val play = MaterialButton(context).apply { text = getString(R.string.play) }
            addView(play)
            play.setOnClickListener { togglePlay(d.conversationId, play) { status.text = it } }
        })
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
        body.addView(Ui.card(this) {
            title(getString(R.string.transcript))
            if (d.transcript.isEmpty()) small(getString(R.string.no_transcript))
            for (l in d.transcript) {
                val speaker = if (l.role == "agent") c.agentName else (who ?: getString(R.string.them))
                text("$speaker  ${Format.duration(l.t.toInt())}", bold = true)
                text(l.text)
            }
        })
    }

    private fun togglePlay(conversationId: String, button: MaterialButton, status: (String) -> Unit) {
        player?.let { p ->
            if (p.isPlaying) { p.pause(); button.text = getString(R.string.play) } else { p.start(); button.text = getString(R.string.pause) }
            return
        }
        val s = SecureStore.load(this) ?: return
        button.isEnabled = false
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val api = Api(s)
                    val out = File.createTempFile("rec", ".audio", cacheDir)
                    out.outputStream().use { os ->
                        var part = 0; var parts = 1
                        while (part < parts) {
                            val p = api.recordingPart(conversationId, part)
                            parts = p.parts
                            os.write(p.bytes)
                            part++
                            withContext(Dispatchers.Main) { status(getString(R.string.loading_part, part, parts)) }
                        }
                    }
                    out
                }
            }
            button.isEnabled = true
            file.onSuccess { f ->
                audioFile = f
                status("")
                player = MediaPlayer().apply {
                    setDataSource(f.absolutePath)
                    setOnCompletionListener { button.text = getString(R.string.play) }
                    prepare(); start()
                }
                button.text = getString(R.string.pause)
            }.onFailure { status(it.message ?: getString(R.string.recording_failed)) }
        }
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
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
