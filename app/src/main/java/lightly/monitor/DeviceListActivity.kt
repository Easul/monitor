package lightly.monitor

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lightly.monitor.data.ControlledDeviceHistoryStore
import lightly.monitor.data.StoredControlledDevice
import lightly.monitor.signal.SignalCodec
import lightly.monitor.signal.SignalMessage
import lightly.monitor.signal.SignalServer
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

class DeviceListActivity : AppCompatActivity() {
    private lateinit var historyStore: ControlledDeviceHistoryStore
    private val peers = mutableListOf<Pair<String, String>>()
    private val controlledDevices = mutableListOf<ControlledDevice>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_list)
        historyStore = ControlledDeviceHistoryStore(this)
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.refreshPeersButton).setOnClickListener { lifecycleScope.launch { refreshPeers() } }
        findViewById<Button>(R.id.saveManualDeviceButton).setOnClickListener { lifecycleScope.launch { saveManualDevice() } }
        findViewById<Button>(R.id.connectManualButton).setOnClickListener { openSession(findViewById<EditText>(R.id.manualHostInput).text.toString().trim()) }
        findViewById<ListView>(R.id.peerList).setOnItemClickListener { _, _, position, _ -> openSession(peers[position].second) }
        lifecycleScope.launch { refreshPeers() }
    }
    private suspend fun refreshPeers() {
        val stored = historyStore.loadDevices().map { it.toControlledDevice() }
        if (stored.isNotEmpty()) {
            showControlledDevices(stored, "已显示 ${stored.size} 台已保存被控设备，正在探测在线状态…")
        }
        val historical = probeHistoricalDevices(stored)
        if (historical.isNotEmpty()) {
            showControlledDevices(mergeControlledDevices(stored, historical), "已通过历史 IP 找到 ${historical.size} 台被控设备")
            return
        }
        discoverViaFixedRange("正在扫描 10.126.126.100-150 的被控端…", stored)
    }

    private suspend fun probeHistoricalDevices(historical: List<ControlledDevice>): List<ControlledDevice> {
        if (historical.isEmpty()) return emptyList()
        findViewById<TextView>(R.id.deviceStatusText).text = "正在优先探测历史设备 IP…"
        return withContext(Dispatchers.IO) {
            historical.map { device ->
                async {
                    probeControlledEndpoint(device.ip, device.port, VPN_SWEEP_TIMEOUT_MS)?.let { probe ->
                        device.copy(name = probe.deviceName ?: device.name, port = probe.port)
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private suspend fun discoverViaFixedRange(message: String, stored: List<ControlledDevice>) {
        findViewById<TextView>(R.id.deviceStatusText).text = message
        val controlled = sweepControlledEndpoints(FIXED_SCAN_HOSTS)
        val merged = mergeControlledDevices(stored, controlled)
        val successMessage = if (controlled.isEmpty() && stored.isNotEmpty()) "已显示 ${stored.size} 台已保存被控设备；本次扫描未发现新设备" else null
        showControlledDevices(merged, successMessage)
    }

    private suspend fun sweepControlledEndpoints(hosts: List<String>): List<ControlledDevice> = withContext(Dispatchers.IO) {
        val found = linkedMapOf<String, ControlledProbe>()
        for (chunk in hosts.chunked(SWEEP_CONCURRENCY)) {
            chunk.map { host -> async { probeControlledEndpoint(host, SignalServer.DEFAULT_PORT, VPN_SWEEP_TIMEOUT_MS)?.let { host to it } } }
                .awaitAll()
                .filterNotNull()
                .forEach { (host, probe) -> found.putIfAbsent(host, probe) }
        }
        if (found.isNotEmpty()) return@withContext found.map { (host, probe) -> ControlledDevice(probe.deviceName ?: "VPN 扫描", host, probe.port) }
        val remainingPorts = SignalServer.PORT_RANGE.filter { it != SignalServer.DEFAULT_PORT }
        for (pairs in remainingPorts.flatMap { port -> hosts.map { host -> host to port } }.chunked(SWEEP_CONCURRENCY)) {
            pairs.map { (host, port) -> async { probeControlledEndpoint(host, port, VPN_SWEEP_TIMEOUT_MS)?.let { host to it } } }
                .awaitAll()
                .filterNotNull()
                .forEach { (host, probe) -> found.putIfAbsent(host, probe) }
            if (found.isNotEmpty()) return@withContext found.map { (host, probe) -> ControlledDevice(probe.deviceName ?: "VPN 扫描", host, probe.port) }
        }
        found.map { (host, probe) -> ControlledDevice(probe.deviceName ?: "VPN 扫描", host, probe.port) }
    }

    private fun showControlledDevices(controlled: List<ControlledDevice>) {
        showControlledDevices(controlled, null)
    }

    private fun showControlledDevices(controlled: List<ControlledDevice>, successMessage: String?) {
        controlledDevices.clear(); controlledDevices.addAll(controlled)
        peers.clear(); peers.addAll(controlledDevices.map { it.name to "${it.ip}:${it.port}" })
        findViewById<ListView>(R.id.peerList).adapter = ControlledDeviceAdapter(controlledDevices) { device -> deleteControlledDevice(device) }
        findViewById<TextView>(R.id.deviceStatusText).text = if (controlled.isEmpty()) {
            "未发现已就绪被控设备。请确认被控端已进入等待连接、双方 VPN 网络互通，或手动输入 10.126.x.x:19090。"
        } else {
            successMessage ?: "发现 ${controlled.size} 台已就绪被控设备；请先在被控端进入等待连接"
        }
        lifecycleScope.launch { rememberControlledDevices(controlled) }
    }

    private fun deleteControlledDevice(device: ControlledDevice) {
        lifecycleScope.launch {
            historyStore.deleteDevice(device.ip)
            val remaining = controlledDevices.filterNot { it.ip == device.ip }
            showControlledDevices(remaining, "已删除被控设备 ${device.ip}")
        }
    }

    private suspend fun rememberControlledDevices(controlled: List<ControlledDevice>) {
        historyStore.rememberDevices(controlled.map { StoredControlledDevice(it.name, it.ip, it.port, System.currentTimeMillis()) })
    }

    private suspend fun saveManualDevice() {
        val device = parseManualDevice(findViewById<EditText>(R.id.manualHostInput).text.toString())
        if (device == null) {
            Toast.makeText(this, "请输入有效的被控端 IP，例如 10.126.126.100 或 10.126.126.100:19090", Toast.LENGTH_SHORT).show()
            return
        }
        historyStore.rememberDevices(listOf(StoredControlledDevice(device.name, device.ip, device.port, System.currentTimeMillis())))
        val stored = historyStore.loadDevices().map { it.toControlledDevice() }
        showControlledDevices(mergeControlledDevices(stored, listOf(device)), "已保存被控设备 ${device.ip}:${device.port}")
    }

    private fun parseManualDevice(raw: String): ControlledDevice? {
        val endpoint = normalizeEndpoint(raw)
        if (endpoint.isBlank()) return null
        val host = normalizeHost(endpoint)
        if (host.isBlank()) return null
        val rawPort = endpoint.substringAfter(':', SignalServer.DEFAULT_PORT.toString())
        val port = rawPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return ControlledDevice("手动设备", host, port)
    }

    private fun mergeControlledDevices(stored: List<ControlledDevice>, discovered: List<ControlledDevice>): List<ControlledDevice> {
        val merged = linkedMapOf<String, ControlledDevice>()
        (stored + discovered).forEach { device -> merged[device.ip] = device }
        return merged.values.toList()
    }

    private suspend fun probeControlledEndpoint(ip: String): ControlledProbe? = withContext(Dispatchers.IO) {
        val host = normalizeHost(ip)
        probeControlledEndpoint(host, SignalServer.DEFAULT_PORT)?.let { return@withContext it }
        val remainingPorts = SignalServer.PORT_RANGE.filter { it != SignalServer.DEFAULT_PORT }
        for (ports in remainingPorts.chunked(PROBE_BATCH_SIZE)) {
            val match = coroutineScope {
                ports.map { port -> async { probeControlledEndpoint(host, port) } }
                    .awaitAll()
                    .filterNotNull()
                    .minByOrNull { it.port }
            }
            if (match != null) return@withContext match
        }
        null
    }

    private fun probeControlledEndpoint(host: String, port: Int): ControlledProbe? {
        return probeControlledEndpoint(host, port, CONTROL_SIGNAL_TIMEOUT_MS)
    }

    private fun probeControlledEndpoint(host: String, port: Int, timeoutMs: Int): ControlledProbe? {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.soTimeout = timeoutMs
                socket.getOutputStream().write(SignalCodec.encodeLine(SignalMessage.Probe()).toByteArray(Charsets.UTF_8))
                socket.getOutputStream().flush()
                val response = BufferedReader(InputStreamReader(socket.getInputStream())).readLine()
                val probe = SignalCodec.decode(response) as? SignalMessage.ProbeResponse
                probe?.takeIf { it.magic == SignalMessage.CONTROLLED_READY_MAGIC }?.let { ControlledProbe(port, it.deviceName?.takeIf(String::isNotBlank)) }
            }
        }.getOrNull()
    }
    private fun openSession(host: String) {
        val normalizedHost = normalizeEndpoint(host)
        if (normalizedHost.isBlank()) return
        startActivity(Intent(this, SessionActivity::class.java).putExtra(SessionActivity.EXTRA_HOST, normalizedHost))
    }

    private fun normalizeEndpoint(host: String): String = host.trim()
        .removePrefix("http://")
        .removePrefix("https://")
        .substringBefore('/')
        .trim()

    private fun normalizeHost(host: String): String = host.trim()
        .removePrefix("http://")
        .removePrefix("https://")
        .substringBefore('/')
        .substringBefore(':')
        .trim()

    companion object {
        private const val CONTROL_SIGNAL_TIMEOUT_MS = 1400
        private const val VPN_SWEEP_TIMEOUT_MS = 550
        private const val PROBE_BATCH_SIZE = 6
        private const val SWEEP_CONCURRENCY = 96
        private val FIXED_SCAN_HOSTS = (100..150).map { "10.126.126.$it" }
    }

    private data class ControlledDevice(val name: String, val ip: String, val port: Int)

    private data class ControlledProbe(val port: Int, val deviceName: String?)

    private inner class ControlledDeviceAdapter(devices: List<ControlledDevice>, private val onDelete: (ControlledDevice) -> Unit) : ArrayAdapter<ControlledDevice>(this, 0, devices) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_controlled_device, parent, false)
            val device = getItem(position)!!
            row.findViewById<TextView>(R.id.deviceText).text = "被控端  ${device.name}  ${device.ip}  端口 ${device.port}"
            row.findViewById<TextView>(R.id.deleteDeviceButton).setOnClickListener { onDelete(device) }
            return row
        }
    }

    private fun StoredControlledDevice.toControlledDevice(): ControlledDevice = ControlledDevice(name, ip, port)
}
