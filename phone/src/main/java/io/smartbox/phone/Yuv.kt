package io.smartbox.phone

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/** 카메라 프레임(YUV_420_888)을 차량 화면 미리보기용 JPEG로 바꾼다. */
object Yuv {
    fun toJpeg(image: ImageProxy, quality: Int): ByteArray {
        val w = image.width
        val h = image.height
        val nv21 = toNv21(image)
        val out = ByteArrayOutputStream(w * h / 4)
        YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), quality, out)
        return out.toByteArray()
    }

    private fun toNv21(image: ImageProxy): ByteArray {
        val w = image.width
        val h = image.height
        val out = ByteArray(w * h * 3 / 2)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = yPlane.buffer.duplicate().apply { rewind() }
        var pos = 0
        for (row in 0 until h) {
            yBuf.position(row * yPlane.rowStride)
            yBuf.get(out, pos, w)
            pos += w
        }
        val uBuf = uPlane.buffer.duplicate().apply { rewind() }
        val vBuf = vPlane.buffer.duplicate().apply { rewind() }
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                out[pos++] = vBuf.get(row * vPlane.rowStride + col * vPlane.pixelStride)
                out[pos++] = uBuf.get(row * uPlane.rowStride + col * uPlane.pixelStride)
            }
        }
        return out
    }
}
