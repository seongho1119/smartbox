package io.smartbox.core

/** 최근 차량 데이터를 일정 시간만큼 보관한다. 이벤트 앞뒤 그래프를 그릴 때 쓴다. */
class VehicleLog(private val keepMs: Long = 120_000) {
    private val samples = ArrayDeque<VehicleSample>()

    @Synchronized
    fun add(s: VehicleSample) {
        samples.addLast(s)
        while (samples.isNotEmpty() && s.timeMs - samples.first().timeMs > keepMs) samples.removeFirst()
    }

    @Synchronized
    fun between(fromMs: Long, toMs: Long): List<VehicleSample> =
        samples.filter { it.timeMs in fromMs..toMs }

    /** CSV로 저장(리포트·분석용). */
    companion object {
        const val CSV_HEADER = "time_ms,speed_kmh,gear,ignition,accelerator_pct,brake_pct"

        fun toCsv(list: List<VehicleSample>): String = buildString {
            appendLine(CSV_HEADER)
            list.forEach {
                appendLine(
                    "${it.timeMs},${it.speedKmh},${it.gear},${if (it.ignitionOn) 1 else 0}," +
                        "${it.acceleratorPct ?: ""},${it.brakePct ?: ""}"
                )
            }
        }
    }
}
