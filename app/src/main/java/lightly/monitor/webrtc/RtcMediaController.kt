package lightly.monitor.webrtc

import android.content.Context
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

class RtcMediaController(
    private val context: Context,
    private val factory: PeerConnectionFactory,
    private val eglContext: EglBase.Context
) {
    var localVideoTrack: VideoTrack? = null
        private set
    var localAudioTrack: AudioTrack? = null
        private set

    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    fun startLocalMedia(createVideo: Boolean, createAudio: Boolean, cameraEnabled: Boolean) {
        if (createAudio && localAudioTrack == null) {
            audioSource = factory.createAudioSource(audioConstraints())
            localAudioTrack = factory.createAudioTrack(AUDIO_TRACK_ID, audioSource).apply { setEnabled(true) }
        }
        if (createVideo && localVideoTrack == null) {
            val capturer = createCameraCapturer() ?: return
            val source = factory.createVideoSource(capturer.isScreencast)
            val helper = SurfaceTextureHelper.create("RtcCameraThread", eglContext)
            capturer.initialize(helper, context, source.capturerObserver)
            capturer.startCapture(640, 480, 15)
            videoCapturer = capturer
            videoSource = source
            surfaceTextureHelper = helper
            localVideoTrack = factory.createVideoTrack(VIDEO_TRACK_ID, source).apply { setEnabled(cameraEnabled) }
        }
    }

    fun switchCamera() {
        (videoCapturer as? CameraVideoCapturer)?.switchCamera(null)
    }

    fun setMicEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
    }

    fun setCameraEnabled(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
    }

    fun stop() {
        runCatching { videoCapturer?.stopCapture() }
        runCatching { videoCapturer?.dispose() }
        runCatching { surfaceTextureHelper?.dispose() }
        runCatching { videoSource?.dispose() }
        runCatching { audioSource?.dispose() }
        localVideoTrack = null
        localAudioTrack = null
        videoCapturer = null
        videoSource = null
        audioSource = null
        surfaceTextureHelper = null
    }

    private fun createCameraCapturer(): VideoCapturer? {
        val enumerators = buildList {
            if (Camera2Enumerator.isSupported(context)) add(Camera2Enumerator(context))
            add(Camera1Enumerator(false))
        }
        for (enumerator in enumerators) {
            createCameraCapturer(enumerator, frontFacing = true)?.let { return it }
            createCameraCapturer(enumerator, frontFacing = false)?.let { return it }
        }
        return null
    }

    private fun createCameraCapturer(enumerator: CameraEnumerator, frontFacing: Boolean): VideoCapturer? {
        return enumerator.deviceNames.firstOrNull { name ->
            if (frontFacing) enumerator.isFrontFacing(name) else enumerator.isBackFacing(name)
        }?.let { name -> enumerator.createCapturer(name, null) }
    }

    private fun audioConstraints(): MediaConstraints = MediaConstraints().apply {
        optional.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
        optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        optional.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
    }

    companion object {
        private const val AUDIO_TRACK_ID = "lightly_audio"
        private const val VIDEO_TRACK_ID = "lightly_camera"
    }
}
