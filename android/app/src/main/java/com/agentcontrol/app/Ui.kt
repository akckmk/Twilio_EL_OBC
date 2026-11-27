package com.agentcontrol.app

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/** Tiny helpers to build simple screens in code. */
object Ui {
    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    fun card(c: Context, build: LinearLayout.() -> Unit): MaterialCardView {
        val inner = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14))
            build()
        }
        return MaterialCardView(c).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(c, 10) }
            addView(inner)
        }
    }

    fun LinearLayout.text(s: CharSequence?, style: Int = com.google.android.material.R.attr.textAppearanceBodyMedium, bold: Boolean = false, alpha: Float = 1f): TextView {
        val tv = TextView(context).apply {
            text = s ?: ""
            val a = context.obtainStyledAttributes(intArrayOf(style))
            TextViewCompat.setTextAppearance(this, a.getResourceId(0, 0)); a.recycle()
            if (bold) setTypeface(typeface, Typeface.BOLD)
            this.alpha = alpha
            setTextIsSelectable(false)
        }
        addView(tv)
        return tv
    }

    fun LinearLayout.title(s: CharSequence) = text(s, com.google.android.material.R.attr.textAppearanceTitleMedium, bold = true)
    fun LinearLayout.big(s: CharSequence) = text(s, com.google.android.material.R.attr.textAppearanceHeadlineSmall, bold = true)
    fun LinearLayout.small(s: CharSequence?) = text(s, com.google.android.material.R.attr.textAppearanceBodySmall, alpha = 0.75f)

    fun LinearLayout.redButton(label: String, onClick: (MaterialButton) -> Unit): MaterialButton {
        val b = MaterialButton(context).apply {
            text = label
            setBackgroundColor(context.getColor(R.color.end_red))
            setTextColor(context.getColor(R.color.on_red))
            textSize = 18f
            minHeight = dp(context, 60)
            filterTouchesWhenObscured = true
            setOnClickListener { onClick(this) }
        }
        addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(context, 8) })
        return b
    }

    fun LinearLayout.chart(): BarChartView = BarChartView(context).also { addView(it) }

    /** Two-column "label   value" row. */
    fun LinearLayout.row(label: String, value: CharSequence): View {
        val r = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        r.addView(TextView(context).apply { text = label; alpha = 0.75f }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(TextView(context).apply { text = value; setTypeface(typeface, Typeface.BOLD) })
        addView(r)
        return r
    }
}
