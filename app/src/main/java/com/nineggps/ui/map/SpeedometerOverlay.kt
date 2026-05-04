// Copyright © R.N.K 9G5AR RadioZport
package com.nineggps.ui.map

import android.graphics.*
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.*

class SpeedometerOverlay(private val mapView: MapView) : Overlay() {

    var speedKmh: Float = 0f
    var maxSpeed: Float = 0f
    var isVisible: Boolean = false

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 30, 30, 30)
        style = Paint.Style.FILL
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 12f
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        textAlign = Paint.Align.CENTER
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (!isVisible || shadow) return

        val w = mapView.width.toFloat()
        val h = mapView.height.toFloat()

        val size = minOf(w, h) * 0.25f
        val cx = w - size / 2 - 20f
        val cy = h - size / 2 - 20f
        val radius = size / 2

        // Background circle
        canvas.drawCircle(cx, cy, radius, bgPaint)

        // Speed arc
        val startAngle = 135f
        val sweepMax = 270f
        val fraction = if (maxSpeed > 0) speedKmh / maxSpeed else speedKmh / 200f
        val sweep = (fraction.coerceIn(0f, 1f) * sweepMax)

        // Background arc
        arcPaint.color = Color.argb(100, 255, 255, 255)
        canvas.drawArc(
            cx - radius + 15f, cy - radius + 15f,
            cx + radius - 15f, cy + radius - 15f,
            startAngle, sweepMax, false, arcPaint
        )

        // Speed arc
        val speedColor = when {
            speedKmh < 60f  -> Color.GREEN
            speedKmh < 100f -> Color.YELLOW
            speedKmh < 130f -> Color.rgb(255, 165, 0) // orange
            else            -> Color.RED
        }
        arcPaint.color = speedColor
        if (sweep > 0f) {
            canvas.drawArc(
                cx - radius + 15f, cy - radius + 15f,
                cx + radius - 15f, cy + radius - 15f,
                startAngle, sweep, false, arcPaint
            )
        }

        // Speed number
        textPaint.textSize = radius * 0.55f
        canvas.drawText(speedKmh.toInt().toString(), cx, cy + radius * 0.15f, textPaint)

        // Unit label
        labelPaint.textSize = radius * 0.22f
        canvas.drawText("km/h", cx, cy + radius * 0.42f, labelPaint)

        // Max speed indicator
        if (maxSpeed > 0) {
            labelPaint.textSize = radius * 0.18f
            canvas.drawText("MAX ${maxSpeed.toInt()}", cx, cy - radius * 0.42f, labelPaint)
        }
    }
}
