package io.smartbox.phone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import io.smartbox.core.CameraRole
import io.smartbox.core.PairingInfo
import io.smartbox.core.Protocol

/** 처음 한 번 설정하는 화면. 설정이 끝나면 화면을 꺼도 서비스가 알아서 녹화한다. */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var statusView: TextView
    private lateinit var pairingView: TextView
    private lateinit var forceButton: Button
    private val handler = Handler(Looper.getMainLooper())
    private var afterPermission: (() -> Unit)? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it } || hasCameraPermission()) afterPermission?.invoke()
            else toast("카메라 권한이 있어야 녹화할 수 있어요")
            afterPermission = null
        }

    private val scanLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val info = r.data?.getStringExtra(ScanActivity.EXTRA_PAIRING)?.let(PairingInfo::parse)
            if (info != null) {
                prefs.pairing = info
                toast("차량과 연결 정보를 저장했어요")
                refreshPairing()
            }
        }

    private val ticker = object : Runnable {
        override fun run() {
            renderStatus()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildUi())
        refreshPairing()
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    // ---------------------------------------------------------------- 화면 구성

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(20)
            setPadding(p, p, p, p)
        }
        root.addView(TextView(this).apply {
            text = "스마트박스 카메라"
            textSize = 24f
        })
        statusView = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(statusView)

        root.addView(section("1. 카메라 위치"))
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        CameraRole.entries.forEach { role ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = role.label
                isChecked = prefs.role == role
                setOnCheckedChangeListener { _, checked -> if (checked) prefs.role = role }
            })
        }
        root.addView(group)

        root.addView(section("2. 차량과 연결"))
        root.addView(button("차량 화면의 QR 코드 스캔") { startScan() })
        root.addView(TextView(this).apply { text = "또는 차량 화면에 보이는 주소와 연결 코드를 직접 입력" })
        val hostInput = EditText(this).apply {
            hint = "차량 주소 (예: 192.168.43.20)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val codeInput = EditText(this).apply {
            hint = "연결 코드 6자리"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        root.addView(hostInput)
        root.addView(codeInput)
        root.addView(button("입력한 정보로 연결") {
            val host = hostInput.text.toString().trim()
            val code = codeInput.text.toString().trim()
            if (host.isEmpty() || code.length != 6) {
                toast("주소와 6자리 코드를 확인해 주세요")
            } else {
                prefs.pairing = PairingInfo(host, Protocol.VEHICLE_PORT, code)
                refreshPairing()
                toast("저장했어요")
            }
        })
        pairingView = TextView(this).apply { setPadding(0, dp(4), 0, dp(4)) }
        root.addView(pairingView)

        root.addView(section("3. 녹화 대기"))
        root.addView(button("대기 모드 시작") { withCamera { startService(RecorderService.ACTION_STANDBY) } })
        forceButton = button("충전 없이 녹화 테스트") {
            val on = !RecorderService.status.forced
            withCamera { startService(RecorderService.ACTION_FORCE, on) }
        }
        root.addView(forceButton)
        root.addView(button("중지") { startService(RecorderService.ACTION_STOP) })
        root.addView(button("배터리 최적화 제외 설정") { requestBatteryExemption() })

        root.addView(TextView(this).apply {
            setPadding(0, dp(16), 0, 0)
            text = """
                사용 방법
                1. 카메라 위치를 고르고 차량과 연결합니다.
                2. '대기 모드 시작'을 누른 뒤 화면을 꺼도 됩니다.
                3. 시동을 걸어 충전이 시작되면 자동으로 녹화하고, 시동을 끄면 멈춥니다.
                4. 음성은 녹음하지 않으며, 영상은 이 휴대폰에만 저장됩니다.
            """.trimIndent()
        })
        return ScrollView(this).apply { addView(root) }
    }

    private fun renderStatus() {
        val s = RecorderService.status
        val lines = mutableListOf<String>()
        lines += when {
            !s.running -> "상태: 꺼짐"
            s.recording -> "상태: 녹화 중 (${prefs.role.label})"
            else -> "상태: 대기 중"
        }
        if (s.running) {
            lines += "충전 ${if (s.charging) "중" else "안 함"} · 시동 신호 ${if (s.ignition) "켜짐" else "꺼짐"}" +
                if (s.forced) " · 테스트 녹화" else ""
            lines += "차량 연결: ${if (s.linked) "연결됨" else "연결 안 됨"}" + if (s.synced) " · 시간 맞춤 완료" else ""
            lines += "내 주소: ${s.myIp ?: "-"} · 보관된 이벤트 ${s.savedEvents}개"
        }
        s.error?.let { lines += "오류: $it" }
        statusView.text = lines.joinToString("\n")
        forceButton.text = if (s.forced) "테스트 녹화 끄기" else "충전 없이 녹화 테스트"
    }

    private fun refreshPairing() {
        val p = prefs.pairing
        pairingView.text = if (p == null) "아직 차량과 연결하지 않았어요" else "연결 대상: ${p.host}:${p.port}"
    }

    // ---------------------------------------------------------------- 동작

    private fun startScan() = withCamera {
        // 스캔 화면이 카메라를 써야 하므로, 녹화 서비스를 잠시 멈춘다.
        startService(RecorderService.ACTION_STOP)
        handler.postDelayed({ scanLauncher.launch(Intent(this, ScanActivity::class.java)) }, 400)
    }

    private fun startService(action: String, on: Boolean? = null) {
        val intent = Intent(this, RecorderService::class.java).setAction(action)
        on?.let { intent.putExtra(RecorderService.EXTRA_ON, it) }
        if (action == RecorderService.ACTION_STOP) startService(intent)
        else ContextCompat.startForegroundService(this, intent)
    }

    private fun withCamera(block: () -> Unit) {
        val needed = buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) block() else {
            afterPermission = block
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("BatteryLife") // 차량 거치용 기기라 백그라운드 녹화가 핵심 기능이다.
    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("이미 제외되어 있어요")
            return
        }
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    // ---------------------------------------------------------------- 작은 도우미

    private fun section(title: String) = TextView(this).apply {
        text = title
        textSize = 18f
        setPadding(0, dp(18), 0, dp(6))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
