package io.smartbox.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** 상대 앱에 요청을 보내는 간단한 HTTP 클라이언트. */
object Http {
    fun get(url: String, timeoutMs: Int = 3000): String = request("GET", url, timeoutMs).toString(Charsets.UTF_8)

    fun post(url: String, timeoutMs: Int = 3000): String = request("POST", url, timeoutMs).toString(Charsets.UTF_8)

    fun download(url: String, dest: File, timeoutMs: Int = 10_000) {
        val c = open("GET", url, timeoutMs)
        try {
            check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
            val tmp = File(dest.path + ".part")
            c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(dest)) error("rename failed")
        } finally {
            c.disconnect()
        }
    }

    /** 스트리밍 응답(MJPEG)을 열어 입력 스트림을 넘긴다. */
    fun openStream(url: String, timeoutMs: Int = 5000): Pair<HttpURLConnection, InputStream> {
        val c = open("GET", url, timeoutMs)
        c.readTimeout = 0
        check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
        return c to c.inputStream
    }

    private fun request(method: String, url: String, timeoutMs: Int): ByteArray {
        val c = open(method, url, timeoutMs)
        try {
            if (method == "POST") {
                c.doOutput = true
                c.setFixedLengthStreamingMode(0)
                c.outputStream.close()
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val bytes = stream?.use { readAll(it) } ?: ByteArray(0)
            check(code in 200..299) { "HTTP $code" }
            return bytes
        } finally {
            c.disconnect()
        }
    }

    private fun open(method: String, url: String, timeoutMs: Int): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.useCaches = false
        return c
    }

    private fun readAll(s: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        s.copyTo(out)
        return out.toByteArray()
    }
}
