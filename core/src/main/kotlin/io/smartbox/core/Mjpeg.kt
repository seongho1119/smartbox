package io.smartbox.core

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** MJPEG(움직이는 JPEG) 스트림 쓰기·읽기. 차량 화면 미리보기에 쓴다. */
object Mjpeg {
    const val BOUNDARY = "smartboxframe"
    const val CONTENT_TYPE = "multipart/x-mixed-replace; boundary=$BOUNDARY"

    fun writeFrame(out: OutputStream, jpeg: ByteArray) {
        out.write("--$BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n".toByteArray())
        out.write(jpeg)
        out.write("\r\n".toByteArray())
        out.flush()
    }

    /** 스트림에서 JPEG 프레임을 하나씩 꺼낸다. 끝나면 null. */
    class Reader(input: InputStream) {
        private val input = BufferedInputStream(input, 64 * 1024)

        fun next(): ByteArray? {
            var length = -1
            while (true) {
                val line = readLine() ?: return null
                if (line.isEmpty()) {
                    if (length >= 0) break else continue
                }
                if (line.startsWith("Content-Length", ignoreCase = true)) {
                    length = line.substringAfter(':').trim().toIntOrNull() ?: -1
                }
            }
            val buf = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(buf, read, length - read)
                if (n < 0) return null
                read += n
            }
            return buf
        }

        private fun readLine(): String? {
            val b = ByteArrayOutputStream()
            while (true) {
                val c = input.read()
                if (c < 0) return null
                if (c == '\n'.code) return b.toString(Charsets.US_ASCII.name())
                if (c != '\r'.code) b.write(c)
                if (b.size() > 1024) b.reset()
            }
        }
    }
}
