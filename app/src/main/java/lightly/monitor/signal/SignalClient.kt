package lightly.monitor.signal

import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

class SignalClient(private val host: String, private val port: Int = DEFAULT_PORT) {
    private var connection: SignalConnection? = null
    @Volatile private var closed = false
    var onMessage: ((SignalMessage) -> Unit)? = null
    var onConnected: ((SignalConnection) -> Unit)? = null
    var onError: ((Throwable) -> Unit)? = null
    var onRetry: ((Int) -> Unit)? = null
    fun connect() {
        closed = false
        thread(name = "SignalClient") {
            var attempt = 1
            var lastError: Throwable? = null
            val deadline = System.currentTimeMillis() + RETRY_WINDOW_MS
            while (!closed && System.currentTimeMillis() <= deadline) {
                try {
                    val connectPort = if (port > 0) port else DEFAULT_PORT
                    val socket = Socket().apply { connect(InetSocketAddress(host, connectPort), CONNECT_TIMEOUT_MS) }
                    val current = SignalConnection(socket)
                    connection = current
                    onConnected?.invoke(current)
                    current.listen(onMessage = { message -> onMessage?.invoke(message) })
                    return@thread
                } catch (error: IOException) {
                    lastError = error
                    connection = null
                    Log.e(TAG, "Signal connect failed", error)
                    if (!closed && System.currentTimeMillis() + RETRY_DELAY_MS <= deadline) {
                        onRetry?.invoke(attempt++)
                        Thread.sleep(RETRY_DELAY_MS)
                    }
                } catch (error: SecurityException) {
                    connection = null
                    Log.e(TAG, "Signal connect blocked", error)
                    onError?.invoke(error)
                    return@thread
                }
            }
            if (!closed) onError?.invoke(lastError ?: IOException("Signal connect timed out"))
        }
    }
    fun send(message: SignalMessage) { connection?.send(message) }
    fun close() { closed = true; connection?.close(); connection = null }

    companion object {
        private const val TAG = "SignalClient"
        const val DEFAULT_PORT = 19090
        private const val CONNECT_TIMEOUT_MS = 3000
        private const val RETRY_DELAY_MS = 2000L
        private const val RETRY_WINDOW_MS = 60_000L
    }
}
