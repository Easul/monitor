package lightly.monitor.webrtc

import android.content.Context
import android.util.Log
import lightly.monitor.signal.SignalMessage
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack

class RtcSession(
    private val context: Context,
    private val preferredOverlayIp: String? = null,
    private val localOverlayIp: String? = null,
    sharedFactory: PeerConnectionFactory? = null,
    sharedEglBase: EglBase? = null,
    private val sharedVideoTrack: VideoTrack? = null
) {
    private val ownsRtcResources = sharedFactory == null || sharedEglBase == null
    private val eglBase = sharedEglBase ?: EglBase.create()
    private val factory = sharedFactory ?: createFactory(context, eglBase.eglBaseContext)
    private val candidateFilter = RtcCandidateFilter(WebRtcNetworkPreference(preferredOverlayIp, OVERLAY_PREFIX))
    private var peerConnection: PeerConnection? = null
    private var localTracksAttached = false
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()
    private var hasRemoteDescription = false
    private val remoteAudioTracks = mutableListOf<AudioTrack>()
    private var remoteAudioEnabled = true
    private var localVideoSender: RtpSender? = null
    private var localVideoEnabled = true
    val mediaController = RtcMediaController(context, factory, eglBase.eglBaseContext)
    var onSignal: ((SignalMessage) -> Unit)? = null
    var onRemoteVideoTrack: ((VideoTrack) -> Unit)? = null
    var onState: ((String) -> Unit)? = null

    fun eglContext(): EglBase.Context = eglBase.eglBaseContext

    fun start(asOfferer: Boolean, createVideoTrack: Boolean = true, cameraEnabled: Boolean = true, createAudioTrack: Boolean = true) {
        if (peerConnection != null) return
        localVideoEnabled = cameraEnabled
        mediaController.startLocalMedia(createVideoTrack && sharedVideoTrack == null, createAudioTrack, cameraEnabled)
        val connection = factory.createPeerConnection(rtcConfig(), observer()) ?: error("PeerConnection unavailable")
        peerConnection = connection
        logRtc("RTC local overlay=${localOverlayIp ?: "none"} remote overlay=${preferredOverlayIp ?: "none"}")
        if (asOfferer) {
            attachLocalTracks()
            createOffer()
        }
    }

    fun handleSignal(message: SignalMessage) {
        when (message) {
            is SignalMessage.Offer -> setRemoteDescription(SessionDescription.Type.OFFER, message.sdp) {
                attachLocalTracks()
                createAnswer()
            }
            is SignalMessage.Answer -> setRemoteDescription(SessionDescription.Type.ANSWER, message.sdp) {}
            is SignalMessage.Candidate -> addRemoteCandidate(message)
            else -> Unit
        }
    }

    fun stop() {
        runCatching { peerConnection?.close() }
        runCatching { peerConnection?.dispose() }
        mediaController.stop()
        if (ownsRtcResources) {
            runCatching { factory.dispose() }
            runCatching { eglBase.release() }
        }
        pendingRemoteCandidates.clear()
        remoteAudioTracks.clear()
        hasRemoteDescription = false
        localTracksAttached = false
        localVideoSender = null
        peerConnection = null
    }

    private fun createOffer() {
        peerConnection?.createOffer(object : DescriptionObserver() {
            override fun onCreateSuccess(description: SessionDescription) {
                logRtc("Created offer ${WebRtcSdpSummary().summarize(description.description)}")
                setLocalDescription(description) { rewritten -> onSignal?.invoke(SignalMessage.Offer(rewritten.description)) }
            }
        }, mediaConstraints())
    }

    private fun createAnswer() {
        peerConnection?.createAnswer(object : DescriptionObserver() {
            override fun onCreateSuccess(description: SessionDescription) {
                logRtc("Created answer ${WebRtcSdpSummary().summarize(description.description)}")
                setLocalDescription(description) { rewritten -> onSignal?.invoke(SignalMessage.Answer(rewritten.description)) }
            }
        }, mediaConstraints())
    }

    private fun setLocalDescription(description: SessionDescription, afterSet: (SessionDescription) -> Unit) {
        val rewritten = SessionDescription(
            description.type,
            description.description.lines().joinToString("\r\n") { line ->
                if (line.startsWith("a=candidate:")) "a=${candidateFilter.rewriteHostCandidateIp(line.removePrefix("a="), preferredLocalOverlayIp())}" else line
            }
        )
        logRtc("Set local ${description.type} ${WebRtcSdpSummary().summarize(rewritten.description)}")
        peerConnection?.setLocalDescription(object : DescriptionObserver() {
            override fun onSetSuccess() = afterSet(rewritten)
        }, rewritten)
    }

    private fun setRemoteDescription(type: SessionDescription.Type, sdp: String, afterSet: () -> Unit) {
        logRtc("Set remote $type ${WebRtcSdpSummary().summarize(sdp)}")
        peerConnection?.setRemoteDescription(object : DescriptionObserver() {
            override fun onSetSuccess() {
                hasRemoteDescription = true
                flushRemoteCandidates()
                afterSet()
            }
        }, SessionDescription(type, sdp))
    }

    private fun addRemoteCandidate(message: SignalMessage.Candidate) {
        if (!candidateFilter.shouldAcceptRemoteCandidate(message.candidate, ::logRtc)) return
        val candidate = IceCandidate(message.sdpMid, message.sdpMLineIndex, message.candidate)
        if (hasRemoteDescription) peerConnection?.addIceCandidate(candidate) else pendingRemoteCandidates += candidate
    }

    private fun flushRemoteCandidates() {
        val connection = peerConnection ?: return
        pendingRemoteCandidates.forEach(connection::addIceCandidate)
        pendingRemoteCandidates.clear()
    }

    private fun observer(): PeerConnection.Observer = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            val rewritten = candidateFilter.rewriteHostCandidateIp(candidate.sdp, preferredLocalOverlayIp(), ::logRtc)
            if (candidateFilter.shouldSendLocalCandidate(rewritten, ::logRtc)) {
                onSignal?.invoke(SignalMessage.Candidate(rewritten, candidate.sdpMid, candidate.sdpMLineIndex))
            }
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            handleRemoteTrack(transceiver.receiver.track())
        }

        override fun onAddStream(stream: MediaStream) {
            stream.audioTracks.forEach { track -> enableRemoteAudio(track) }
            stream.videoTracks.firstOrNull()?.let { track -> onRemoteVideoTrack?.invoke(track) }
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) { logRtc("ICE $state") }
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) { logRtc("Peer $newState") }
        override fun onSignalingChange(state: PeerConnection.SignalingState) { logRtc("Signaling $state") }
        override fun onIceConnectionReceivingChange(receiving: Boolean) { logRtc("ICE receiving=$receiving") }
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) { logRtc("ICE gathering $state") }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
            handleRemoteTrack(receiver.track())
        }
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
    }

    private fun attachLocalTracks() {
        val connection = peerConnection ?: return
        if (localTracksAttached) return
        mediaController.localAudioTrack?.let { track -> addLocalTrack(connection, track) }
        localVideoTrack()?.let { track -> localVideoSender = addLocalTrack(connection, track) }
        localTracksAttached = true
        logRtc("Local tracks attached audio=${mediaController.localAudioTrack != null} video=${localVideoTrack() != null} afterRemote=$hasRemoteDescription")
    }

    private fun addLocalTrack(connection: PeerConnection, track: org.webrtc.MediaStreamTrack): RtpSender? {
        return if (hasRemoteDescription) {
            connection.addTrack(track, listOf(STREAM_ID))
        } else {
            addSendReceiveTransceiver(connection, track)
        }
    }

    private fun addSendReceiveTransceiver(connection: PeerConnection, track: org.webrtc.MediaStreamTrack): RtpSender? {
        val init = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
        return connection.addTransceiver(track, init)?.sender
    }

    private fun localVideoTrack(): VideoTrack? = sharedVideoTrack ?: mediaController.localVideoTrack

    private fun handleRemoteTrack(track: org.webrtc.MediaStreamTrack?) {
        when (track) {
            is AudioTrack -> enableRemoteAudio(track)
            is VideoTrack -> {
                logRtc("Remote video connected")
                onRemoteVideoTrack?.invoke(track)
            }
        }
    }

    fun setRemoteAudioEnabled(enabled: Boolean) {
        remoteAudioEnabled = enabled
        remoteAudioTracks.forEach { it.setEnabled(enabled) }
    }

    fun setLocalMicEnabled(enabled: Boolean) {
        mediaController.setMicEnabled(enabled)
    }

    fun setLocalCameraEnabled(enabled: Boolean) {
        localVideoEnabled = enabled
        if (sharedVideoTrack != null) {
            localVideoSender?.setTrack(if (enabled) sharedVideoTrack else null, false)
        } else {
            mediaController.setCameraEnabled(enabled)
        }
    }

    private fun enableRemoteAudio(track: AudioTrack) {
        if (!remoteAudioTracks.contains(track)) remoteAudioTracks += track
        track.setEnabled(remoteAudioEnabled)
        onState?.invoke("远端音频已连接")
    }

    private fun rtcConfig(): PeerConnection.RTCConfiguration {
        return PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
    }

    private fun mediaConstraints(): MediaConstraints = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
    }

    private fun preferredLocalOverlayIp(): String? = localOverlayIp

    private fun logRtc(message: String) {
        Log.d(TAG, message)
        onState?.invoke(message)
    }

    private open class DescriptionObserver : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) { Log.e(TAG, "SDP create failed: $error") }
        override fun onSetFailure(error: String) { Log.e(TAG, "SDP set failed: $error") }
    }

    companion object {
        private const val TAG = "RtcSession"
        private const val STREAM_ID = "lightly_stream"
        private const val OVERLAY_PREFIX = "10.126."

        fun createFactory(context: Context, eglContext: EglBase.Context): PeerConnectionFactory {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions()
            )
            val encoderFactory = DefaultVideoEncoderFactory(eglContext, true, true)
            val decoderFactory = DefaultVideoDecoderFactory(eglContext)
            return PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()
        }
    }
}
