package io.smartbox.vehicle

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.smartbox.core.CameraRole
import io.smartbox.core.DrivingEvent
import io.smartbox.core.EventDetector
import io.smartbox.core.Gear
import io.smartbox.core.KeyValue
import io.smartbox.core.MiniHttpServer
import io.smartbox.core.NetUtil
import io.smartbox.core.PairingInfo
import io.smartbox.core.Protocol
import io.smartbox.core.VehicleLog
import io.smartbox.core.VehicleSample
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** 이벤트 하나와, 그 앞뒤 구간의 차량 데이터. */
class EventRecord(val event: DrivingEvent) {
    @Volatile var samples: List<VehicleSample>? = null
}

/**
 * 차량 앱의 두뇌. 화면과 상관없이 앱이 살아 있는 동안 동작한다.
 * 1. 차량 데이터를 받아 기록하고 이벤트를 감지한다.
 * 2. 스마트폰의 등록을 받고, 이벤트·시동 신호를 보낸다.
 * 3. P단 전환 또는 시동 끔 때 뒷좌석 움직임을 확인해 하차 알림을 띄운다.
 */
class Controller(private val ctx: Context) {

    interface Listener {
        fun onChanged()
        fun onExitAlert(message: String)
    }

    val listeners = CopyOnWriteArraySet<Listener>()
    val token: String
    val log = VehicleLog()
    val events = CopyOnWriteArrayList<EventRecord>()
    val phones = ConcurrentHashMap<CameraRole, PhoneLink>()
    @Volatile var latest: VehicleSample? = null
        private set
    @Volatile var source: VehicleSource
        private set
    @Volatile var serverError: String? = null
        private set
    val carApiAvailable: Boolean

    private val detector = EventDetector()
    private val exec = Executors.newScheduledThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val server = MiniHttpServer(Protocol.VEHICLE_PORT, ::handle)
    private var lastNotify = 0L

    init {
        val prefs = ctx.getSharedPreferences("smartbox", Context.MODE_PRIVATE)
        token = prefs.getString("token", null) ?: Random.nextInt(100000, 999999).toString().also {
            prefs.edit().putString("token", it).apply()
        }
        val car = CarApiSource.tryCreate(ctx)
        carApiAvailable = car != null
        source = car ?: SimulatedSource()
    }

    fun start() {
        try {
            server.start()
        } catch (e: Exception) {
            serverError = "통신 서버를 열지 못했어요: ${e.message}"
        }
        source.start(::onSample)
        exec.scheduleWithFixedDelay({ runCatching { pollPhones() } }, 0, 1, TimeUnit.SECONDS)
        exec.scheduleWithFixedDelay({ runCatching { finishEvents() } }, 1, 1, TimeUnit.SECONDS)
    }

    /** 실제 차량 데이터 ↔ 시뮬레이터 전환. */
    fun useSimulator(on: Boolean) {
        val next = if (on) SimulatedSource() else CarApiSource.tryCreate(ctx) ?: return
        source.stop()
        source = next
        next.start(::onSample)
        notifyChanged(force = true)
    }

    fun pairingInfo(): PairingInfo? = NetUtil.localIpv4()?.let { PairingInfo(it, Protocol.VEHICLE_PORT, token) }

    fun isDriving(): Boolean = latest?.let { it.gear == Gear.D || it.gear == Gear.R || it.speedKmh > 3f } ?: false

    fun saveNow() {
        val s = latest ?: return
        addEvent(detector.manual(s.timeMs, s.speedKmh))
    }

    fun event(id: String) = events.firstOrNull { it.event.id == id }

    // ---------------------------------------------------------------- 내부 동작

    private fun onSample(s: VehicleSample) {
        val prev = latest
        latest = s
        log.add(s)
        detector.onSample(s)?.let(::addEvent)
        if (prev != null) {
            if (prev.ignitionOn != s.ignitionOn) {
                phones.values.forEach { link -> exec.execute { runCatching { link.sendIgnition(s.ignitionOn) } } }
            }
            val parked = prev.gear != Gear.P && s.gear == Gear.P
            val turnedOff = prev.ignitionOn && !s.ignitionOn
            if (parked || turnedOff) checkRearSeat(s.timeMs)
        }
        notifyChanged()
    }

    private fun addEvent(ev: DrivingEvent) {
        events.add(0, EventRecord(ev))
        phones.values.filter { it.alive }.forEach { link ->
            exec.execute { runCatching { link.sendEvent(ev.id, ev.vehicleTimeMs) } }
        }
        notifyChanged(force = true)
    }

    /** 이벤트 뒤 20초가 지나면 그래프용 차량 데이터를 잘라 보관한다. */
    private fun finishEvents() {
        val now = System.currentTimeMillis()
        events.filter { it.samples == null && now >= it.event.vehicleTimeMs + Protocol.EVENT_AFTER_MS }.forEach {
            val t = it.event.vehicleTimeMs
            it.samples = log.between(t - Protocol.EVENT_BEFORE_MS, t + Protocol.EVENT_AFTER_MS)
        }
    }

    private fun checkRearSeat(nowVt: Long) {
        val rear = phones[CameraRole.REAR] ?: return
        exec.execute {
            rear.pollStatus()
            val recentMotion = rear.lastMotionVt > nowVt - 15_000
            if (rear.alive && (recentMotion || rear.motion >= 0.02f)) {
                main.post { listeners.forEach { it.onExitAlert("뒷좌석에 움직임이 있어요. 내리기 전에 확인하세요.") } }
            }
        }
    }

    private fun pollPhones() {
        phones.values.filter { it.alive }.forEach { it.pollStatus() }
        notifyChanged(force = true)
    }

    private fun handle(req: MiniHttpServer.Request, res: MiniHttpServer.Response) {
        when (req.path) {
            "/time" -> res.text(System.currentTimeMillis().toString())
            "/register" -> {
                if (req.query["token"] != token) return res.text("forbidden", 403)
                val role = CameraRole.parse(req.query["role"])
                val host = req.query["host"]?.takeIf { it.isNotBlank() } ?: req.remoteHost
                val port = req.query["port"]?.toIntOrNull() ?: Protocol.PHONE_PORT
                val link = phones.getOrPut(role) { PhoneLink(role, host, port, token) }
                link.host = host
                link.port = port
                link.lastSeenMs = System.currentTimeMillis()
                res.text(KeyValue.encode(mapOf("ignition" to if (latest?.ignitionOn == true) 1 else 0)))
                notifyChanged(force = true)
            }
        }
    }

    private fun notifyChanged(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotify < 200) return
        lastNotify = now
        main.post { listeners.forEach { it.onChanged() } }
    }
}
