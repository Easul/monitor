package lightly.monitor

import android.app.Activity
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.ImageButton
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import lightly.monitor.ai.MimoApiClient
import lightly.monitor.control.AudioRouteController
import lightly.monitor.control.PermissionController
import lightly.monitor.session.SessionController
import lightly.monitor.signal.SignalCommand
import lightly.monitor.signal.SignalMessage
import lightly.monitor.data.MimoApiStore
import org.webrtc.EglRenderer
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class SessionActivity : Activity() {
    private lateinit var sessionController: SessionController
    private var audioRouteController: AudioRouteController? = null
    private lateinit var permissionController: PermissionController
    private var remoteRenderer: SurfaceViewRenderer? = null
    private var sessionStarted = false
    private var controlsVisible = true
    private var localMicEnabled = true
    private var localSpeakerEnabled = true
    private var remoteMicEnabled = true
    private var remoteCameraEnabled = true
    private var localCameraEnabled = false
    private var isController = false
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var typewriterJob: Job? = null
    private var aiSpeechPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_session)
        enterImmersiveMode()
        permissionController = PermissionController(this)
        sessionController = SessionController(this).apply {
            onState = ::status
            onRemoteVideoTrack = { rtc, track -> attachRemoteVideo(rtc.eglContext(), track) }
            onRemoteBattery = ::showRemoteBattery
        }
        bindControls()
        if (permissionController.hasMediaPermissions()) {
            startSession()
        } else {
            status("请授权摄像头和麦克风")
            permissionController.requestMediaPermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveMode()
    }

    override fun onBackPressed() {
        endLocalCallAndFinish()
    }

    override fun onDestroy() {
        typewriterJob?.cancel()
        uiScope.cancel()
        stopAiSpeech()
        remoteRenderer?.release()
        sessionController.stop()
        audioRouteController?.leaveCommunicationMode()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PermissionController.REQUEST_MEDIA_PERMISSIONS) return
        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startSession()
        } else {
            status("摄像头和麦克风权限被拒绝")
        }
    }

    private fun startSession() {
        if (sessionStarted) return
        sessionStarted = true
        audioRouteController = AudioRouteController(this).also { it.enterCommunicationMode() }
        val host = intent.getStringExtra(EXTRA_HOST)?.let(::normalizeEndpoint)
        if (host.isNullOrBlank()) {
            sessionController.startServer()
            status("被控端已就绪，等待控制端连接")
        } else {
            sessionController.connect(host)
            status("正在连接 $host")
        }
    }

    private fun normalizeEndpoint(host: String): String = host.trim()
        .removePrefix("http://")
        .removePrefix("https://")
        .substringBefore('/')
        .trim()

    private fun bindControls() {
        findViewById<View>(R.id.sessionRoot).setOnClickListener { toggleControls() }
        findViewById<View>(R.id.controlsOverlay).setOnClickListener { }
        findViewById<View>(R.id.backButton).setOnClickListener { endLocalCallAndFinish() }
        isController = !intent.getStringExtra(EXTRA_HOST).isNullOrBlank()
        localMicEnabled = !isController
        localCameraEnabled = !isController
        setControlState(R.id.micButton, localMicEnabled, R.drawable.ic_mic, R.drawable.ic_mic_off)
        setControlState(R.id.speakerButton, true, R.drawable.ic_volume_on, R.drawable.ic_volume_off)
        setControlState(R.id.remoteMicButton, true, R.drawable.ic_remote_mic, R.drawable.ic_remote_mic_off)
        setControlState(R.id.remoteCameraButton, true, R.drawable.ic_remote_camera, R.drawable.ic_remote_camera_off)
        setControlState(R.id.cameraButton, localCameraEnabled, R.drawable.ic_camera, R.drawable.ic_camera_off)
        findViewById<View>(R.id.remoteControlsRow).visibility = if (isController) View.VISIBLE else View.GONE
        findViewById<View>(R.id.aiAnalyzeButton).visibility = if (isController) View.VISIBLE else View.GONE
        findViewById<View>(R.id.remoteBatteryText).visibility = View.GONE
        findViewById<View>(R.id.switchCameraButton).visibility = View.VISIBLE
        findViewById<View>(R.id.remoteSwitchCameraButton).visibility = if (isController) View.VISIBLE else View.GONE
        findViewById<View>(R.id.micButton).setOnClickListener {
            localMicEnabled = sessionController.toggleLocalMic()
            setControlState(R.id.micButton, localMicEnabled, R.drawable.ic_mic, R.drawable.ic_mic_off)
        }
        findViewById<View>(R.id.speakerButton).setOnClickListener {
            localSpeakerEnabled = sessionController.toggleLocalSpeaker()
            setControlState(R.id.speakerButton, localSpeakerEnabled, R.drawable.ic_volume_on, R.drawable.ic_volume_off)
        }
        findViewById<View>(R.id.remoteMicButton).setOnClickListener {
            remoteMicEnabled = sessionController.toggleRemoteMic()
            setControlState(R.id.remoteMicButton, remoteMicEnabled, R.drawable.ic_remote_mic, R.drawable.ic_remote_mic_off)
        }
        findViewById<View>(R.id.remoteCameraButton).setOnClickListener {
            remoteCameraEnabled = sessionController.toggleRemoteCamera()
            setControlState(R.id.remoteCameraButton, remoteCameraEnabled, R.drawable.ic_remote_camera, R.drawable.ic_remote_camera_off)
        }
        findViewById<View>(R.id.aiAnalyzeButton).setOnClickListener { analyzeCurrentFrame() }
        findViewById<View>(R.id.aiResultCloseButton).setOnClickListener { hideAiResultPanel() }
        findViewById<View>(R.id.remoteSwitchCameraButton).setOnClickListener {
            sessionController.send(SignalMessage.Command(SignalCommand.SwitchCamera))
            status("已请求切换被控端摄像头")
        }
        findViewById<View>(R.id.cameraButton).setOnClickListener {
            localCameraEnabled = sessionController.toggleLocalCamera()
            setControlState(R.id.cameraButton, localCameraEnabled, R.drawable.ic_camera, R.drawable.ic_camera_off)
        }
        findViewById<View>(R.id.switchCameraButton).setOnClickListener {
            sessionController.switchLocalCamera()
            status("本机摄像头已切换")
        }
        findViewById<View>(R.id.hangupButton).setOnClickListener { endLocalCallAndFinish() }
    }

    private fun analyzeCurrentFrame() {
        val renderer = remoteRenderer
        if (!isController || renderer == null) {
            status("请先连接远端视频")
            return
        }
        showAiProgress("准备分析画面…")
        val captured = AtomicBoolean(false)
        lateinit var listener: EglRenderer.FrameListener
        listener = EglRenderer.FrameListener { bitmap ->
            if (!captured.compareAndSet(false, true)) {
                bitmap.recycle()
                return@FrameListener
            }
            runOnUiThread { runCatching { renderer.removeFrameListener(listener) } }
            uiScope.launch(Dispatchers.IO) {
                val result = runCatching {
                    showAiProgress("已截取画面，正在压缩图片…")
                    val imageBytes = bitmap.useAsJpegBytes()
                    val config = MimoApiStore(this@SessionActivity).load()
                    val client = MimoApiClient(config)
                    showAiProgress("正在上传图片，避免请求体过大…")
                    val imageUrl = client.uploadTempImage(imageBytes)
                    showAiProgress("图片已上传，正在请求 AI 分析…")
                    val text = client.analyzeImageUrl(imageUrl) { partial -> showAiStreamingText(partial) }
                        .ifBlank { error("AI 返回为空，请稍后重试") }
                    showAiProgress("AI 已返回文字，正在合成语音…")
                    val speech = runCatching { client.synthesizeSpeech(text) }.getOrElse { ByteArray(0) }
                    text to speech
                }
                result.onSuccess { (text, speech) ->
                    showAiResultTypewriter(text)
                    if (speech.isNotEmpty()) {
                        status("正在播放语音结果…")
                        playSpeech(speech)
                    }
                }.onFailure {
                    Log.e(TAG, "AI analysis failed", it)
                    showAiProgress(formatAiFailureHint(it))
                }
            }
        }
        renderer.addFrameListener(listener, 0.35f)
    }

    private fun formatAiFailureHint(error: Throwable): String {
        val detail = error.message?.takeIf { it.isNotBlank() } ?: "未知错误"
        val networkHint = when {
            detail.contains("timeout", ignoreCase = true) || detail.contains("timed out", ignoreCase = true) ->
                "网络连接超时，请确认手机可以直连外网，或暂时关闭会影响外网的 VPN/代理后重试。"
            detail.contains("HTTP 503") || detail.contains("HTTP 504") || detail.contains("Gateway", ignoreCase = true) || detail.contains("没有可用的内网节点") ->
                "AI 网关暂时不可用，请稍后重试；如果正在使用若轻 VPN，Monitor 会尽量走直连网络。"
            detail.contains("Unable to resolve", ignoreCase = true) || detail.contains("Failed to connect", ignoreCase = true) ->
                "无法连接 AI 服务，请检查 Wi-Fi/移动网络、DNS 或代理设置。"
            else ->
                "请检查网络、Mimo API Key 和服务地址后再试。"
        }
        return "AI 分析失败：$detail\n$networkHint"
    }

    private fun Bitmap.useAsJpegBytes(): ByteArray {
        return try {
            val output = ByteArrayOutputStream()
            compress(Bitmap.CompressFormat.JPEG, 70, output)
            output.toByteArray()
        } finally {
            recycle()
        }
    }

    private fun showAiProgress(text: String) {
        runOnUiThread {
            showAiResultPanel()
            findViewById<TextView>(R.id.aiResultText).text = text
            scrollAiResultToBottom()
            status(text)
        }
    }

    private fun showAiStreamingText(text: String) {
        runOnUiThread {
            showAiResultPanel()
            findViewById<TextView>(R.id.aiResultText).text = "AI 正在回答…\n\n$text"
            scrollAiResultToBottom()
            status("AI 正在生成分析结果…")
        }
    }

    private fun showAiResultTypewriter(text: String) {
        typewriterJob?.cancel()
        typewriterJob = uiScope.launch {
            showAiResultPanel()
            val view = findViewById<TextView>(R.id.aiResultText)
            view.text = ""
            status("AI 画面分析完成，正在显示结果…")
            text.forEachIndexed { index, _ ->
                view.text = text.substring(0, index + 1)
                scrollAiResultToBottom()
                delay(32)
            }
            status("AI 画面分析完成")
        }
    }

    private fun showAiResultPanel() {
        findViewById<View>(R.id.aiResultPanel).apply {
            visibility = View.VISIBLE
            bringToFront()
        }
    }

    private fun hideAiResultPanel() {
        typewriterJob?.cancel()
        findViewById<View>(R.id.aiResultPanel).visibility = View.GONE
    }

    private fun scrollAiResultToBottom() {
        findViewById<ScrollView>(R.id.aiResultScroll).post {
            findViewById<ScrollView>(R.id.aiResultScroll).fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun playSpeech(bytes: ByteArray) {
        runCatching {
            stopAiSpeech()
            audioRouteController?.enterCommunicationMode()
            val file = File(cacheDir, "mimo_analysis.wav")
            file.writeBytes(bytes)
            MediaPlayer().apply {
                aiSpeechPlayer = this
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                setDataSource(file.absolutePath)
                setOnCompletionListener { player ->
                    player.release()
                    if (aiSpeechPlayer === player) aiSpeechPlayer = null
                    file.delete()
                }
                setOnErrorListener { player, _, _ ->
                    player.release()
                    if (aiSpeechPlayer === player) aiSpeechPlayer = null
                    file.delete()
                    true
                }
                prepare()
                start()
            }
        }
    }

    private fun stopAiSpeech() {
        aiSpeechPlayer?.let { player ->
            runCatching { player.stop() }
            runCatching { player.release() }
        }
        aiSpeechPlayer = null
        runCatching { File(cacheDir, "mimo_analysis.wav").delete() }
    }

    private fun setControlState(id: Int, enabled: Boolean, enabledIcon: Int, disabledIcon: Int) {
        val button = findViewById<ImageButton>(id)
        button.setImageResource(if (enabled) enabledIcon else disabledIcon)
        button.setBackgroundResource(if (enabled) R.drawable.bg_fab_selected else R.drawable.bg_fab_warm)
        button.alpha = if (enabled) 1f else 0.82f
    }

    private fun endLocalCallAndFinish() {
        sessionController.endCurrentCall()
        finish()
    }

    private fun toggleControls() {
        controlsVisible = !controlsVisible
        findViewById<View>(R.id.controlsOverlay).animate()
            .alpha(if (controlsVisible) 1f else 0f)
            .setDuration(180)
            .withStartAction { if (controlsVisible) findViewById<View>(R.id.controlsOverlay).visibility = View.VISIBLE }
            .withEndAction { if (!controlsVisible) findViewById<View>(R.id.controlsOverlay).visibility = View.GONE }
            .start()
    }

    private fun enterImmersiveMode() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun attachRemoteVideo(eglContext: org.webrtc.EglBase.Context, track: VideoTrack) {
        runOnUiThread {
            val container = findViewById<FrameLayout>(R.id.videoContainer)
            val renderer = remoteRenderer ?: SurfaceViewRenderer(this).also { view ->
                view.init(eglContext, null)
                view.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                view.setMirror(false)
                view.setZOrderMediaOverlay(false)
                container.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                remoteRenderer = view
            }
            findViewById<View>(R.id.emptyVideoHint).visibility = View.GONE
            findViewById<View>(R.id.controlsOverlay).bringToFront()
            findViewById<View>(R.id.emptyVideoHint).bringToFront()
            findViewById<View>(R.id.remoteBatteryText).bringToFront()
            track.addSink(renderer)
            status("远端视频已连接")
        }
    }

    private fun showRemoteBattery(level: Int, charging: Boolean) {
        if (!isController) return
        runOnUiThread {
            findViewById<TextView>(R.id.remoteBatteryText).apply {
                text = if (charging) "被控电量 ${level}% · 充电中" else "被控电量 ${level}%"
                visibility = View.VISIBLE
                bringToFront()
            }
        }
    }

    private fun status(text: String) {
        runOnUiThread { findViewById<TextView>(R.id.sessionStatusText).text = text }
    }

    companion object {
        private const val TAG = "SessionActivity"
        const val EXTRA_HOST = "host"
    }
}
