package com.cellularchat.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

class DirectionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.hm_border_default)
        style = Paint.Style.STROKE
        strokeWidth = density(2f)
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.hm_action_primary_bg)
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.hm_fg_muted)
        textAlign = Paint.Align.CENTER
        textSize = density(13f)
    }
    private var azimuth: Double? = null

    /**
     * Sets the direction to draw. Only ever called with a fresh platform angle
     * sample (PROTOCOL_V2.md §12); null clears the arrow (never synthesized).
     */
    fun setAzimuth(azimuthDegrees: Double?) {
        azimuth = azimuthDegrees
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = minOf(width, height) * 0.39f
        canvas.drawCircle(centerX, centerY, radius, ringPaint)
        val angle = azimuth
        if (angle == null) {
            canvas.drawText("방향 데이터 없음", centerX, centerY + density(5f), labelPaint)
            return
        }
        canvas.save()
        canvas.rotate(angle.toFloat(), centerX, centerY)
        val path = Path().apply {
            moveTo(centerX, centerY - radius * 0.92f)
            lineTo(centerX - radius * 0.28f, centerY + radius * 0.55f)
            lineTo(centerX, centerY + radius * 0.30f)
            lineTo(centerX + radius * 0.28f, centerY + radius * 0.55f)
            close()
        }
        canvas.drawPath(path, arrowPaint)
        canvas.restore()
    }

    private fun density(value: Float): Float = value * resources.displayMetrics.density
}
