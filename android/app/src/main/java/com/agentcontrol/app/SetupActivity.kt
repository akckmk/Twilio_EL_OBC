package com.agentcontrol.app

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.text.method.LinkMovementMethod
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.text.HtmlCompat
import androidx.lifecycle.lifecycleScope
import com.agentcontrol.app.Ui.small
import com.agentcontrol.app.Ui.text
import com.agentcontrol.app.Ui.title
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One-time automatic setup. You paste your Twilio Account SID + Auth Token and your
 * ElevenLabs key; the app deploys the backend into your Twilio account, then forgets
 * the Twilio and ElevenLabs keys. Only the backend URL and a new access key are saved.
 */
class SetupActivity : SecureActivity() {
    private lateinit var sid: EditText
    private lateinit var token: EditText
    private lateinit var elKey: EditText
    private lateinit var go: MaterialButton
    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.setup_title)
        val c = this
        val column = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(c, 20), Ui.dp(c, 12), Ui.dp(c, 20), Ui.dp(c, 40))
            title(getString(R.string.setup_title))
            text(getString(R.string.setup_intro))
            addView(TextView(c).apply {
                text = HtmlCompat.fromHtml(getString(R.string.setup_where), HtmlCompat.FROM_HTML_MODE_COMPACT)
                movementMethod = LinkMovementMethod.getInstance()
                setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 8))
            })
        }
        sid = field(column, getString(R.string.setup_account_sid), secret = false)
        token = field(column, getString(R.string.setup_auth_token), secret = true)
        elKey = field(column, getString(R.string.setup_el_key), secret = true)
        go = MaterialButton(c).apply {
            text = getString(R.string.setup_go)
            minHeight = Ui.dp(c, 64)
            filterTouchesWhenObscured = true
            setOnClickListener { start() }
        }
        column.addView(go)
        log = column.text("")
        column.small(getString(R.string.setup_privacy))
        val scroll = ScrollView(c).apply { addView(column) }
        setContentView(scroll)
        scroll.padForSystemBars()
    }

    private fun field(parent: LinearLayout, hint: String, secret: Boolean): EditText {
        val layout = TextInputLayout(this).apply {
            this.hint = hint
            if (secret) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val edit = TextInputEditText(layout.context).apply {
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            imeOptions = 0x1000000 // flagNoPersonalizedLearning: the keyboard won't learn what you type
        }
        layout.addView(edit)
        parent.addView(layout)
        return edit
    }

    private fun start() {
        val accountSid = sid.text.toString().replace(Regex("\\s"), "")
        val authToken = token.text.toString().replace(Regex("\\s"), "")
        val elevenLabs = elKey.text.toString().replace(Regex("\\s"), "")
        when {
            !Regex("^AC[0-9a-fA-F]{32}$").matches(accountSid) -> { log.text = getString(R.string.bad_account_sid); return }
            authToken.length < 32 -> { log.text = getString(R.string.bad_auth_token); return }
            elevenLabs.length < 20 -> { log.text = getString(R.string.bad_el_key); return }
        }
        go.isEnabled = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val steps = StringBuilder()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val (files, deps) = Deployer.bundledFiles(
                        read = { assets.open(it).use { s -> s.readBytes() } },
                        listDir = { assets.list(it)?.toList().orEmpty() },
                    )
                    deployerFactory(accountSid, authToken, files, deps).deploy(elevenLabs) { step ->
                        runOnUiThread { steps.append("✓ ").append(step).append('\n'); log.text = steps }
                    }
                }
            }
            // Forget the provider keys: wipe the boxes and the clipboard. They were never saved.
            token.setText(""); elKey.setText(""); clearClipboard()
            result.onFailure {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                go.isEnabled = true
                log.text = steps.append("\n✗ ").append(it.message ?: getString(R.string.setup_failed))
            }.onSuccess { r ->
                val settings = SecureStore.Settings(r.baseUrl, r.accessKey)
                SecureStore.save(this@SetupActivity, settings)
                steps.append("✓ ").append(getString(R.string.setup_checking)).append('\n'); log.text = steps
                // The new backend can take a few seconds to answer after going live.
                var health: String? = null
                for (attempt in 1..8) {
                    health = withContext(Dispatchers.IO) { runCatching { Api(settings).health().let { h -> if (h.optBoolean("ok")) null else h.optString("message") } }.getOrElse { e -> e.message ?: "?" } }
                    if (health == null) break
                    delay(5000)
                }
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (health == null) {
                    log.text = steps.append("\n").append(getString(R.string.setup_done))
                    go.text = getString(R.string.setup_open_app)
                    go.isEnabled = true
                    go.setOnClickListener {
                        startActivity(Intent(this@SetupActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                        finish()
                    }
                } else {
                    log.text = steps.append("\n").append(getString(R.string.setup_done_with_problem, health))
                    go.text = getString(R.string.setup_open_app)
                    go.isEnabled = true
                    go.setOnClickListener { startActivity(Intent(this@SetupActivity, MainActivity::class.java)); finish() }
                }
            }
        }
    }

    private fun clearClipboard() {
        val cm = getSystemService(android.content.ClipboardManager::class.java) ?: return
        try { cm.clearPrimaryClip() } catch (e: Exception) { }
    }

    companion object {
        /** Only tests replace this (to point at a fake Twilio). */
        @androidx.annotation.VisibleForTesting
        internal var deployerFactory: (String, String, List<Deployer.FunctionFile>, org.json.JSONArray) -> Deployer =
            { sid, token, files, deps -> Deployer(sid, token, files, deps) }
    }
}
