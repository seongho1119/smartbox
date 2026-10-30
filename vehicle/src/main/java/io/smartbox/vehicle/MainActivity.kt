package io.smartbox.vehicle

import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import io.smartbox.core.CameraRole
import io.smartbox.core.Gear
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** 차량 화면. 차량 상태, 카메라 연결, 실시간 화면, 저장된 기록을 보여 준다. */
class MainActivity : AppCompatActivity(), Controller.Listener {

    private enum class PreviewMode { LARGE, SMALL, HIDDEN }

    private val c get() = controller
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var recView: TextView
    private lateinit var vehicleView: TextView
    private lateinit var sourceView: TextView
    private lateinit var simSwitch: SwitchCompat
    private lateinit var simBox: LinearLayout
    private lateinit var ignitionButton: Button
    private lateinit var phonesView: TextView
    private lateinit var qrImage: ImageView
    private lateinit var pairText: TextView
    private lateinit var previewBox: FrameLayout
    private lateinit var previewImage: ImageView
    private lateinit var previewHint: TextView
    private lateinit var peekButton: Button
    private lateinit var roleButtons: Map<CameraRole, Button>
    private lateinit var eventsBox: LinearLayout

    private var stream: PreviewStream? = null
    private var streamUrl: String? = null
    private var previewRole = CameraRole.REAR
    private var peekUntil = 0L
    private var lastQr: String? = null
    private var renderedEvents = -1
    private var alertShowing = false
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.KOREA)

    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            requestPermissions(arrayOf("android.car.permission.CAR_SPEED"), 1)
        }
    }

    override fun onStart() {
        super.onStart()
        c.listeners.add(this)
        handler.post(ticker)
    }

    override fun onStop() {
        super.onStop()
        c.listeners.remove(this)
        handler.removeCallbacks(ticker)
        stopStream()
    }

    override fun onChanged() = render()

    override fun onExitAlert(message: String) {
        if (alertShowing) return
        alertShowing = true
        runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90).startTone(ToneGenerator.TONE_PROP_BEEP2, 800) }
        AlertDialog.Builder(this)
            .setTitle("하차 전 뒷좌석 확인")
            .setMessage(message)
            .setPositiveButton("확인했어요", null)
            .setOnDismissListener { alertShowing = false }
            .show()
    }

    // ---------------------------------------------------------------- 화면 구성

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            val p = Ui.dp(this@MainActivity, 16)
            setPadding(p, p, p, p)
        }

        // 상단: 제목 + 녹화 표시(탑승자도 녹화 여부를 알 수 있도록 항상 보인다)
        recView = Ui.text(this, "", 18f).apply { gravity = Gravity.END }
        root.addView(Ui.row(this, Ui.text(this, "스마트박스", 24f), recView))

        val wide = resources.displayMetrics.widthPixels > resources.displayMetrics.heightPixels
        val body = LinearLayout(this).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setPadding(0, Ui.dp(this@MainActivity, 12), 0, 0)
        }
        val left = buildLeft()
        val right = buildRight()
        if (wide) {
            body.addView(ScrollView(this).apply { addView(left) }, LinearLayout.LayoutParams(0, -1, 1f))
            body.addView(View(this), LinearLayout.LayoutParams(Ui.dp(this, 16), 1))
            body.addView(right, LinearLayout.LayoutParams(0, -1, 1.3f))
            root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            body.addView(left)
            body.addView(right, LinearLayout.LayoutParams(-1, Ui.dp(this, 700)))
            root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        return root
    }

    private fun buildLeft(): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val vehicleCard = Ui.card(this, "차량 상태")
        vehicleView = Ui.text(this, "", 26f)
        sourceView = Ui.text(this, "", 13f, Ui.MUTED)
        simSwitch = SwitchCompat(this).apply {
            text = "시뮬레이터 사용"
            setTextColor(Ui.TEXT)
            visibility = if (c.carApiAvailable) View.VISIBLE else View.GONE
            isChecked = c.source is SimulatedSource
            setOnCheckedChangeListener { _, on -> c.useSimulator(on) }
        }
        vehicleCard.addView(vehicleView)
        vehicleCard.addView(sourceView)
        vehicleCard.addView(simSwitch)

        simBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun sim(block: SimulatedSource.() -> Unit) = { (c.source as? SimulatedSource)?.block(); Unit }
        ignitionButton = Ui.button(this, "시동", sim { toggleIgnition() })
        simBox.addView(Ui.row(this, ignitionButton, Ui.button(this, "출발", sim { drive() }), Ui.button(this, "P단 주차", sim { park() })))
        simBox.addView(
            Ui.row(
                this,
                Ui.button(this, "급가속", sim { hardAccel() }),
                Ui.button(this, "급제동", sim { hardBrake() }),
                Ui.button(this, "저속 페달 급조작", sim { pedalSurge() }),
            )
        )
        vehicleCard.addView(simBox)
        col.addView(vehicleCard)

        val camCard = Ui.card(this, "카메라")
        phonesView = Ui.text(this, "", 16f)
        camCard.addView(phonesView)
        camCard.addView(Ui.button(this, "지금 기록 저장") {
            c.saveNow()
            toast("앞뒤 20초 영상과 주행 데이터를 저장해요")
        })
        col.addView(camCard)

        val pairCard = Ui.card(this, "스마트폰 연결")
        qrImage = ImageView(this)
        pairText = Ui.text(this, "", 15f)
        pairCard.addView(qrImage, LinearLayout.LayoutParams(Ui.dp(this, 170), Ui.dp(this, 170)))
        pairCard.addView(pairText)
        pairCard.addView(Ui.text(this, "스마트폰 앱에서 QR 코드를 스캔하거나 주소와 코드를 입력하세요. 두 기기는 같은 Wi-Fi(또는 핫스팟)에 있어야 해요.", 13f, Ui.MUTED))
        col.addView(pairCard)
        return col
    }

    private fun buildRight(): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        roleButtons = CameraRole.entries.associateWith { role ->
            Ui.button(this, role.label) { previewRole = role; render() }
        }
        col.addView(
            Ui.row(this, Ui.text(this, "실시간 화면", 18f), roleButtons.getValue(CameraRole.REAR), roleButtons.getValue(CameraRole.PEDAL))
        )

        previewBox = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        previewImage = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        previewHint = Ui.text(this, "", 16f, Ui.MUTED).apply { gravity = Gravity.CENTER }
        previewBox.addView(previewImage, FrameLayout.LayoutParams(-1, -1))
        previewBox.addView(previewHint, FrameLayout.LayoutParams(-1, -1))
        col.addView(previewBox, LinearLayout.LayoutParams(-1, Ui.dp(this, 300)))

        peekButton = Ui.button(this, "뒷좌석 5초 보기") {
            previewRole = CameraRole.REAR
            peekUntil = System.currentTimeMillis() + 5_000
            render()
        }
        col.addView(peekButton)

        col.addView(Ui.text(this, "저장된 기록", 18f).apply { setPadding(0, Ui.dp(this@MainActivity, 12), 0, Ui.dp(this@MainActivity, 6)) })
        eventsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(ScrollView(this).apply { addView(eventsBox) }, LinearLayout.LayoutParams(-1, 0, 1f))
        return col
    }

    // ---------------------------------------------------------------- 화면 갱신

    private fun render() {
        val s = c.latest
        vehicleView.text = s?.let {
            "${it.speedKmh.roundToInt()} km/h · ${gearLabel(it.gear)} · 시동 ${if (it.ignitionOn) "켜짐" else "꺼짐"}"
        } ?: "차량 데이터를 기다리는 중"
        sourceView.text = "데이터: ${c.source.name}"
        val sim = c.source as? SimulatedSource
        simBox.visibility = if (sim != null) View.VISIBLE else View.GONE
        ignitionButton.text = if (sim?.ignition == true) "시동 끄기" else "시동 켜기"

        val recording = c.phones.values.filter { it.recording }.map { it.role.label }
        recView.text = if (recording.isEmpty()) "○ 녹화 안 함" else "● 녹화 중 · ${recording.joinToString(", ")}"
        recView.setTextColor(if (recording.isEmpty()) Ui.MUTED else Ui.RED)

        phonesView.text = CameraRole.entries.joinToString("\n") { role ->
            val link = c.phones[role]
            val state = when {
                link == null -> "연결 안 됨"
                !link.alive -> "연결 끊김"
                else -> buildString {
                    append(if (link.recording) "녹화 중" else "대기")
                    link.batteryTemp?.takeIf { it > 0 }?.let {
                        append(" · 휴대폰 ${it.roundToInt()}°C")
                        if (it >= 45f) append(" (과열 주의)")
                    }
                }
            }
            "${role.label} 카메라: $state"
        }

        val info = c.pairingInfo()
        if (info == null) {
            pairText.text = "Wi-Fi 또는 핫스팟에 연결해 주세요"
            qrImage.setImageDrawable(null)
            lastQr = null
        } else {
            val uri = info.toUri()
            if (uri != lastQr) {
                qrImage.setImageBitmap(Ui.qr(uri, 400))
                lastQr = uri
            }
            pairText.text = "주소 ${info.host} · 연결 코드 ${info.token}"
        }
        c.serverError?.let { pairText.text = "${pairText.text}\n$it" }

        renderPreview()
        renderEvents()
    }

    /** 주행 중에는 영상을 숨기고, 버튼을 누르면 작은 화면으로 5초만 보여 준다. 정차 중에는 크게 보여 준다. */
    private fun renderPreview() {
        val driving = c.isDriving()
        val peek = System.currentTimeMillis() < peekUntil
        val mode = when {
            !driving -> PreviewMode.LARGE
            peek -> PreviewMode.SMALL
            else -> PreviewMode.HIDDEN
        }
        if (driving) previewRole = CameraRole.REAR
        roleButtons.forEach { (role, b) ->
            b.isEnabled = !driving
            b.alpha = if (role == previewRole) 1f else 0.6f
        }
        peekButton.visibility = if (driving && !peek) View.VISIBLE else View.GONE

        val height = when (mode) {
            PreviewMode.LARGE -> 300
            PreviewMode.SMALL -> 160
            PreviewMode.HIDDEN -> 70
        }
        val lp = previewBox.layoutParams
        if (lp.height != Ui.dp(this, height)) {
            lp.height = Ui.dp(this, height)
            previewBox.layoutParams = lp
        }

        val link = c.phones[previewRole]?.takeIf { it.alive }
        when {
            mode == PreviewMode.HIDDEN -> {
                stopStream()
                previewHint.text = "안전을 위해 주행 중에는 영상을 숨깁니다"
            }
            link == null -> {
                stopStream()
                previewHint.text = "${previewRole.label} 카메라가 연결되지 않았어요"
            }
            else -> {
                startStream(link.previewUrl()) { link.rotation }
                previewHint.text = ""
            }
        }
    }

    private fun renderEvents() {
        if (c.events.size == renderedEvents) return
        renderedEvents = c.events.size
        eventsBox.removeAllViews()
        if (c.events.isEmpty()) {
            eventsBox.addView(Ui.text(this, "아직 저장된 기록이 없어요", 15f, Ui.MUTED))
            return
        }
        c.events.forEach { rec ->
            val e = rec.event
            val label = "${e.type.label} · ${timeFormat.format(Date(e.vehicleTimeMs))} · ${e.speedKmh.roundToInt()} km/h"
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(Ui.text(this, label, 16f), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Ui.button(this, "보기") {
                if (c.isDriving()) toast("안전을 위해 정차 후 볼 수 있어요")
                else startActivity(Intent(this, EventActivity::class.java).putExtra(EventActivity.EXTRA_ID, e.id))
            })
            eventsBox.addView(row)
        }
    }

    private fun startStream(url: String, rotation: () -> Int) {
        if (url == streamUrl && stream != null) return
        stopStream()
        streamUrl = url
        stream = PreviewStream(url) { bmp -> previewImage.setImageBitmap(Ui.rotate(bmp, rotation())) }
    }

    private fun stopStream() {
        stream?.stop()
        stream = null
        streamUrl = null
        previewImage.setImageDrawable(null)
    }

    private fun gearLabel(g: Gear) = when (g) {
        Gear.UNKNOWN -> "기어 정보 없음"
        else -> "${g.name}단"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
