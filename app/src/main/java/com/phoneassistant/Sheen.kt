package com.phoneassistant

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.sin

/**
 * A slow band of light crossing something flat: the wet paint on a canvas, the plastic of the
 * bubble wrap. It owns the timing, the fade and the paint; the caller owns the geometry and
 * clips its own canvas, so the same band can be narrow on one toy and broad on another.
 *
 * The caller also decides when to stop repainting: [visible] is false for the stretch of every
 * cycle when the band is off screen.
 */
class Sheen(bandPx: Float, tint: Int, private val periodMs: Long) {

    private val band = bandPx

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f, 0f, band, 0f,
            intArrayOf(tint and 0x00FFFFFF, tint, tint and 0x00FFFFFF),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    /** Whether the band is on screen at [now], for something created at [bornAt]. */
    fun visible(now: Long, bornAt: Long): Boolean = phase(now, bornAt) <= SWEEP

    /**
     * Draws the band across [left]..[right] and [top]..[bottom], angled and faded for this
     * instant. The caller has already clipped the canvas.
     */
    fun draw(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        now: Long,
        bornAt: Long
    ) {
        val through = phase(now, bornAt) / SWEEP
        if (through > 1f) return

        paint.alpha = (sin(through * PI) * 255.0).toInt().coerceIn(0, 255)
        val x = left - band + through * (right - left + band * 2f)
        canvas.save()
        canvas.rotate(TILT, (left + right) / 2f, (top + bottom) / 2f)
        canvas.translate(x, top)
        canvas.drawRect(0f, -band, band, bottom - top + band * 2f, paint)
        canvas.restore()
    }

    private fun phase(now: Long, bornAt: Long): Float =
        ((now - bornAt) % periodMs).toFloat() / periodMs

    private companion object {
        /** The band is on screen for this fraction of each cycle. */
        const val SWEEP = 0.42f

        /** Its angle across whatever it is crossing, in degrees. */
        const val TILT = -15f
    }
}
