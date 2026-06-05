package lightly.monitor.session

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import lightly.monitor.easytier.EasyTierManager
import lightly.monitor.easytier.EasyTierNetworkInfoAnalyzer
import lightly.monitor.signal.SignalClient
import lightly.monitor.signal.SignalCommand
import lightly.monitor.signal.SignalConnection
import lightly.monitor.signal.SignalMessage
import lightly.monitor.signal.SignalServer
import lightly.monitor.webrtc.RtcMediaController
import lightly.monitor.webrtc.RtcSession
import org.json.JSONObject
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.VideoTrack

class SessionController(private val context: Context) {
    private var server: SignalServer? = null
    private var client: SignalClient? = null
    private var connection: SignalConnection? = null
    private var rtcSession: RtcSession? = null
    private val controlledSessions = mutableMapOf<SignalConnection, RtcSession>()
    private var controlledEglBase: EglBase? = null
    private var controlledFactory: PeerConnectionFactory? = null
    private var controlledCameraController: RtcMediaController? = null
    private var controlledVideoTrack: VideoTrack? = null
    private var localMicEnabled = true
    private var localCameraEnabled = true
    private var remoteMicEnabled = true
    private var remoteCameraEnabled = true
    private var localSpeakerEnabled = true
    private val handler = Handler(Looper.getMainLooper())
    private var batteryRunnable: Runnable? = null

    var onMessage: ((SignalMessage) -> Unit)? = null
    var onRemoteVideoTrack: ((RtcSession, VideoTrack) -> Unit)? = null
    var onRemoteBattery: ((Int, Boolean) -> Unit)? = null
    var onState: ((String) -> Unit)? = null

    fun startServer() {
        server = SignalServer().apply {
            onConnection = { accepted ->
                accepted.send(hello("controlled"))
                startControlledRtc(accepted)
            }
            onMessage = { accepted, message ->
                route(accepted, message)
            }
            onDisconnect = { disconnected ->
                endControlledCall(disconnected)
            }
            onError = { error ->
                onState?.invoke("被控端监听失败：${error.localizedMessage ?: "请重新进入等待连接"}")
            }
            start()
        }
    }

    fun connect(host: String) {
        val endpoint = SignalEndpoint.parse(host)
        val overlayIp = localOverlayIp()
        if (overlayIp.isNullOrBlank() && !EasyTierManager(context).isExternalVpnActive()) {
            onState?.invoke("请先在首页启动 EasyTier VPN，获取本机虚拟 IP 后再连接")
            return
        } else if (overlayIp.isNullOrBlank()) {
            onState?.invoke("检测到已有若轻或其他 VPN，正在复用现有 VPN 连接被控端")
        }
        client = SignalClient(endpoint.host, endpoint.port).apply {
            onConnected = { connected ->
                connection = connected
                connected.send(hello("controller"))
                startRtc(asOfferer = true, overlayIp = endpoint.host, controllerCamera = true)
            }
            onMessage = ::route
            onRetry = { attempt ->
                onState?.invoke("正在等待被控端监听…第 ${attempt + 1} 次尝试")
            }
            onError = { error ->
                client = null
                connection = null
                onState?.invoke("连接被控端失败：${error.localizedMessage ?: "网络不可达"}。请确认若轻/EasyTier VPN 正在运行、双方在同一网络，且被控端已进入等待连接")
            }
            connect()
        }
    }

    fun toggleLocalMic(): Boolean {
        localMicEnabled = !localMicEnabled
        rtcSession?.mediaController?.setMicEnabled(localMicEnabled)
        controlledSessions.values.forEach { it.mediaController.setMicEnabled(localMicEnabled) }
        onState?.invoke(if (localMicEnabled) "本机麦克风已开启" else "本机麦克风已关闭")
        return localMicEnabled
    }

    fun toggleLocalCamera(): Boolean {
        localCameraEnabled = !localCameraEnabled
        rtcSession?.mediaController?.setCameraEnabled(localCameraEnabled)
        controlledSessions.values.forEach { it.mediaController.setCameraEnabled(localCameraEnabled) }
        onState?.invoke(if (localCameraEnabled) "本机摄像头已开启" else "本机摄像头已关闭")
        return localCameraEnabled
    }

    fun toggleLocalSpeaker(): Boolean {
        localSpeakerEnabled = !localSpeakerEnabled
        rtcSession?.setRemoteAudioEnabled(localSpeakerEnabled)
        controlledSessions.values.forEach { it.setRemoteAudioEnabled(localSpeakerEnabled) }
        onState?.invoke(if (localSpeakerEnabled) "本机喇叭已开启" else "本机喇叭已静音")
        return localSpeakerEnabled
    }

    fun toggleRemoteMic(): Boolean {
        remoteMicEnabled = !remoteMicEnabled
        send(SignalMessage.Command(SignalCommand.MicState, remoteMicEnabled))
        onState?.invoke(if (remoteMicEnabled) "对端麦克风已开启" else "对端麦克风已关闭")
        return remoteMicEnabled
    }

