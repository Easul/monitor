package lightly.monitor.signal

import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.random.Random

class SignalServer(private val port: Int = DEFAULT_PORT) {
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    @Volatile var boundPort: Int? = null
        private set
    var onMessage: ((SignalConnection, SignalMessage) -> Unit)? = null
    var onConnection: ((SignalConnection) -> Unit)? = null
    var onDisconnect: ((SignalConnection) -> Unit)? = null
    var onError: ((Throwable) -> Unit)? = null
    fun start() {
        if (running) return
        running = true
        thread(name = "SignalServer") {
            try {
                serverSocket = bindServerSocket()
                boundPort = serverSocket?.localPort
                while (running) runCatching { accept(serverSocket!!.accept()) }
            } catch (error: SocketException) {
                if (running) Log.e(TAG, "Signal server socket failed", error)
                if (running) onError?.invoke(error)
            } catch (error: IOException) {
                if (running) Log.e(TAG, "Signal server failed", error)
                if (running) onError?.invoke(error)
            }
        }
    }

    private fun bindServerSocket(): ServerSocket {
        val ports = if (port > 0 && port != DEFAULT_PORT) {
            listOf(port)
        } else {
            listOf(DEFAULT_PORT) + PORT_RANGE.filter { it != DEFAULT_PORT }.shuffled(Random(System.currentTimeMillis()))
        }
        var lastError: IOException? = null
        for (candidate in ports) {
            try {
                return ServerSocket(candidate)
            } catch (error: IOException) {
                lastError = error
            }
        }
        throw lastError ?: IOException("No available signal port")
    }
    private fun accept(socket: Socket) {
        val connection = SignalConnection(socket)
        var connected = false
        connection.listen(
            onMessage = { message ->
                if (message is SignalMessage.Probe) {
                    connection.sendBlocking(SignalMessage.ProbeResponse(deviceName = Build.MODEL ?: "Android"))
                    connection.close()
                    return@listen
                }
                if (!connected) {
                    connected = true
                    onConnection?.invoke(connection)
                }
                onMessage?.invoke(connection, message)
            },
            onClosed = { onDisconnect?.invoke(connection) }
        )
    }
    fun stop() { running = false; runCatching { serverSocket?.close() }; serverSocket = null; boundPort = null }
    companion object { const val DEFAULT_PORT = 19090; val PORT_RANGE: IntRange = 19090..19120; private const val TAG = "SignalServer" }
}

class SignalConnection(private val socket: Socket) {
    private val sendExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "SignalSend") }
    @Volatile private var closed = false

    init {
        runCatching {
            socket.keepAlive = true
            socket.tcpNoDelay = true
        }
        startHeartbeat()
    }

    fun send(message: SignalMessage) {
        if (closed) return
        runCatching { sendExecutor.execute {
            if (closed) return@execute
            runCatching {
                val encoded = SignalCodec.encodeLine(message).toByteArray(Charsets.UTF_8)
                synchronized(socket) {
                    socket.getOutputStream().write(encoded)
                    socket.getOutputStream().flush()
                }
            }.onFailure { error ->
                if (!closed) Log.e(TAG, "Signal send failed", error)
                close()
            }
        } }.onFailure { error ->
            if (!closed) Log.e(TAG, "Signal send queue failed", error)
            close()
        }
    }

    fun sendBlocking(message: SignalMessage) {
        if (closed) return
        runCatching {
            val encoded = SignalCodec.encodeLine(message).toByteArray(Charsets.UTF_8)
            synchronized(socket) {
                socket.getOutputStream().write(encoded)
                socket.getOutputStream().flush()
            }
        }.onFailure { error ->
            if (!closed) Log.e(TAG, "Signal send failed", error)
            close()
        }
    }

    private fun startHeartbeat() {
        thread(name = "SignalHeartbeat", isDaemon = true) {
            while (!closed) {
                runCatching { Thread.sleep(HEARTBEAT_INTERVAL_MS) }.onFailure { return@thread }
                if (!closed) send(SignalMessage.Heartbeat())
            }
        }
    }

    fun listen(onMessage: (SignalMessage) -> Unit, onClosed: () -> Unit = {}) {
        thread(name = "SignalConnection") {
            try {
                BufferedReader(InputStreamReader(socket.getInputStream())).useLines { lines ->
                    lines.mapNotNull(SignalCodec::decode).forEach(onMessage)
                }
            } catch (error: SocketException) {
                if (!closed) Log.e(TAG, "Signal socket closed unexpectedly", error)
            } catch (error: IOException) {
                if (!closed) Log.e(TAG, "Signal listen failed", error)
            } finally {
                close()
                onClosed()
            }
        }
    }

    fun close() {
        closed = true
        runCatching { socket.close() }
        sendExecutor.shutdownNow()
    }

    companion object { private const val TAG = "SignalConnection"; private const val HEARTBEAT_INTERVAL_MS = 15_000L }
}
