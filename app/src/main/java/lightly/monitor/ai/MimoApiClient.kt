package lightly.monitor.ai

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import lightly.monitor.data.MimoApiConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class MimoApiClient(private val config: MimoApiConfig) {
    suspend fun uploadTempImage(jpegBytes: ByteArray): String = withContext(Dispatchers.IO) {
        val boundary = "----LightlyMonitor${UUID.randomUUID().toString().replace("-", "")}"
        val connection = openUrlConnection(URL(TEMP_UPLOAD_URL)).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("User-Agent", "Mozilla/5.0 LightlyMonitor/1.0")
            setRequestProperty("Referer", "https://tmper.app")
            setRequestProperty("Origin", "https://tmper.app")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        DataOutputStream(connection.outputStream).use { output ->
            output.writeBytes("--$boundary\r\n")
            output.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"frame.jpg\"\r\n")
            output.writeBytes("Content-Type: image/jpeg\r\n\r\n")
            output.write(jpegBytes)
            output.writeBytes("\r\n--$boundary--\r\n")
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val response = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        connection.disconnect()
        if (code !in 200..299) error("图片上传失败：HTTP $code $response")
        val url = JSONObject(response).optString("url")
        if (url.isBlank()) error("图片上传失败：未返回图片链接")
        url
    }

    suspend fun analyzeImageUrl(imageUrl: String, onChunk: ((String) -> Unit)? = null): String = chat(
        model = MimoApiConfig.MODEL_MULTIMODAL,
        messages = JSONArray().put(JSONObject().apply {
            put("role", "user")
            put("content", JSONArray()
                .put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().put("url", imageUrl))
                })
                .put(JSONObject().apply {
                    put("type", "text")
                    put("text", IMAGE_PROMPT)
                })
            )
        }),
        stream = true,
        onChunk = onChunk
    )
    suspend fun analyzeImageBase64(imageBase64: String): String = chat(
        model = MimoApiConfig.MODEL_MULTIMODAL,
        messages = JSONArray().put(JSONObject().apply {
            put("role", "user")
            put("content", JSONArray()
                .put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$imageBase64"))
                })
                .put(JSONObject().apply {
                    put("type", "text")
                    put("text", IMAGE_PROMPT)
                })
            )
        }),
        stream = true
    )

    suspend fun transcribeAudioUrl(audioUrl: String): String = chat(
        model = MimoApiConfig.MODEL_MULTIMODAL,
        messages = JSONArray().put(JSONObject().apply {
            put("role", "user")
            put("content", JSONArray()
                .put(JSONObject().apply {
                    put("type", "input_audio")
                    put("input_audio", JSONObject().put("data", audioUrl))
                })
                .put(JSONObject().apply {
                    put("type", "text")
                    put("text", TRANSCRIBE_PROMPT)
                })
            )
        }),
        stream = true
    )

    suspend fun askText(text: String, complex: Boolean = false): String = chat(
        model = if (complex) MimoApiConfig.MODEL_TEXT_PRO else MimoApiConfig.MODEL_TEXT_FAST,
        messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", TEXT_SYSTEM_PROMPT))
            .put(JSONObject().put("role", "user").put("content", text)),
        stream = true
    )

    suspend fun synthesizeSpeech(text: String): ByteArray = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("model", MimoApiConfig.MODEL_TTS)
            put("messages", JSONArray()
                .put(JSONObject().put("role", "user").put("content", TTS_STYLE_PROMPT))
                .put(JSONObject().put("role", "assistant").put("content", text))
            )
            put("audio", JSONObject()
                .put("format", "wav")
                .put("voice", MimoApiConfig.DEFAULT_TTS_VOICE)
            )
            put("stream", false)
        }
        val raw = post(body)
        val audio = JSONObject(raw).optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optJSONObject("audio")
            ?.optString("data")
            .orEmpty()
        if (audio.isBlank()) ByteArray(0) else Base64.decode(audio, Base64.DEFAULT)
    }

    private suspend fun chat(model: String, messages: JSONArray, stream: Boolean, onChunk: ((String) -> Unit)? = null): String = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("stream", stream)
        }
        if (!stream) return@withContext parseText(post(body))

        val first = runCatching { postStream(body, onChunk) }
        first.getOrNull()?.let { return@withContext it }
        val error = first.exceptionOrNull()
        if (!error.isRetryableGatewayError()) throw error ?: IllegalStateException("Mimo API 请求失败")

        delay(800)
        val retry = runCatching { postStream(body, onChunk) }
        retry.getOrNull()?.let { return@withContext it }
        val retryError = retry.exceptionOrNull()
        if (!retryError.isRetryableGatewayError()) throw (retryError ?: error ?: IllegalStateException("Mimo API 请求失败"))

        body.put("stream", false)
        parseText(post(body))
    }

    private fun post(body: JSONObject): String {
        val connection = openConnection()
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val response = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        connection.disconnect()
        if (code !in 200..299) error("Mimo API 请求失败：HTTP $code $response")
        return response
    }

    private fun postStream(body: JSONObject, onChunk: ((String) -> Unit)? = null): String {
        val connection = openConnection()
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        if (code !in 200..299) {
            val response = connection.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            connection.disconnect()
            error("Mimo API 请求失败：HTTP $code $response")
        }
        val builder = StringBuilder()
        BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).useLines { lines ->
            lines.forEach { line ->
                if (!line.startsWith("data:")) return@forEach
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") return@forEach
                runCatching { JSONObject(data) }.getOrNull()?.let { chunk ->
                    val choice = chunk.optJSONArray("choices")?.optJSONObject(0)
                    val delta = choice?.optJSONObject("delta")
                    val message = choice?.optJSONObject("message")
                    val text = buildString {
                        append(delta.textOrEmpty("content"))
                        append(message.textOrEmpty("content"))
                        append(delta.textOrEmpty("reasoning_content"))
                        append(message.textOrEmpty("reasoning_content"))
                        append(choice.textOrEmpty("text"))
                    }
                    if (text.isNotBlank()) {
                        builder.append(text)
                        onChunk?.invoke(builder.toString().trim())
                    }
                }
            }
        }
        connection.disconnect()
        return builder.toString().trim()
    }

    private fun parseText(raw: String): String {
        val message = JSONObject(raw).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
        return message.textOrEmpty("content").takeIf { it.isNotBlank() }
            ?: message.textOrEmpty("reasoning_content")
    }

    private fun JSONObject?.textOrEmpty(key: String): String {
        if (this == null || isNull(key)) return ""
        return optString(key).takeUnless { it == "null" }.orEmpty()
    }

    private fun Throwable?.isRetryableGatewayError(): Boolean {
        val message = this?.message.orEmpty()
        return message.contains("HTTP 503") ||
            message.contains("HTTP 504") ||
            message.contains("Gateway") ||
            message.contains("没有可用的内网节点")
    }

    private fun openConnection(): HttpURLConnection {
        if (!config.isReady()) error("请先在设置里填写 Mimo API Key")
        val url = URL(config.normalizedBaseUrl() + MimoApiConfig.CHAT_COMPLETIONS_PATH)
        return openUrlConnection(url).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${config.apiKey.trim()}")
        }
    }

    private fun openUrlConnection(url: URL): HttpURLConnection {
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
    }

    companion object {
        const val IMAGE_PROMPT = "请用中文简洁描述当前画面，重点关注人物、危险物品、异常情况和需要看护者注意的变化。不要编造看不见的信息。"
        const val TRANSCRIBE_PROMPT = "请识别这段中文语音，只输出转写文字，不要解释，不要总结。如果听不清，请输出“未听清”。"
        const val TEXT_SYSTEM_PROMPT = "你是“若轻看护”的中文看护助手。回答要温和、简洁、可执行。不要声称自己真的看到了未提供的内容；涉及安全和健康风险时提醒用户人工确认。"
        const val TTS_STYLE_PROMPT = "用温柔、清晰、稳定的中文女声播报，语速中等，适合看护场景，不要夸张表演。"
        private const val TEMP_UPLOAD_URL = "https://tmper.app/upload/"
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 180_000
    }
}
