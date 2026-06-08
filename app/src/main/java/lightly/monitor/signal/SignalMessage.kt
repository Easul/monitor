package lightly.monitor.signal

sealed class SignalMessage(open val id: Long = now(), open val timestamp: Long = now()) {
    data class Hello(val deviceName: String, val deviceIp: String? = null, val role: String = "either", override val id: Long = now(), override val timestamp: Long = now()) : SignalMessage(id, timestamp)
    data class Heartbeat(override val id: Long = now(), override val timestamp: Long = now()) : SignalMessage(id, timestamp)
    data class Ack(val ackId: Long, val success: Boolean = true, val error: String? = null, override val id: Long = now(), override val timestamp: Long = now()) : SignalMessage(id, timestamp)
    data class Offer(val sdp: String, override val id: Long = now(), override val timestamp: Long = now()) : SignalMessage(id, timestamp)
    data class Answer(val sdp: String, override val id: Long = now(), override val timestamp: Long = now()) : SignalMessage(id, timestamp)
    data class Candidate(val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int, override val id: Long = now(), override val timestamp: Long = now()) : SignalMessage(id, timestamp)
    data class Command(val command: SignalCommand, val enabled: Boolean? = null, override val id: Long = now(), override val timestamp: Long = now()) : SignalMessage(id, timestamp)
    data class Battery(val level: Int, val charging: Boolean, override val id: Long = now(), override val timestamp: Long = now()) : SignalMessage(id, timestamp)
    data class Probe(override val id: Long = now(), override val timestamp: Long = now()) : SignalMessage(id, timestamp)
    data class ProbeResponse(val magic: String = CONTROLLED_READY_MAGIC, val deviceName: String? = null, override val id: Long = now(), override val timestamp: Long = now()) : SignalMessage(id, timestamp)
    companion object {
        const val CONTROLLED_READY_MAGIC = "LIGHTLY_MONITOR_CONTROLLED_READY"
        fun now(): Long = System.currentTimeMillis()
    }
}

enum class SignalCommand(val wireName: String) { WakeScreen("wake_screen"), MicState("mic_state"), CameraState("camera_state"), SwitchCamera("switch_camera"), Hangup("hangup"); companion object { fun fromWireName(value: String): SignalCommand? = values().firstOrNull { it.wireName == value } } }
