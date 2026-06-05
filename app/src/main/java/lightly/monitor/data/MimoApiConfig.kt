package lightly.monitor.data

data class MimoApiConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    val apiKey: String = ""
) {
    fun normalizedBaseUrl(): String = baseUrl.trim().trimEnd('/').ifBlank { DEFAULT_BASE_URL }
    fun isReady(): Boolean = apiKey.isNotBlank()

    companion object {
        const val DEFAULT_BASE_URL = "https://example.com"
        const val CHAT_COMPLETIONS_PATH = "/v1/chat/completions"
        const val MODEL_MULTIMODAL = "mimo-v2.5"
        const val MODEL_TEXT_FAST = "mimo-v2-flash"
        const val MODEL_TEXT_PRO = "mimo-v2.5-pro"
        const val MODEL_TTS = "mimo-v2.5-tts"
        const val DEFAULT_TTS_VOICE = "冰糖"
    }
}
