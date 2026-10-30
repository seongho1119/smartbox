package io.smartbox.core

/** 기어 상태. 차량마다 표현이 달라 앱 내부에서는 이 네 가지로 단순화한다. */
enum class Gear { P, R, N, D, UNKNOWN }

/**
 * 한 시점의 차량 상태.
 * 페달 값은 차량·권한에 따라 제공되지 않을 수 있어 null을 허용한다.
 */
data class VehicleSample(
    val timeMs: Long,
    val speedKmh: Float,
    val gear: Gear,
    val ignitionOn: Boolean,
    val acceleratorPct: Float? = null,
    val brakePct: Float? = null,
)

enum class EventType(val label: String) {
    HARD_ACCEL("급가속"),
    HARD_BRAKE("급감속"),
    PEDAL_SURGE("저속 페달 급조작"),
    MANUAL("수동 저장"),
}

data class DrivingEvent(
    val id: String,
    val type: EventType,
    val vehicleTimeMs: Long,
    val speedKmh: Float,
    val accelMs2: Float,
)

/** 스마트폰 카메라가 어디를 찍는지. */
enum class CameraRole(val label: String) {
    PEDAL("페달"),
    REAR("뒷좌석");

    companion object {
        fun parse(s: String?): CameraRole = entries.firstOrNull { it.name.equals(s, true) } ?: PEDAL
    }
}