    fun toggleRemoteCamera(): Boolean {
        remoteCameraEnabled = !remoteCameraEnabled
        send(SignalMessage.Command(SignalCommand.CameraState, remoteCameraEnabled))
        onState?.invoke(if (remoteCameraEnabled) "对端摄像头已开启" else "对端摄像头已关闭")
        return remoteCameraEnabled
    }

    fun switchLocalCamera() {
        rtcSession?.mediaController?.switchCamera()
        controlledSessions.values.forEach { it.mediaController.switchCamera() }
        onState?.invoke("本机摄像头已切换")
    }

    fun endCurrentCall() {
        client?.close()
        connection?.close()
        rtcSession?.stop()
        controlledSessions.keys.toList().forEach { it.close() }
        controlledSessions.values.forEach { it.stop() }
        controlledSessions.clear()
        stopBatteryUpdates()
        client = null
        connection = null
        rtcSession = null
        onState?.invoke("通话已结束，等待下一次连接")
    }

    fun send(message: SignalMessage) {
        if (controlledSessions.isNotEmpty()) controlledSessions.keys.forEach { it.send(message) } else connection?.send(message) ?: client?.send(message)
    }

    fun route(message: SignalMessage) {
        onMessage?.invoke(message)
        when (message) {
            is SignalMessage.Offer, is SignalMessage.Answer, is SignalMessage.Candidate -> rtcSession?.handleSignal(message)
            is SignalMessage.Command -> handleCommand(message)
            is SignalMessage.Battery -> onRemoteBattery?.invoke(message.level, message.charging)
            is SignalMessage.Heartbeat -> send(SignalMessage.Ack(message.id))
            else -> Unit
        }
    }

    private fun route(accepted: SignalConnection, message: SignalMessage) {
        onMessage?.invoke(message)
        when (message) {
            is SignalMessage.Offer, is SignalMessage.Answer, is SignalMessage.Candidate -> controlledSessions[accepted]?.handleSignal(message)
            is SignalMessage.Command -> handleCommand(accepted, message)
            is SignalMessage.Heartbeat -> accepted.send(SignalMessage.Ack(message.id))
            else -> Unit
        }
    }

    fun stop() {
        server?.stop()
        client?.close()
        connection?.close()
        rtcSession?.stop()
        controlledSessions.keys.toList().forEach { it.close() }
        controlledSessions.values.forEach { it.stop() }
        controlledSessions.clear()
        releaseControlledSharedMedia()
        stopBatteryUpdates()
        server = null
        client = null
        connection = null
        rtcSession = null
    }

    private fun startRtc(asOfferer: Boolean, overlayIp: String?, controllerCamera: Boolean) {
        localCameraEnabled = !controllerCamera
        localMicEnabled = !controllerCamera
        localSpeakerEnabled = true
        remoteMicEnabled = true
        remoteCameraEnabled = true
        rtcSession?.stop()
        rtcSession = RtcSession(context, overlayIp, localOverlayIp()).apply {
            onSignal = ::send
            onRemoteVideoTrack = { track -> this@SessionController.onRemoteVideoTrack?.invoke(this, track) }
            onState = { state -> this@SessionController.onState?.invoke(state) }
            start(asOfferer = asOfferer, createVideoTrack = true, cameraEnabled = localCameraEnabled, createAudioTrack = true)
            mediaController.setMicEnabled(localMicEnabled)
        }
        if (controllerCamera) stopBatteryUpdates() else startBatteryUpdates()
    }

    private fun startControlledRtc(accepted: SignalConnection) {
        localCameraEnabled = true
        localMicEnabled = true
        localSpeakerEnabled = true
        remoteMicEnabled = true
        remoteCameraEnabled = true
        val sharedVideo = ensureControlledVideoTrack()
        val factory = controlledFactory
        val eglBase = controlledEglBase
        val session = RtcSession(context, null, localOverlayIp(), factory, eglBase, sharedVideo).apply {
            onSignal = { message -> accepted.send(message) }
            onRemoteVideoTrack = { track -> this@SessionController.onRemoteVideoTrack?.invoke(this, track) }
            onState = { state -> this@SessionController.onState?.invoke(state) }
            start(asOfferer = false, createVideoTrack = true, cameraEnabled = localCameraEnabled, createAudioTrack = true)
            setLocalMicEnabled(localMicEnabled)
            setLocalCameraEnabled(localCameraEnabled)
            setRemoteAudioEnabled(localSpeakerEnabled)
        }
        controlledSessions[accepted] = session
        startBatteryUpdates()
        onState?.invoke("控制端已连接：${controlledSessions.size} 个")
    }

    private fun ensureControlledVideoTrack(): VideoTrack? {
        controlledVideoTrack?.let { return it }
        val eglBase = EglBase.create()
        val factory = RtcSession.createFactory(context, eglBase.eglBaseContext)
        val cameraController = RtcMediaController(context, factory, eglBase.eglBaseContext).apply {
            startLocalMedia(createVideo = true, createAudio = false, cameraEnabled = true)
        }
        controlledEglBase = eglBase
        controlledFactory = factory
        controlledCameraController = cameraController
        controlledVideoTrack = cameraController.localVideoTrack
        return controlledVideoTrack
    }

