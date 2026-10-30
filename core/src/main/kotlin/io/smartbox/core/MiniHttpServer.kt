package io.smartbox.core

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors

/**
 * 같은 Wi-Fi(로컬 네트워크) 안에서 차량 앱과 스마트폰 앱이 통신하기 위한 아주 작은 HTTP 서버.
 * 외부 라이브러리 없이 동작하도록 필요한 기능만 구현했다.
 */
class MiniHttpServer(private val port: Int, private val handler: (Request, Response) -> Unit) {

    class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray,
        val remoteHost: String,
    )

    class Response(private val out: OutputStream) {
        private var sent = false
        val isSent get() = sent

        fun send(status: Int, body: ByteArray, contentType: String = "text/plain; charset=utf-8") {
            writeHead(status, contentType, body.size.toLong())
            out.write(body)
            out.flush()
        }

        fun text(text: String, status: Int = 200) = send(status, text.toByteArray())

        fun stream(status: Int, contentType: String, length: Long, src: InputStream) {
            writeHead(status, contentType, length)
            src.copyTo(out, 64 * 1024)
            out.flush()
        }

        /** 길이를 모르는 스트리밍(MJPEG 등). 연결이 끊길 때까지 [body]가 직접 쓴다. */
        fun open(contentType: String, body: (OutputStream) -> Unit) {
            check(!sent)
            sent = true
            out.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nCache-Control: no-cache\r\n" +
                    "Connection: close\r\n\r\n").toByteArray()
            )
            out.flush()
            body(out)
        }

        private fun writeHead(status: Int, contentType: String, length: Long) {
            check(!sent)
            sent = true
            out.write(
                ("HTTP/1.1 $status ${reason(status)}\r\nContent-Type: $contentType\r\n" +
                    "Content-Length: $length\r\nConnection: close\r\n\r\n").toByteArray()
            )
        }

        private fun reason(s: Int) = when (s) {
            200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; else -> "Error"
        }
    }

    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var running = false

    val isRunning get() = running

    fun start() {
        if (running) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        server = ss
        running = true
        pool.execute {
            while (running) {
                val sock = try { ss.accept() } catch (e: SocketException) { break }
                pool.execute { serve(sock) }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
    }

    private fun serve(sock: Socket) {
        sock.use { s ->
            try {
                s.soTimeout = 15_000
                val input = BufferedInputStream(s.getInputStream())
                val req = parse(input, s.inetAddress.hostAddress ?: "") ?: return
                s.soTimeout = 0
                val res = Response(s.getOutputStream())
                try {
                    handler(req, res)
                } catch (e: Exception) {
                    if (!res.isSent) res.text("error: ${e.message}", 500)
                }
                if (!res.isSent) res.text("not found", 404)
            } catch (_: Exception) {
                // 연결이 끊긴 경우 등은 무시한다.
            }
        }
    }

    private fun parse(input: InputStream, remote: String): Request? {
        val first = readLine(input) ?: return null
        val parts = first.split(' ')
        if (parts.size < 2) return null
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(body, read, len - read)
            if (n < 0) break
            read += n
        }
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = if ('?' in target) parseQuery(target.substringAfter('?')) else emptyMap()
        return Request(parts[0].uppercase(), path, query, headers, body, remote)
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString(Charsets.UTF_8.name())
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > 8192) return null
        }
        return buf.toString(Charsets.UTF_8.name())
    }
}
