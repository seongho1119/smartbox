package io.smartbox.vehicle

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import io.smartbox.core.CameraRole
import io.smartbox.core.Protocol
import io.smartbox.core.VehicleLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 저장된 기록 한 건을 보는 화면.
 * 스마트폰에서 이벤트 영상을 받아 재생하면서, 같은 시각의 속도·페달·브레이크 그래프에 재생 위치를 표시한다.
 */
class EventActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "id"
    }

    private lateinit var rec: EventRecord
    private lateinit var graph: SpeedGraphView
    private lateinit var statusText: TextView
    private lateinit var video: VideoView
    private val handler = Handler(Looper.getMainLooper())

    /** (영상 파일, 그 조각이 시작된 차량 시각) */
    private var clips: List<Pair<File, Long>> = emptyList()
    private var clipIndex = 0
    @Volatile private var loadingRole: CameraRole? = null

    private val ticker = object : Runnable {
        override fun run() {
            if (controller.isDriving()) {
                if (video.isPlaying) video.pause()
                statusText.text = "주행 중에는 영상을 재생하지 않아요. 정차 후 다시 열어 주세요."
            }
            if (graph.samples.isEmpty()) loadSamples()
            clips.getOrNull(clipIndex)?.let { (_, startVt) ->
                if (video.isPlaying) graph.cursorMs = startVt + video.currentPosition
            }
            handler.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val found = intent.getStringExtra(EXTRA_ID)?.let { controller.event(it) }
        if (found == null) {
            finish()
            return
        }
        rec = found
        setContentView(buildUi())
        loadSamples()
        loadVideo(CameraRole.PEDAL)
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        if (::video.isInitialized) video.pause()
    }

    private fun buildUi(): LinearLayout {
        val e = rec.event
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            val p = Ui.dp(this@EventActivity, 16)
            setPadding(p, p, p, p)
        }
        val time = SimpleDateFormat("yyyy.MM.dd HH:mm:ss", Locale.KOREA).format(Date(e.vehicleTimeMs))
        root.addView(Ui.text(this, "${e.type.label} · $time", 22f))
        root.addView(
            Ui.text(
                this,
                "속도 ${e.speedKmh.roundToInt()} km/h · 가속도 ${"%.1f".format(e.accelMs2)} m/s² · 앞뒤 20초 기록",
                14f, Ui.MUTED,
            )
        )
        graph = SpeedGraphView(this).apply {
            fromMs = e.vehicleTimeMs - Protocol.EVENT_BEFORE_MS
            toMs = e.vehicleTimeMs + Protocol.EVENT_AFTER_MS
            eventMs = e.vehicleTimeMs
        }
        root.addView(graph, LinearLayout.LayoutParams(-1, Ui.dp(this, 200)))

        root.addView(
            Ui.row(
                this,
                Ui.button(this, "페달 영상") { loadVideo(CameraRole.PEDAL) },
                Ui.button(this, "뒷좌석 영상") { loadVideo(CameraRole.REAR) },
                Ui.button(this, "처음부터") { play(0) },
                Ui.button(this, "데이터 저장(CSV)") { exportCsv() },
                Ui.button(this, "닫기") { finish() },
            )
        )
        statusText = Ui.text(this, "", 15f, Ui.MUTED)
        root.addView(statusText)
        video = VideoView(this)
        root.addView(video, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun loadSamples() {
        val e = rec.event
        graph.samples = rec.samples
            ?: controller.log.between(e.vehicleTimeMs - Protocol.EVENT_BEFORE_MS, e.vehicleTimeMs + Protocol.EVENT_AFTER_MS)
    }

    /** 스마트폰에 보관된 이벤트 영상 조각을 내려받는다. 아직 녹화 중이면 기다렸다가 다시 확인한다. */
    private fun loadVideo(role: CameraRole) {
        loadingRole = role
        video.stopPlayback()
        clips = emptyList()
        val id = rec.event.id
        statusText.text = "${role.label} 영상을 확인하는 중..."
        Thread {
            val link = controller.phones[role]
            if (link == null) {
                ui(role) { statusText.text = "${role.label} 카메라가 연결되지 않았어요" }
                return@Thread
            }
            var info = link.eventInfo(id)
            var tries = 0
            while (info["status"] == "pending" && tries++ < 40 && loadingRole == role) {
                ui(role) { statusText.text = "${role.label} 카메라가 이벤트 뒤 20초까지 녹화를 마무리하는 중이에요..." }
                Thread.sleep(3000)
                info = link.eventInfo(id)
            }
            if (info["status"] != "ready") {
                ui(role) { statusText.text = "${role.label} 카메라에 이 기록의 영상이 없어요" }
                return@Thread
            }
            val count = info["count"]?.toIntOrNull() ?: 0
            val dir = File(cacheDir, "events/$id/${role.name}").apply { mkdirs() }
            val list = mutableListOf<Pair<File, Long>>()
            try {
                for (i in 0 until count) {
                    val f = File(dir, "$i.mp4")
                    if (!f.exists()) {
                        ui(role) { statusText.text = "영상을 받는 중... (${i + 1}/$count)" }
                        link.downloadClip(id, i, f)
                    }
                    val start = info["seg$i"]?.substringBefore(',')?.toLongOrNull() ?: continue
                    list += f to start
                }
            } catch (e: Exception) {
                ui(role) { statusText.text = "영상을 받지 못했어요: ${e.message}" }
                return@Thread
            }
            ui(role) {
                clips = list
                statusText.text = "${role.label} 영상 · 흰 선이 지금 재생 중인 시각이에요"
                play(0)
            }
        }.start()
    }

    private fun play(index: Int) {
        if (controller.isDriving()) return
        val clip = clips.getOrNull(index) ?: return
        clipIndex = index
        video.setVideoPath(clip.first.path)
        video.setOnCompletionListener { play(index + 1) }
        video.start()
    }

    private fun exportCsv() {
        val samples = graph.samples
        if (samples.isEmpty()) {
            toast("저장할 데이터가 없어요")
            return
        }
        val f = File(getExternalFilesDir(null) ?: filesDir, "smartbox_${rec.event.id}.csv")
        f.writeText(VehicleLog.toCsv(samples))
        toast("저장했어요: ${f.path}")
    }

    private fun ui(role: CameraRole, block: () -> Unit) = runOnUiThread { if (loadingRole == role) block() }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
