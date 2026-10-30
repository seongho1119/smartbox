package io.smartbox.vehicle

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import io.smartbox.core.Gear
import io.smartbox.core.VehicleSample
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * 차량 데이터 공급원. 실제 차량(Car API)과 시뮬레이터를 같은 방식으로 다룬다.
 * Pleos Vehicle SDK를 쓸 수 있게 되면 이 인터페이스를 구현한 클래스만 추가하면 된다.
 */
interface VehicleSource {
    val name: String
    fun start(onSample: (VehicleSample) -> Unit)
    fun stop()
}

private const val SAMPLE_MS = 100L

/** Android Automotive 표준 Car API로 속도·기어·시동 상태를 읽는다. */
class CarApiSource private constructor(private val car: Car) : VehicleSource {

    companion object {
        fun tryCreate(ctx: Context): CarApiSource? {
            if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return null
            return runCatching { Car.createCar(ctx)?.let(::CarApiSource) }.getOrNull()
        }

        /** 페달 값은 Android 버전·차량·권한에 따라 없을 수 있어 이름으로 찾아본다. */
        private fun optionalProperty(name: String): Int? =
            runCatching { VehiclePropertyIds::class.java.getField(name).getInt(null) }.getOrNull()
    }

    override val name = "차량 데이터 (Android Automotive Car API)"
    private var scheduler: ScheduledExecutorService? = null
    private val acceleratorId = optionalProperty("ACCELERATOR_PEDAL_COMPRESSION_PERCENTAGE")
    private val brakeId = optionalProperty("BRAKE_PEDAL_COMPRESSION_PERCENTAGE")

    override fun start(onSample: (VehicleSample) -> Unit) {
        val mgr = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
        var lastSpeed = 0f
        scheduler = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleWithFixedDelay({
                val speed = runCatching {
                    abs(mgr.getFloatProperty(VehiclePropertyIds.PERF_VEHICLE_SPEED, 0)) * 3.6f
                }.getOrDefault(lastSpeed)
                lastSpeed = speed
                val gearRaw = runCatching { mgr.getIntProperty(VehiclePropertyIds.GEAR_SELECTION, 0) }
                    .recoverCatching { mgr.getIntProperty(VehiclePropertyIds.CURRENT_GEAR, 0) }
                    .getOrNull()
                // VehicleIgnitionState: ON = 4, START = 5. 값을 못 읽으면 앱이 켜져 있는 동안 시동 켜짐으로 본다.
                val ignition = runCatching { mgr.getIntProperty(VehiclePropertyIds.IGNITION_STATE, 0) >= 4 }
                    .getOrDefault(true)
                val acc = acceleratorId?.let { id -> runCatching { mgr.getFloatProperty(id, 0) }.getOrNull() }
                val brake = brakeId?.let { id -> runCatching { mgr.getFloatProperty(id, 0) }.getOrNull() }
                onSample(VehicleSample(System.currentTimeMillis(), speed, mapGear(gearRaw), ignition, acc, brake))
            }, 0, SAMPLE_MS, TimeUnit.MILLISECONDS)
        }
    }

    override fun stop() {
        scheduler?.shutdownNow()
        runCatching { car.disconnect() }
    }

    // VehicleGear: NEUTRAL=0x1, REVERSE=0x2, PARK=0x4, DRIVE=0x8, 1단 이상=0x10~
    private fun mapGear(raw: Int?): Gear = when (raw) {
        null -> Gear.UNKNOWN
        0x1 -> Gear.N
        0x2 -> Gear.R
        0x4 -> Gear.P
        else -> if (raw >= 0x8) Gear.D else Gear.UNKNOWN
    }
}

/**
 * 차량이 없어도 시연할 수 있도록 주행을 흉내 낸다.
 * 버튼으로 출발·급가속·급제동·주차 같은 상황을 만든다.
 */
class SimulatedSource : VehicleSource {
    override val name = "시뮬레이터 (차량 없이 시연)"

    @Volatile var ignition = false
        private set
    @Volatile var gear = Gear.P
        private set
    @Volatile private var target = 0f
    @Volatile private var rateMs2 = 2f
    @Volatile private var parkWhenStopped = false
    @Volatile private var surgeUntil = 0L
    private var speed = 0f
    private var scheduler: ScheduledExecutorService? = null

    override fun start(onSample: (VehicleSample) -> Unit) {
        scheduler = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleWithFixedDelay({ onSample(tick()) }, 0, SAMPLE_MS, TimeUnit.MILLISECONDS)
        }
    }

    override fun stop() {
        scheduler?.shutdownNow()
    }

    private fun tick(): VehicleSample {
        val now = System.currentTimeMillis()
        if (!ignition || gear == Gear.P) target = 0f
        val surge = now < surgeUntil
        if (surge) target = 8f
        val step = rateMs2 * 3.6f * SAMPLE_MS / 1000f
        val diff = target - speed
        speed = (speed + diff.coerceIn(-step, step)).coerceAtLeast(0f)
        if (parkWhenStopped && speed < 0.5f) {
            gear = Gear.P
            parkWhenStopped = false
        }
        val accelerating = diff > 0.5f
        val braking = diff < -0.5f
        val acc = when {
            surge -> 95f
            accelerating -> if (rateMs2 >= 4f) 90f else 30f
            !braking && speed > 0f -> 15f
            else -> 0f
        }
        val brake = when {
            braking -> if (rateMs2 >= 5f) 90f else 30f
            speed < 0.5f && gear != Gear.P && ignition -> 20f
            else -> 0f
        }
        return VehicleSample(now, speed, if (ignition) gear else Gear.P, ignition, acc, brake)
    }

    fun toggleIgnition() {
        ignition = !ignition
        if (!ignition) { gear = Gear.P; target = 0f }
    }

    fun drive() = go(50f, 2f)
    fun hardAccel() = go(80f, 5f)
    fun hardBrake() { target = 0f; rateMs2 = 7f }
    fun park() { target = 0f; rateMs2 = 3f; parkWhenStopped = true }

    /** 주차장에서 가속 페달을 깊게 밟는 상황(저속). */
    fun pedalSurge() {
        if (!ignition) return
        gear = Gear.D
        rateMs2 = 2f
        surgeUntil = System.currentTimeMillis() + 1200
    }

    private fun go(speedKmh: Float, rate: Float) {
        if (!ignition) return
        gear = Gear.D
        parkWhenStopped = false
        target = speedKmh
        rateMs2 = rate
    }
}
