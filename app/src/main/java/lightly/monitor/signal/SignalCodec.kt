package lightly.monitor.signal

import org.json.JSONObject

object SignalCodec {
    fun encode(message: SignalMessage): String = toJson(message).toString()
    fun encodeLine(message: SignalMessage): String = encode(message) + "\n"
    fun decode(raw: String): SignalMessage? = runCatching {
        val json = JSONObject(raw.trim())
        when (json.optString("type")) {
            "hello" -> SignalMessage.Hello(json.optString("deviceName"), json.optString("deviceIp").takeIf { it.isNotEmpty() }, json.optString("role", "either"), json.optLong("id"), json.optLong("ts"))
            "heartbeat" -> SignalMessage.Heartbeat(json.optLong("id"), json.optLong("ts"))
            "ack" -> SignalMessage.Ack(json.optLong("ackId"), json.optBoolean("success", true), json.optString("error").takeIf { it.isNotEmpty() }, json.optLong("id"), json.optLong("ts"))
            "offer" -> SignalMessage.Offer(json.optString("sdp"), json.optLong("id"), json.optLong("ts"))
            "answer" -> SignalMessage.Answer(json.optString("sdp"), json.optLong("id"), json.optLong("ts"))
            "candidate" -> SignalMessage.Candidate(json.optString("candidate"), json.optString("sdpMid").takeIf { it.isNotEmpty() }, json.optInt("sdpMLineIndex"), json.optLong("id"), json.optLong("ts"))
            "command" -> SignalCommand.fromWireName(json.optString("command"))?.let { SignalMessage.Command(it, if (json.has("enabled")) json.optBoolean("enabled") else null, json.optLong("id"), json.optLong("ts")) }
            "battery" -> SignalMessage.Battery(json.optInt("level"), json.optBoolean("charging"), json.optLong("id"), json.optLong("ts"))
            "probe" -> SignalMessage.Probe(json.optLong("id"), json.optLong("ts"))
            "probe_response" -> SignalMessage.ProbeResponse(json.optString("magic"), json.optString("deviceName").takeIf { it.isNotEmpty() }, json.optLong("id"), json.optLong("ts"))
            else -> null
        }
    }.getOrNull()
    fun decodeMultiple(data: String): List<SignalMessage> = data.lineSequence().mapNotNull { line -> line.takeIf { it.isNotBlank() }?.let(::decode) }.toList()
    private fun toJson(message: SignalMessage): JSONObject = JSONObject().apply { put("id", message.id); put("ts", message.timestamp); when (message) {
        is SignalMessage.Hello -> { put("type", "hello"); put("deviceName", message.deviceName); put("deviceIp", message.deviceIp); put("role", message.role) }
        is SignalMessage.Heartbeat -> put("type", "heartbeat")
        is SignalMessage.Ack -> { put("type", "ack"); put("ackId", message.ackId); put("success", message.success); put("error", message.error) }
        is SignalMessage.Offer -> { put("type", "offer"); put("sdp", message.sdp) }
        is SignalMessage.Answer -> { put("type", "answer"); put("sdp", message.sdp) }
        is SignalMessage.Candidate -> { put("type", "candidate"); put("candidate", message.candidate); put("sdpMid", message.sdpMid); put("sdpMLineIndex", message.sdpMLineIndex) }
        is SignalMessage.Command -> { put("type", "command"); put("command", message.command.wireName); if (message.enabled != null) put("enabled", message.enabled) }
        is SignalMessage.Battery -> { put("type", "battery"); put("level", message.level); put("charging", message.charging) }
        is SignalMessage.Probe -> put("type", "probe")
        is SignalMessage.ProbeResponse -> { put("type", "probe_response"); put("magic", message.magic); put("deviceName", message.deviceName) }
    } }
}
