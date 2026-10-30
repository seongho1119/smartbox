package io.smartbox.core

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 차량 앱과 스마트폰 앱이 주고받는 약속.
 *
 * 차량 앱(포트 8787)
 *   GET  /time                    차량 시각(시계 맞추기용)
 *   POST /register?...            스마트폰 등록·생존 신호(5초마다)
 *
 * 스마트폰 앱(포트 8788)
 *   GET  /status                  녹화 여부, 움직임 점수 등 (key=value 줄 형식)
 *   GET  /preview.mjpeg           저화질 실시간 미리보기
 *   POST /ignition?on=1|0         시동 상태 전달
 *   POST /event?id=..&vt=..       이벤트 발생(차량 시각 vt 기준 앞뒤 구간 보관)
 *   GET  /events/{id}/list        보관된 영상 조각 목록
 *   GET  /events/{id}/{n}.mp4     영상 조각
 */
object Protocol {
    const val VEHICLE_PORT = 8787
    const val PHONE_PORT = 8788
    const val EVENT_BEFORE_MS = 20_000L
    const val EVENT_AFTER_MS = 20_000L
    const val SEGMENT_MS = 20_000L
    const val HEARTBEAT_MS = 5_000L
    const val PHONE_TIMEOUT_MS = 12_000L
}

/** 차량 화면 QR 코드에 담기는 연결 정보. */
data class PairingInfo(val host: String, val port: Int, val token: String) {
    fun toUri(): String =
        "smartbox://pair?host=${enc(host)}&port=$port&token=${enc(token)}"

    companion object {
        fun parse(uri: String): PairingInfo? {
            if (!uri.startsWith("smartbox://pair?")) return null
            val q = parseQuery(uri.substringAfter('?'))
            val host = q["host"] ?: return null
            val port = q["port"]?.toIntOrNull() ?: return null
            val token = q["token"] ?: return null
            return PairingInfo(host, port, token)
        }
    }
}

internal fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

fun parseQuery(q: String): Map<String, String> =
    q.split('&').filter { it.isNotEmpty() }.associate {
        val k = it.substringBefore('=')
        val v = if ('=' in it) it.substringAfter('=') else ""
        URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
    }

fun buildQuery(params: Map<String, Any?>): String =
    params.filterValues { it != null }.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v.toString())}" }

/** 간단한 key=value 줄 형식. JSON 라이브러리 없이 양쪽에서 쓰기 위함. */
object KeyValue {
    fun encode(map: Map<String, Any?>): String =
        map.filterValues { it != null }.entries.joinToString("\n") { "${it.key}=${it.value}" }

    fun decode(text: String): Map<String, String> =
        text.lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
}