    private fun endControlledCall(connection: SignalConnection) {
        val session = controlledSessions.remove(connection) ?: return
        session.stop()
        if (controlledSessions.isEmpty()) stopBatteryUpdates()
        if (controlledSessions.isEmpty()) releaseControlledSharedMedia()
        onState?.invoke("控制端已断开，剩余 ${controlledSessions.size} 个")
    }

    private fun releaseControlledSharedMedia() {
        controlledCameraController?.stop()
        runCatching { controlledFactory?.dispose() }
        runCatching { controlledEglBase?.release() }
        controlledCameraController = null
        controlledFactory = null
        controlledEglBase = null
        controlledVideoTrack = null
    }

    private fun startBatteryUpdates() {
        stopBatteryUpdates()
        batteryRunnable = object : Runnable {
            override fun run() {
                currentBattery()?.let { (level, charging) -> send(SignalMessage.Battery(level, charging)) }
                handler.postDelayed(this, BATTERY_INTERVAL_MS)
            }
        }
        batteryRunnable?.run()
    }

    private fun stopBatteryUpdates() {
        batteryRunnable?.let(handler::removeCallbacks)
        batteryRunnable = null
    }

    private fun currentBattery(): Pair<Int, Boolean>? {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val percent = (level * 100 / scale).coerceIn(0, 100)
        val chargingStatus = status.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = chargingStatus == BatteryManager.BATTERY_STATUS_CHARGING || chargingStatus == BatteryManager.BATTERY_STATUS_FULL
        return percent to charging
    }

    private fun localOverlayIp(): String? {
        val raw = EasyTierManager(context).getNetworkInfo()
        if (raw.isNullOrBlank()) return null
        return runCatching {
            EasyTierNetworkInfoAnalyzer.extractInstanceIpv4(JSONObject(raw), "")?.substringBefore('/')
        }.getOrNull()
    }

    private fun handleCommand(message: SignalMessage.Command) {
        when (message.command) {
            SignalCommand.WakeScreen -> Unit
            SignalCommand.MicState -> {
                localMicEnabled = message.enabled ?: !localMicEnabled
                rtcSession?.mediaController?.setMicEnabled(localMicEnabled)
                controlledSessions.values.forEach { it.mediaController.setMicEnabled(localMicEnabled) }
                onState?.invoke(if (localMicEnabled) "本机麦克风已开启" else "本机麦克风已关闭")
            }
            SignalCommand.CameraState -> {
                localCameraEnabled = message.enabled ?: !localCameraEnabled
                rtcSession?.mediaController?.setCameraEnabled(localCameraEnabled)
                controlledSessions.values.forEach { it.mediaController.setCameraEnabled(localCameraEnabled) }
                onState?.invoke(if (localCameraEnabled) "本机摄像头已开启" else "本机摄像头已关闭")
            }
            SignalCommand.SwitchCamera -> {
                rtcSession?.mediaController?.switchCamera()
                controlledSessions.values.forEach { it.mediaController.switchCamera() }
                onState?.invoke("摄像头已切换")
            }
            SignalCommand.Hangup -> stop()
        }
    }

    private fun handleCommand(connection: SignalConnection, message: SignalMessage.Command) {
        val session = controlledSessions[connection]
        when (message.command) {
            SignalCommand.WakeScreen -> Unit
            SignalCommand.MicState -> {
                session?.setLocalMicEnabled(message.enabled ?: true)
                onState?.invoke(if (message.enabled != false) "该控制端已开启被控端麦克风" else "该控制端已关闭被控端麦克风")
            }
            SignalCommand.CameraState -> {
                session?.setLocalCameraEnabled(message.enabled ?: true)
                onState?.invoke(if (message.enabled != false) "该控制端已开启被控端摄像头" else "该控制端已关闭被控端摄像头")
            }
            SignalCommand.SwitchCamera -> {
                controlledCameraController?.switchCamera()
                onState?.invoke("摄像头已切换")
            }
            SignalCommand.Hangup -> {
                connection.close()
                endControlledCall(connection)
            }
        }
    }

    private fun hello(role: String): SignalMessage.Hello = SignalMessage.Hello(
        deviceName = Build.MODEL ?: "Android",
        role = role
    )

    companion object { private const val BATTERY_INTERVAL_MS = 20_000L }
}

private data class SignalEndpoint(val host: String, val port: Int) {
    companion object {
        fun parse(raw: String): SignalEndpoint {
            val cleaned = raw.trim().removePrefix("http://").removePrefix("https://").substringBefore('/')
            val host = cleaned.substringBefore(':').trim()
            val port = cleaned.substringAfter(':', SignalClient.DEFAULT_PORT.toString()).toIntOrNull() ?: SignalClient.DEFAULT_PORT
            return SignalEndpoint(host, port)
        }
    }
}
