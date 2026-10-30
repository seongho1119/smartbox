package io.smartbox.vehicle

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import io.smartbox.core.VehicleSample

/**
 * 이벤트 앞뒤 구간의 속도·가속 페달·브레이크 그래프.
 * 영상이 재생되는 위치를 [cursorMs]로 표시해, 영상과 차량 데이터를 같은 시간축에서 비교할 수 있다.
 */
class SpeedGraphView(context: Context) : View(context) {

    var samples: List<VehicleSample> = emptyList()
        set(v) { field = v; invalidate() }
    var fromMs = 0L
    var toMs = 1L
    var eventMs = 0L
    var cursorMs: Long? = null
        set(v) { field = v; invalidate() }

    private val density = resources.displayMetrics.density
    private fun stroke(color: Int, w: Float = 2.5f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = w * density
    }

    private val speedPaint = stroke(Color.rgb(77, 163, 255), 3f)
    private val accPaint = stroke(Color.rgb(70, 200, 120))
    private val brakePaint = stroke(Color.rgb(240, 90, 90))
    private val gridPaint = stroke(Color.argb(60, 255, 255, 255), 1f)
    private val eventPaint = stroke(Color.rgb(255, 200, 60), 2f).apply {
        pathEffect = DashPathEffect(floatArrayOf(8 * density, 6 * density), 0f)
    }
    private val cursorPaint = stroke(Color.WHITE, 2f)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        textSize = 12 * density
    }

    override fun onDraw(canvas: Canvas) {
        val left = 40 * density
        val right = width - 8 * density
        val top = 20 * density
        val bottom = height - 22 * density
        val maxSpeed = ((samples.maxOfOrNull { it.speedKmh } ?: 0f).coerceAtLeast(40f) / 20f).toInt().plus(1) * 20f

        for (i in 0..4) {
            val y = bottom - (bottom - top) * i / 4f
            canvas.drawLine(left, y, right, y, gridPaint)
            canvas.drawText("${(maxSpeed * i / 4).toInt()}", 4 * density, y + 4 * density, textPaint)
        }
        canvas.drawText("km/h", 4 * density, top - 6 * density, textPaint)

        fun x(t: Long) = left + (right - left) * ((t - fromMs).toFloat() / (toMs - fromMs).coerceAtLeast(1))
        fun line(paint: Paint, value: (VehicleSample) -> Float?, scale: Float) {
            val path = Path()
            var started = false
            samples.forEach { s ->
                val v = value(s) ?: return@forEach
                val px = x(s.timeMs)
                val py = bottom - (bottom - top) * (v / scale).coerceIn(0f, 1f)
                if (started) path.lineTo(px, py) else { path.moveTo(px, py); started = true }
            }
            canvas.drawPath(path, paint)
        }
        line(accPaint, { it.acceleratorPct }, 100f)
        line(brakePaint, { it.brakePct }, 100f)
        line(speedPaint, { it.speedKmh }, maxSpeed)

        val ex = x(eventMs)
        canvas.drawLine(ex, top, ex, bottom, eventPaint)
        cursorMs?.let { canvas.drawLine(x(it), top, x(it), bottom, cursorPaint) }

        val legendY = height - 6 * density
        canvas.drawText("속도", left, legendY, textPaint.withColor(speedPaint.color))
        canvas.drawText("가속 페달(%)", left + 50 * density, legendY, textPaint.withColor(accPaint.color))
        canvas.drawText("브레이크(%)", left + 140 * density, legendY, textPaint.withColor(brakePaint.color))
        canvas.drawText("-20초", left, top - 6 * density, textPaint)
        canvas.drawText("+20초", right - 36 * density, top - 6 * density, textPaint)
    }

    private fun Paint.withColor(c: Int) = Paint(this).apply { color = c }
}
