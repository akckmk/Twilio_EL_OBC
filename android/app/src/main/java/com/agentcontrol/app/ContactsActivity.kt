package com.agentcontrol.app

import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import com.agentcontrol.app.Ui.small
import com.agentcontrol.app.Ui.title
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Names for phone numbers, added by hand one at a time, so calls show "Dustin"
 * instead of the number. Never reads the phone's contacts. Stored encrypted.
 */
class ContactsActivity : SecureActivity() {
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.contacts)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 16), Ui.dp(context, 8), Ui.dp(context, 16), Ui.dp(context, 32))
            addView(MaterialButton(context).apply { text = getString(R.string.add_contact); setOnClickListener { edit(null, null) } })
            small(getString(R.string.contacts_help))
            addView(list)
        }
        val scroll = ScrollView(this).apply { addView(column) }
        setContentView(scroll)
        scroll.padForSystemBars()
    }

    override fun onUnlocked() = render()

    private fun render() {
        list.removeAllViews()
        val all = Contacts.all(this)
        if (all.isEmpty()) list.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; small(getString(R.string.no_contacts)) })
        for ((number, name) in all.entries.sortedBy { it.value.lowercase() }) {
            list.addView(Ui.card(this) { title(name); small(number) }.apply {
                isClickable = true
                setOnClickListener { edit(number, name) }
            })
        }
    }

    private fun edit(number: String?, name: String?) {
        val numberField = EditText(this).apply {
            hint = getString(R.string.phone_number); inputType = InputType.TYPE_CLASS_PHONE; setText(number ?: "")
        }
        val nameField = EditText(this).apply {
            hint = getString(R.string.name); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS; setText(name ?: "")
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 20), Ui.dp(context, 8), Ui.dp(context, 20), 0)
            addView(numberField); addView(nameField)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (number == null) R.string.add_contact else R.string.edit_contact)
            .setView(box)
            .setPositiveButton(R.string.save_short) { _, _ ->
                val n = numberField.text.toString().trim(); val nm = nameField.text.toString().trim()
                if (Contacts.key(n).length >= 7 && nm.isNotEmpty()) {
                    if (number != null && Contacts.key(number) != Contacts.key(n)) Contacts.remove(this, number)
                    Contacts.put(this, n, nm)
                }
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (number != null) dialog.setNeutralButton(R.string.delete) { _, _ -> Contacts.remove(this, number); render() }
        dialog.show()
    }
}
