package com.agentcontrol.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.max

/**
 * A small bar chart drawn by hand (no chart library to trust or update).
 * values: bar heights. overlay: optional second series drawn inside each bar
 * (e.g. answered calls inside total calls). labels: shown under some bars.
 */
class BarChartView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {
    private var values: List<Double> = emptyList()
    private var overlay: List<Double>? = null
    private var labels: List<String> = emptyList()
    private var valueFormat: (Double) -> String = { it.toLong().toString() }

    private val dp = resources.displayMetrics.density
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF90A4AE.toInt() }
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2E7D32.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11 * resources.displayMetrics.scaledDensity
        val tv = TypedValue()
        c.theme.resolveAttribute(android.R.attr.textColorSecondary, tv, true)
        color = try { c.getColor(tv.resourceId) } catch (e: Exception) { 0xFF607D8B.toInt() }
    }

    fun setData(values: List<Double>, labels: List<String>, overlay: List<Double>? = null, format: (Double) -> String = { it.toLong().toString() }) {
        this.values = values; this.labels = labels; this.overlay = overlay; this.valueFormat = format
        contentDescription = labels.zip(values).joinToString { (l, v) -> "$l: ${format(v)}" }
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (160 * dp).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        if (values.isEmpty()) {
            canvas.drawText("No data", 8 * dp, height / 2f, textPaint); return
        }
        val top = 16 * dp; val bottom = height - 20 * dp
        val maxV = max(values.maxOrNull() ?: 0.0, 1e-9)
        val slot = width.toFloat() / values.size
        val gap = if (slot > 6 * dp) 2 * dp else 0f
        canvas.drawText("max ${valueFormat(maxV)}", 0f, top - 4 * dp, textPaint)
        val every = max(1, (values.size + 5) / 6) // about 6 labels
        values.forEachIndexed { i, v ->
            val x0 = i * slot + gap / 2; val x1 = (i + 1) * slot - gap / 2
            val y = bottom - ((v / maxV) * (bottom - top)).toFloat()
            canvas.drawRect(x0, y, x1, bottom, barPaint)
            overlay?.getOrNull(i)?.let { o ->
                canvas.drawRect(x0, bottom - ((o / maxV) * (bottom - top)).toFloat(), x1, bottom, overlayPaint)
            }
            if (i % every == 0 && i < labels.size) canvas.drawText(labels[i], x0, height - 4 * dp, textPaint)
        }
    }
}
