package io.smartbox.core

/**
 * 차량과 스마트폰의 시계 차이를 맞춘다 (간단한 NTP 방식).
 *
 * 스마트폰이 t0에 요청을 보내고, 차량이 자기 시각 vehicleTime을 답하고, 스마트폰이 t1에 받으면
 * 왕복 시간의 절반만큼 지연됐다고 보고 offset = vehicleTime - (t0 + t1) / 2 로 계산한다.
 * 여러 번 측정해 왕복 시간이 가장 짧았던 값을 쓴다(네트워크 지연의 영향이 가장 적음).
 */
class ClockSync {
    private var bestRtt = Long.MAX_VALUE
    @Volatile var offsetMs: Long = 0
        private set
    @Volatile var synced: Boolean = false
        private set

    fun addMeasurement(t0: Long, vehicleTime: Long, t1: Long) {
        val rtt = t1 - t0
        if (rtt < 0) return
        if (rtt <= bestRtt) {
            bestRtt = rtt
            offsetMs = vehicleTime - (t0 + t1) / 2
            synced = true
        }
    }

    /** 오래된 측정값을 버리고 다시 맞출 때. */
    fun reset() {
        bestRtt = Long.MAX_VALUE
        synced = false
    }

    fun phoneToVehicle(phoneMs: Long) = phoneMs + offsetMs
    fun vehicleToPhone(vehicleMs: Long) = vehicleMs - offsetMs
}
