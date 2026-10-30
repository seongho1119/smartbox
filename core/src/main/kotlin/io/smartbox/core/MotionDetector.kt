package io.smartbox.core

import kotlin.math.abs

/**
 * 카메라 밝기(Y) 값만으로 움직임을 감지한다. 뒷좌석 하차 알림에 쓴다.
 * 영상을 저장하거나 사람을 식별하지 않고, "이전 프레임과 얼마나 달라졌는지"만 본다.
 */
class MotionDetector(
    private val gridW: Int = 32,
    private val gridH: Int = 24,
    private val pixelDelta: Int = 25,
    private val motionRatio: Float = 0.02f,
) {
    private var prev: IntArray? = null

    /** @return 이번 프레임에서 바뀐 칸의 비율(0~1) */
    fun score(luma: ByteArray, width: Int, height: Int, rowStride: Int = width): Float {
        val cur = downsample(luma, width, height, rowStride)
        val p = prev
        prev = cur
        if (p == null) return 0f
        var changed = 0
        for (i in cur.indices) if (abs(cur[i] - p[i]) > pixelDelta) changed++
        return changed.toFloat() / cur.size
    }

    fun isMotion(score: Float) = score >= motionRatio

    private fun downsample(y: ByteArray, w: Int, h: Int, stride: Int): IntArray {
        val out = IntArray(gridW * gridH)
        val cw = w / gridW
        val ch = h / gridH
        if (cw == 0 || ch == 0) return out
        for (gy in 0 until gridH) for (gx in 0 until gridW) {
            var sum = 0
            var n = 0
            var yy = gy * ch
            while (yy < (gy + 1) * ch) {
                var xx = gx * cw
                while (xx < (gx + 1) * cw) {
                    sum += y[yy * stride + xx].toInt() and 0xFF
                    n++
                    xx += 4
                }
                yy += 4
            }
            out[gy * gridW + gx] = if (n == 0) 0 else sum / n
        }
        return out
    }
}
