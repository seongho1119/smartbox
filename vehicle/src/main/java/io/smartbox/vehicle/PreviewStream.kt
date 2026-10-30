package io.smartbox.vehicle

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import io.smartbox.core.Http
import io.smartbox.core.Mjpeg
import java.net.HttpURLConnection

/** 스마트폰의 MJPEG 미리보기를 받아 화면에 그릴 Bitmap으로 넘긴다. 끊기면 다시 연결한다. */
class PreviewStream(private val url: String, private val onFrame: (Bitmap) -> Unit) {
    @Volatile private var running = true
    @Volatile private var conn: HttpURLConnection? = null
    private val main = Handler(Looper.getMainLooper())

    private val thread = Thread {
        while (running) {
            try {
                val (c, input) = Http.openStream(url)
                conn = c
                val reader = Mjpeg.Reader(input)
                while (running) {
                    val jpeg = reader.next() ?: break
                    val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: continue
                    main.post { if (running) onFrame(bmp) }
                }
            } catch (_: Exception) {
            } finally {
                conn?.disconnect()
            }
            if (running) Thread.sleep(1000)
        }
    }.apply { isDaemon = true; start() }

    fun stop() {
        running = false
        Thread { conn?.disconnect() }.start()
    }
}
