package io.smartbox.phone

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import io.smartbox.core.ClockSync
import io.smartbox.core.Http
import io.smartbox.core.KeyValue
import io.smartbox.core.MiniHttpServer
import io.smartbox.core.Mjpeg
import io.smartbox.core.MotionDetector
import io.smartbox.core.NetUtil
import io.smartbox.core.Protocol
import io.smartbox.core.SegmentStore
import io.smartbox.core.buildQuery
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 스마트폰 쪽 핵심 서비스.
 *
 * 1. 대기 모드로 계속 실행되며(포그라운드 서비스), 화면이 꺼져 있어도 동작한다.
 * 2. 충전이 시작되거나(= 시동을 걸어 차량 USB 전원이 들어옴) 차량이 시동 신호를 보내면 녹화를 시작한다.
 * 3. 20초 단위로 루프 녹화하고, 오래된 조각은 지운다. 이벤트가 오면 앞뒤 구간을 따로 보관한다.
 * 4. 같은 네트워크의 차량 앱에 미리보기(MJPEG), 상태, 보관 영상을 제공한다.
 */
class RecorderService : LifecycleService() {

    companion object {
        const val ACTION_STANDBY = "io.smartbox.phone.STANDBY"
        const val ACTION_STOP = "io.smartbox.phone.STOP"
        const val ACTION_FORCE = "io.smartbox.phone.FORCE"
        const val EXTRA_ON = "on"
        private const val CHANNEL = "recorder"
        private const val NOTIF_ID = 1
        private const val UNPLUG_GRACE_MS = 10_000L
        private const val LINK_LOST_MS = 15_000L

        /** 화면(MainActivity)에서 읽는 현재 상태. */
        @Volatile var status = Status()
            private set
    }

    data class Status(
        val running: Boolean = false,
        val recording: Boolean = false,
        val charging: Boolean = false,
        val ignition: Boolean = false,
        val forced: Boolean = false,
        val linked: Boolean = false,
        val synced: Boolean = false,
        val myIp: String? = null,
        val savedEvents: Int = 0,
        val error: String? = null,
    )

    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newScheduledThreadPool(2)
    private val analysisExec = Executors.newSingleThreadExecutor()

    private var provider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var binding = false
    private var cameraBound = false
    private var started = false

    private val store = SegmentStore(maxUnprotectedBytes = 1_000_000_000L)
    private val pendingEvents = ConcurrentHashMap<String, Long>() // 이벤트 id → 스마트폰 시각
    private val clock = ClockSync()
    private val motion = MotionDetector()

    @Volatile private var motionScore = 0f
    @Volatile private var lastMotionMs = 0L
    @Volatile private var rotation = 0
    private val frameLock = Object()
    private var frame: ByteArray? = null
    private var frameNo = 0L
    private val previewClients = AtomicInteger(0)
    private var lastJpegMs = 0L

    @Volatile private var charging = false
    @Volatile private var ignition = false
    @Volatile private var forced = false
    @Volatile private var lastLinkMs = 0L
    @Volatile private var lastError: String? = null
    @Volatile private var myIp: String? = null

    private var server: MiniHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var heartbeat: ScheduledFuture<*>? = null
    private val stopAfterUnplug = Runnable { charging = false; update() }

