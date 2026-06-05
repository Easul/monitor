package lightly.monitor.webrtc

data class WebRtcNetworkPreference(val preferredHost: String? = null, val preferredOverlayPrefix: String? = null) { val preferOverlayHostCandidates: Boolean get() = preferredHost != null && preferredOverlayPrefix != null && preferredHost.startsWith(preferredOverlayPrefix) }
class RtcCandidateFilter(private val preference: WebRtcNetworkPreference = WebRtcNetworkPreference()) {
    fun shouldSendLocalCandidate(candidate: String, log: ((String) -> Unit)? = null): Boolean { logOverlayDecision(candidate, log, "webrtc-overlay-candidate-available", "webrtc-overlay-candidate-fallback: sending non-overlay"); return true }
    fun shouldAcceptRemoteCandidate(candidate: String, log: ((String) -> Unit)? = null): Boolean { logOverlayDecision(candidate, log, "webrtc-overlay-remote-candidate", "webrtc-overlay-remote-fallback: accepting non-overlay"); return true }
    fun rewriteHostCandidateIp(candidate: String, replacementIp: String?, log: ((String) -> Unit)? = null): String { if (replacementIp.isNullOrEmpty() || kind(candidate) != "host") return candidate; val parts = candidate.split(' ').toMutableList(); if (parts.size < 5 || parts[4] == replacementIp) return candidate; parts[4] = replacementIp; return parts.joinToString(" ").also { log?.invoke("webrtc-overlay-candidate-rewritten: ${summary(candidate)} -> ${summary(it)}") } }
    fun isPreferredOverlayCandidate(candidate: String): Boolean = kind(candidate) == "host" && extractIp(candidate)?.let { ip -> preference.preferredOverlayPrefix?.let(ip::startsWith) } == true
    fun summary(candidate: String): String = "${kind(candidate)}/${protocol(candidate)}@${extractIp(candidate) ?: "unknown"}:${extractPort(candidate) ?: "unknown"}"
    fun kind(candidate: String): String = candidate.split(' ').let { parts -> parts.indexOf("typ").takeIf { it >= 0 && it + 1 < parts.size }?.let { parts[it + 1].lowercase() } ?: "unknown" }
    fun extractIp(candidate: String): String? = candidate.split(' ').getOrNull(4)
    fun extractPort(candidate: String): String? = candidate.split(' ').getOrNull(5)
    fun protocol(candidate: String): String = candidate.split(' ').getOrNull(2)?.lowercase() ?: "unknown"
    private fun logOverlayDecision(candidate: String, log: ((String) -> Unit)?, preferred: String, fallback: String) { if (!preference.preferOverlayHostCandidates || log == null) return; if (isPreferredOverlayCandidate(candidate)) log("$preferred: ${summary(candidate)}") else if (kind(candidate) == "host") log("$fallback ${summary(candidate)}") }
}
class WebRtcSdpSummary { fun summarize(sdp: String?): String = if (sdp.isNullOrEmpty()) "empty" else "chars=${sdp.length} audioM=${"\nm=audio ".toRegex().findAll(sdp).count()} candidates=${"\na=candidate:".toRegex().findAll(sdp).count()} fingerprint=${sdp.contains("\na=fingerprint:")}" }
