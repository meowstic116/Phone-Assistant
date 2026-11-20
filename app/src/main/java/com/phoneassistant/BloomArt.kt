package com.phoneassistant

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

/** One silhouette for both the toy and the collection icon, drawn around the bloom's center. */
internal object BloomArt {
    fun petal(canvas: Canvas, shape: PetalShape, cx: Float, cy: Float, r: Float, angle: Float,
              paint: Paint, path: Path) {
        if (shape == PetalShape.ROUND) {
            canvas.drawCircle(cx, cy, r, paint)
            return
        }
        val save = canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(angle)
        when (shape) {
            PetalShape.ROUND -> Unit
            PetalShape.OVAL -> canvas.drawOval(-r, -r * 0.59f, r * 1.38f, r * 0.59f, paint)
            PetalShape.POINT, PetalShape.HEART, PetalShape.FAN, PetalShape.DIAMOND -> {
                path.reset()
                path.moveTo(-r, 0f)
                when (shape) {
                    PetalShape.POINT -> {
                        path.quadTo(r * 0.25f, -r, r * 1.4f, 0f)
                        path.quadTo(r * 0.25f, r, -r, 0f)
                    }
                    PetalShape.HEART -> {
                        path.cubicTo(-r * 0.5f, -r, r * 1.6f, -r * 1.55f, r * 0.94f, 0f)
                        path.cubicTo(r * 1.6f, r * 1.55f, -r * 0.5f, r, -r, 0f)
                    }
                    PetalShape.FAN -> {
                        path.quadTo(r * 0.25f, -r * 0.36f, r * 0.84f, -r)
                        path.quadTo(r * 1.5f, 0f, r * 0.84f, r)
                        path.quadTo(r * 0.25f, r * 0.36f, -r, 0f)
                    }
                    PetalShape.DIAMOND -> {
                        path.lineTo(r * 0.12f, -r * 0.82f)
                        path.lineTo(r * 1.47f, 0f)
                        path.lineTo(r * 0.12f, r * 0.82f)
                    }
                    else -> Unit
                }
                path.close()
                canvas.drawPath(path, paint)
            }
        }
        canvas.restoreToCount(save)
    }
}
