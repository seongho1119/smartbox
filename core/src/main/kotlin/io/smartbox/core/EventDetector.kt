package io.smartbox.core

/**
 * 차량 데이터로 위험 순간을 감지한다.
 *
 * 1. 속도 변화량으로 가속도를 계산해 급가속·급감속을 판단한다. 모든 차종에서 동작한다.
 * 2. 페달 값이 제공되면, 저속에서 가속 페달을 깊게 밟는 순간(주차장 오조작 등)도 잡는다.
 *
 * 같은 상황이 여러 번 잡히지 않도록 [cooldownMs] 동안은 새 이벤트를 만들지 않는다.
 */
class EventDetector(
    private val hardAccelMs2: Float = 3.0f,
    private val hardBrakeMs2: Float = -4.5f,
    private val pedalSurgePct: Float = 80f,
    private val pedalSurgeMaxSpeedKmh: Float = 15f,
    private val cooldownMs: Long = 10_000,
    private val windowMs: Long = 1_000,
    private val idFactory: (Long) -> String = { "ev$it" },
) {
    private val history = ArrayDeque<VehicleSample>()
    private var lastEventMs = Long.MIN_VALUE / 2

    /** 새 샘플을 넣고, 이벤트가 감지되면 반환한다. */
    fun onSample(s: VehicleSample): DrivingEvent? {
        history.addLast(s)
        while (history.size > 2 && s.timeMs - history.first().timeMs > windowMs) {
            history.removeFirst()
        }
        if (!s.ignitionOn) return null
        if (s.timeMs - lastEventMs < cooldownMs) return null

        val accel = currentAccel()
        val type = when {
            s.acceleratorPct != null && s.acceleratorPct >= pedalSurgePct &&
                s.speedKmh <= pedalSurgeMaxSpeedKmh && s.gear != Gear.P -> EventType.PEDAL_SURGE
            accel >= hardAccelMs2 -> EventType.HARD_ACCEL
            accel <= hardBrakeMs2 -> EventType.HARD_BRAKE
            else -> null
        } ?: return null

        lastEventMs = s.timeMs
        return DrivingEvent(idFactory(s.timeMs), type, s.timeMs, s.speedKmh, accel)
    }

    /** 사용자가 직접 저장 버튼을 누른 경우. 쿨다운과 무관하게 만든다. */
    fun manual(nowMs: Long, speedKmh: Float): DrivingEvent =
        DrivingEvent(idFactory(nowMs), EventType.MANUAL, nowMs, speedKmh, currentAccel())

    /** 창(window) 안의 처음과 마지막 샘플로 계산한 가속도(m/s²). */
    fun currentAccel(): Float {
        if (history.size < 2) return 0f
        val a = history.first()
        val b = history.last()
        val dt = (b.timeMs - a.timeMs) / 1000f
        if (dt <= 0.05f) return 0f
        return ((b.speedKmh - a.speedKmh) / 3.6f) / dt
    }
}
