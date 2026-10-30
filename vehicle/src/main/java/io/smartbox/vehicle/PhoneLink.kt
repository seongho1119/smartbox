package io.smartbox.vehicle

import io.smartbox.core.CameraRole
import io.smartbox.core.Http
import io.smartbox.core.KeyValue
import io.smartbox.core.Protocol
import io.smartbox.core.buildQuery
import java.io.File

/** 연결된 스마트폰 한 대(페달 또는 뒷좌석)와의 통신. */
class PhoneLink(val role: CameraRole, @Volatile var host: String, @Volatile var port: Int, private val token: String) {

    @Volatile var lastSeenMs = System.currentTimeMillis()
    @Volatile var status: Map<String, String> = emptyMap()
        private set

    val alive get() = System.currentTimeMillis() - lastSeenMs < Protocol.PHONE_TIMEOUT_MS
    val recording get() = alive && status["recording"] == "true"
    val rotation get() = status["rotation"]?.toIntOrNull() ?: 0
    val motion get() = status["motion"]?.toFloatOrNull() ?: 0f
    val lastMotionVt get() = status["lastMotionVt"]?.toLongOrNull() ?: 0L
    val batteryTemp get() = status["batteryTemp"]?.toFloatOrNull()

    private val base get() = "http://$host:$port"
    private fun q(extra: Map<String, Any?> = emptyMap()) = buildQuery(mapOf("token" to token) + extra)

    fun pollStatus() {
        status = runCatching { KeyValue.decode(Http.get("$base/status?${q()}", 1500)) }.getOrDefault(status)
    }

    fun sendEvent(id: String, vehicleTimeMs: Long) {
        Http.post("$base/event?${q(mapOf("id" to id, "vt" to vehicleTimeMs))}")
    }

    fun sendIgnition(on: Boolean) {
        Http.post("$base/ignition?${q(mapOf("on" to if (on) 1 else 0))}")
    }

    fun previewUrl() = "$base/preview.mjpeg?${q()}"

    /** status=ready|pending|missing, count, seg{n}=시작,끝(차량 시각) */
    fun eventInfo(id: String): Map<String, String> =
        runCatching { KeyValue.decode(Http.get("$base/events/$id/list?${q()}", 3000)) }
            .getOrDefault(mapOf("status" to "missing"))

    fun downloadClip(id: String, index: Int, dest: File) {
        Http.download("$base/events/$id/$index.mp4?${q()}", dest, 30_000)
    }
}