    private val segDir by lazy { File(getExternalFilesDir(null) ?: filesDir, "segments").apply { mkdirs() } }
    private val eventsDir by lazy { File(getExternalFilesDir(null) ?: filesDir, "events").apply { mkdirs() } }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    main.removeCallbacks(stopAfterUnplug)
                    charging = true
                    update()
                }
                // 잠깐 접촉이 끊기는 경우를 대비해 10초 기다렸다가 멈춘다.
                Intent.ACTION_POWER_DISCONNECTED -> main.postDelayed(stopAfterUnplug, UNPLUG_GRACE_MS)
            }
        }
    }

    // ---------------------------------------------------------------- 생명주기

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground()
        startOnce()
        if (intent?.action == ACTION_FORCE) forced = intent.getBooleanExtra(EXTRA_ON, false)
        update()
        // 시스템이 서비스를 강제 종료한 뒤 백그라운드에서 카메라를 다시 켤 수는 없으므로 자동 재시작하지 않는다.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun startOnce() {
        if (started) return
        started = true
        prefs = Prefs(this)
        segDir.listFiles()?.forEach { it.delete() } // 이전 실행의 임시 조각 정리
        ContextCompat.registerReceiver(
            this, powerReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        charging = isPluggedNow()
        server = MiniHttpServer(Protocol.PHONE_PORT, ::handle).also {
            try {
                it.start()
            } catch (e: Exception) {
                lastError = "서버 시작 실패: ${e.message}"
            }
        }
        heartbeat = io.scheduleWithFixedDelay({ runCatching { heartbeat() } }, 0, Protocol.HEARTBEAT_MS, TimeUnit.MILLISECONDS)
    }

    private fun shutdown() {
        if (!started) return
        started = false
        forced = false; charging = false; ignition = false
        heartbeat?.cancel(true)
        server?.stop()
        recording?.stop()
        recording = null
        provider?.unbindAll()
        cameraBound = false
        runCatching { unregisterReceiver(powerReceiver) }
        main.removeCallbacks(stopAfterUnplug)
        releaseWakeLock()
        status = Status()
    }

    // ---------------------------------------------------------------- 녹화 제어

    /** 지금 녹화해야 하는지 판단하고 카메라·녹화를 맞춘다. 항상 메인 스레드에서 실행. */
    private fun update() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { update() }
            return
        }
        val wanted = started && (charging || ignition || forced)
        when {
            wanted && !cameraBound -> bindCamera()
            wanted && recording == null -> startSegment()
            !wanted && recording != null -> recording?.stop() // 마무리 후 onFinalize에서 카메라 해제
            !wanted && recording == null && cameraBound -> {
                provider?.unbindAll()
                cameraBound = false
                videoCapture = null
                releaseWakeLock()
            }
        }
        publish()
    }

    private fun bindCamera() {
        if (binding) return
        binding = true
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            binding = false
            try {
                val p = future.get()
                provider = p
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                    .build()
                val vc = VideoCapture.withOutput(recorder)
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder().setResolutionStrategy(
                            ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                        ).build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExec, ::analyze)
                p.unbindAll()
                p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, vc, analysis)
                videoCapture = vc
                cameraBound = true
                acquireWakeLock()
                lastError = null
            } catch (e: Exception) {
                lastError = "카메라 연결 실패: ${e.message}"
            }
            update()
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("MissingPermission") // 음성 없이 녹화하므로 RECORD_AUDIO가 필요 없다.
    private fun startSegment() {
        val vc = videoCapture ?: return
        val file = File(segDir, "seg_${System.currentTimeMillis()}.mp4")
        val options = FileOutputOptions.Builder(file).setDurationLimitMillis(Protocol.SEGMENT_MS).build()
        var startMs = System.currentTimeMillis()
        recording = vc.output.prepareRecording(this, options)
            .start(ContextCompat.getMainExecutor(this)) { ev ->
                when (ev) {
                    is VideoRecordEvent.Start -> startMs = System.currentTimeMillis()
                    is VideoRecordEvent.Finalize -> onFinalize(file, startMs, ev)
                    else -> Unit
                }
            }
        publish()
    }

    private fun onFinalize(file: File, startMs: Long, ev: VideoRecordEvent.Finalize) {
        recording = null
        val ok = !ev.hasError() || ev.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED
        if (ok && file.length() > 0) {
            val removed = synchronized(store) {
                store.add(SegmentStore.Segment(file.name, startMs, System.currentTimeMillis(), file.length()))
            }
            removed.forEach { File(segDir, it).delete() }
        } else {
            file.delete()
            if (ev.hasError()) lastError = "녹화 오류 코드 ${ev.error}"
        }
        update() // 계속 녹화해야 하면 다음 조각 시작
    }

    // ---------------------------------------------------------------- 프레임 분석(움직임·미리보기)

    private fun analyze(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            rotation = image.imageInfo.rotationDegrees
            val y = image.planes[0]
            val buf = y.buffer.duplicate().apply { rewind() }
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            val score = motion.score(bytes, image.width, image.height, y.rowStride)
            motionScore = score
            if (motion.isMotion(score)) lastMotionMs = now

            if (previewClients.get() > 0 && now - lastJpegMs >= 150) {
                lastJpegMs = now
                val jpeg = Yuv.toJpeg(image, 60)
                synchronized(frameLock) {
                    frame = jpeg
                    frameNo++
                    frameLock.notifyAll()
                }
            }
        } catch (_: Exception) {
        } finally {
            image.close()
        }
    }

    // ---------------------------------------------------------------- 차량과 통신

    private fun heartbeat() {
        val p = prefs.pairing
        myIp = NetUtil.localIpv4()
        if (p != null) {
            val base = "http://${p.host}:${p.port}"
            try {
                repeat(3) {
                    val t0 = System.currentTimeMillis()
                    val vt = Http.get("$base/time", 2000).trim().toLong()
                    clock.addMeasurement(t0, vt, System.currentTimeMillis())
                }
                val q = buildQuery(
                    mapOf("token" to p.token, "host" to myIp, "port" to Protocol.PHONE_PORT, "role" to prefs.role.name)
                )
                val resp = KeyValue.decode(Http.post("$base/register?$q", 2000))
                lastLinkMs = System.currentTimeMillis()
                val ign = resp["ignition"] == "1"
                if (ign != ignition) {
                    ignition = ign
                    update()
                }
            } catch (_: Exception) {
                if (System.currentTimeMillis() - lastLinkMs > LINK_LOST_MS && ignition) {
                    ignition = false // 차량과 연결이 끊기면 시동 신호도 끈다(충전 중이면 녹화는 계속).
                    update()
                }
            }
        }
        processPendingEvents()
        publish()
    }

    /** 이벤트 뒤 구간까지 녹화가 끝나면, 해당 조각을 events/{id}/ 로 복사해 영구 보관한다. */
    private fun processPendingEvents() {
        val now = System.currentTimeMillis()
        for ((id, phoneTime) in pendingEvents) {
            val readyAt = phoneTime + Protocol.EVENT_AFTER_MS + Protocol.SEGMENT_MS + 3_000
            if (now < readyAt && (charging || ignition || forced)) continue
            val segs = synchronized(store) { store.segmentsFor(id) }
            val dir = File(eventsDir, id).apply { mkdirs() }
            val meta = StringBuilder("status=ready\ncount=${segs.size}\nrole=${prefs.role.name}\n")
            segs.forEachIndexed { i, s ->
                File(segDir, s.name).takeIf { it.exists() }?.copyTo(File(dir, "$i.mp4"), overwrite = true)
                meta.append("seg$i=${clock.phoneToVehicle(s.startMs)},${clock.phoneToVehicle(s.endMs)}\n")
            }
            File(dir, "meta.txt").writeText(meta.toString())
            val removed = synchronized(store) { store.release(id) }
            removed.forEach { File(segDir, it).delete() }
            pendingEvents.remove(id)
        }
    }

    private fun handle(req: MiniHttpServer.Request, res: MiniHttpServer.Response) {
        val pairing = prefs.pairing
        if (pairing == null || req.query["token"] != pairing.token) {
            res.text("forbidden", 403)
            return
        }
        when {
            req.path == "/status" -> res.text(
                KeyValue.encode(
                    mapOf(
                        "role" to prefs.role.name,
                        "recording" to (recording != null),
                        "motion" to motionScore,
                        "lastMotionVt" to if (lastMotionMs > 0) clock.phoneToVehicle(lastMotionMs) else 0,
                        "rotation" to rotation,
                        "batteryTemp" to batteryTempC(),
                        "charging" to charging,
                        "synced" to clock.synced,
                    )
                )
            )
            req.path == "/preview.mjpeg" -> streamPreview(res)
            req.path == "/ignition" -> {
                ignition = req.query["on"] == "1"
                update()
                res.text("ok")
            }
            req.path == "/event" -> {
                val id = req.query["id"]?.takeIf(::safeId) ?: return res.text("bad id", 400)
                val vt = req.query["vt"]?.toLongOrNull() ?: return res.text("bad vt", 400)
                val pt = clock.vehicleToPhone(vt)
                synchronized(store) { store.protect(id, pt - Protocol.EVENT_BEFORE_MS, pt + Protocol.EVENT_AFTER_MS) }
                pendingEvents[id] = pt
                res.text("ok")
            }
            req.path.startsWith("/events/") -> {
                val parts = req.path.removePrefix("/events/").split('/')
                val id = parts.getOrNull(0)?.takeIf(::safeId) ?: return res.text("bad id", 400)
                val dir = File(eventsDir, id)
                when (val name = parts.getOrNull(1)) {
                    "list" -> {
                        val meta = File(dir, "meta.txt")
                        when {
                            meta.exists() -> res.text(meta.readText())
                            pendingEvents.containsKey(id) -> res.text("status=pending")
                            else -> res.text("status=missing", 404)
                        }
                    }
                    else -> {
                        val f = File(dir, name ?: "")
                        if (name != null && name.matches(Regex("\\d+\\.mp4")) && f.exists()) {
                            f.inputStream().use { res.stream(200, "video/mp4", f.length(), it) }
                        } else res.text("not found", 404)
                    }
                }
            }
        }
    }

    private fun streamPreview(res: MiniHttpServer.Response) {
        previewClients.incrementAndGet()
        try {
            res.open(Mjpeg.CONTENT_TYPE) { out ->
                var last = -1L
                var idleMs = 0L
                while (idleMs < 10_000) {
                    val f: ByteArray? = synchronized(frameLock) {
                        if (frameNo == last) frameLock.wait(1000)
                        if (frameNo == last) null else { last = frameNo; frame }
                    }
                    if (f == null) { idleMs += 1000; continue }
                    idleMs = 0
                    Mjpeg.writeFrame(out, f)
                }
            }
        } finally {
            previewClients.decrementAndGet()
        }
    }

    private fun safeId(s: String) = s.matches(Regex("[A-Za-z0-9_-]{1,64}"))

    // ---------------------------------------------------------------- 상태·알림

    private fun publish() {
        val rec = recording != null
        val s = Status(
            running = started, recording = rec, charging = charging, ignition = ignition, forced = forced,
            linked = System.currentTimeMillis() - lastLinkMs < LINK_LOST_MS, synced = clock.synced,
            myIp = myIp, savedEvents = eventsDir.list()?.size ?: 0, error = lastError,
        )
        val changed = s.recording != status.recording || s.running != status.running
        status = s
        if (changed && started) {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification())
        }
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "녹화 상태", NotificationManager.IMPORTANCE_LOW))
        }
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notification(), type)
    }

    private fun notification(): Notification {
        val rec = recording != null
        val role = if (::prefs.isInitialized) prefs.role.label else ""
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(if (rec) "스마트박스 · $role 녹화 중" else "스마트박스 · 대기 중")
            .setContentText(if (rec) "화면을 꺼도 계속 녹화합니다" else "충전 케이블을 연결하면 녹화를 시작합니다")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun batteryIntent(): Intent? = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    private fun isPluggedNow() = (batteryIntent()?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    private fun batteryTempC() = (batteryIntent()?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smartbox:recording")
            .apply { acquire(12 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }
}
