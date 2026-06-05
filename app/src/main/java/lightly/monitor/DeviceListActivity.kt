package lightly.monitor

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lightly.monitor.data.ControlledDeviceHistoryStore
import lightly.monitor.data.EasyTierProfileStore
import lightly.monitor.data.StoredControlledDevice
import lightly.monitor.easytier.EasyTierManager
import lightly.monitor.easytier.EasyTierNetworkInfoAnalyzer
import lightly.monitor.easytier.PeerSummary
import lightly.monitor.signal.SignalCodec
import lightly.monitor.signal.SignalMessage
import lightly.monitor.signal.SignalServer
import java.io.BufferedReader
import java.io.InputStreamReader
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket

class DeviceListActivity : AppCompatActivity() {
    private lateinit var easyTierManager: EasyTierManager
    private lateinit var store: EasyTierProfileStore
    private lateinit var historyStore: ControlledDeviceHistoryStore
    private val peers = mutableListOf<Pair<String, String>>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_list)
        easyTierManager = EasyTierManager(this)
        store = EasyTierProfileStore(this)
        historyStore = ControlledDeviceHistoryStore(this)
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.refreshPeersButton).setOnClickListener { lifecycleScope.launch { refreshPeers() } }
        findViewById<Button>(R.id.connectManualButton).setOnClickListener { openSession(findViewById<EditText>(R.id.manualHostInput).text.toString().trim()) }
        findViewById<ListView>(R.id.peerList).setOnItemClickListener { _, _, position, _ -> openSession(peers[position].second) }
        lifecycleScope.launch { refreshPeers() }
    }
    private suspend fun refreshPeers() {
        val historical = probeHistoricalDevices()
        if (historical.isNotEmpty()) {
            showControlledDevices(historical, "已通过历史 IP 找到 ${historical.size} 台被控设备")
            return
        }
        val profiles = store.loadProfiles(); val profile = profiles.firstOrNull { it.id == store.getSelectedProfileId() } ?: profiles.first()
        val sharedInfo = if (easyTierManager.isExternalVpnActive()) easyTierManager.getLightlyNetworkInfo()?.takeIf { it.isRunning } else null
        val localRaw = if (sharedInfo == null) easyTierManager.getNetworkInfo() else null
        val raw = sharedInfo?.rawNetworkInfoJson ?: localRaw?.takeIf { it.isNotBlank() }
        if (raw.isNullOrBlank()) {
            discoverViaActiveVpn("暂未获取到 Monitor 的 EasyTier 网络信息，正在复用当前 VPN 扫描被控端…")
            return
        }
        if (sharedInfo != null) findViewById<TextView>(R.id.deviceStatusText).text = "已读取若轻共享的 EasyTier 网络信息，正在探测被控端…"
        val instanceName = sharedInfo?.instanceName?.takeIf { it.isNotBlank() } ?: profile.config.instanceName
        val summaries = EasyTierNetworkInfoAnalyzer.buildPeerSummaries(JSONObject(raw), instanceName).filter { it.remoteReachable }
        val controlled = discoverFromEasyTierRoutes(summaries)
        if (controlled.isEmpty() && easyTierManager.isExternalVpnActive()) {
            discoverViaActiveVpn("EasyTier routes 中没有发现被控端，正在复用当前 VPN 扫描 10.x 子网…")
            return
        }
        showControlledDevices(controlled)
    }

    private suspend fun probeHistoricalDevices(): List<ControlledDevice> {
        val historical = historyStore.loadDevices()
        if (historical.isEmpty()) return emptyList()
        findViewById<TextView>(R.id.deviceStatusText).text = "正在优先探测历史设备 IP…"
        return withContext(Dispatchers.IO) {
            historical.map { device ->
                async {
                    device.takeIf { probeControlledEndpoint(it.ip, it.port, VPN_SWEEP_TIMEOUT_MS) }
                        ?.let { ControlledDevice(it.name, it.ip, it.port) }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private suspend fun discoverFromEasyTierRoutes(summaries: List<PeerSummary>): List<ControlledDevice> = withContext(Dispatchers.IO) {
        summaries.map { summary ->
            async { probeControlledEndpoint(summary.ip)?.let { port -> ControlledDevice(summary.name, normalizeHost(summary.ip), port) } }
        }.awaitAll().filterNotNull()
    }

    private suspend fun discoverViaActiveVpn(message: String) {
        val hosts = easyTierManager.activeVpnIpv4Hosts()
        if (hosts.isEmpty()) {
            findViewById<TextView>(R.id.deviceStatusText).text = "未检测到可扫描的 VPN IPv4 子网。请确认若轻/EasyTier VPN 已连接，或手动输入被控端 10.x 地址。"
            return
        }
        findViewById<TextView>(R.id.deviceStatusText).text = message
        val controlled = sweepControlledEndpoints(hosts)
        showControlledDevices(controlled)
    }

    private suspend fun sweepControlledEndpoints(hosts: List<String>): List<ControlledDevice> = withContext(Dispatchers.IO) {
        val found = linkedMapOf<String, Int>()
        for (chunk in hosts.chunked(SWEEP_CONCURRENCY)) {
            chunk.map { host -> async { host.takeIf { probeControlledEndpoint(it, SignalServer.DEFAULT_PORT, VPN_SWEEP_TIMEOUT_MS) } } }
                .awaitAll()
                .filterNotNull()
                .forEach { found.putIfAbsent(it, SignalServer.DEFAULT_PORT) }
        }
        if (found.isNotEmpty()) return@withContext found.map { (host, port) -> ControlledDevice("VPN 扫描", host, port) }
        val remainingPorts = SignalServer.PORT_RANGE.filter { it != SignalServer.DEFAULT_PORT }
        for (pairs in remainingPorts.flatMap { port -> hosts.map { host -> host to port } }.chunked(SWEEP_CONCURRENCY)) {
            pairs.map { (host, port) -> async { (host to port).takeIf { probeControlledEndpoint(host, port, VPN_SWEEP_TIMEOUT_MS) } } }
                .awaitAll()
                .filterNotNull()
                .forEach { (host, port) -> found.putIfAbsent(host, port) }
            if (found.isNotEmpty()) return@withContext found.map { (host, port) -> ControlledDevice("VPN 扫描", host, port) }
        }
        found.map { (host, port) -> ControlledDevice("VPN 扫描", host, port) }
    }

    private fun showControlledDevices(controlled: List<ControlledDevice>) {
        showControlledDevices(controlled, null)
    }

    private fun showControlledDevices(controlled: List<ControlledDevice>, successMessage: String?) {
        peers.clear(); peers.addAll(controlled.map { it.name to "${it.ip}:${it.port}" })
        findViewById<ListView>(R.id.peerList).adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, controlled.map { "被控端  ${it.name}  ${it.ip}  端口 ${it.port}" })
        findViewById<TextView>(R.id.deviceStatusText).text = if (controlled.isEmpty()) {
            "未发现已就绪被控设备。请确认被控端已进入等待连接、双方 VPN 网络互通，或手动输入 10.126.x.x:19090。"
        } else {
            successMessage ?: "发现 ${controlled.size} 台已就绪被控设备；请先在被控端进入等待连接"
        }
        lifecycleScope.launch { rememberControlledDevices(controlled) }
    }

    private suspend fun rememberControlledDevices(controlled: List<ControlledDevice>) {
        historyStore.rememberDevices(controlled.map { StoredControlledDevice(it.name, it.ip, it.port, System.currentTimeMillis()) })
    }

    private suspend fun probeControlledEndpoint(ip: String): Int? = withContext(Dispatchers.IO) {
        val host = normalizeHost(ip)
        if (probeControlledEndpoint(host, SignalServer.DEFAULT_PORT)) return@withContext SignalServer.DEFAULT_PORT
        val remainingPorts = SignalServer.PORT_RANGE.filter { it != SignalServer.DEFAULT_PORT }
        for (ports in remainingPorts.chunked(PROBE_BATCH_SIZE)) {
            val match = coroutineScope {
                ports.map { port -> async { port.takeIf { probeControlledEndpoint(host, port) } } }
                    .awaitAll()
                    .filterNotNull()
                    .minOrNull()
            }
            if (match != null) return@withContext match
        }
        null
    }

    private fun probeControlledEndpoint(host: String, port: Int): Boolean {
        return probeControlledEndpoint(host, port, CONTROL_SIGNAL_TIMEOUT_MS)
    }

    private fun probeControlledEndpoint(host: String, port: Int, timeoutMs: Int): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.soTimeout = timeoutMs
                socket.getOutputStream().write(SignalCodec.encodeLine(SignalMessage.Probe()).toByteArray(Charsets.UTF_8))
                socket.getOutputStream().flush()
                val response = BufferedReader(InputStreamReader(socket.getInputStream())).readLine()
                (SignalCodec.decode(response) as? SignalMessage.ProbeResponse)?.magic == SignalMessage.CONTROLLED_READY_MAGIC
            }
        }.getOrDefault(false)
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
    }

    private data class ControlledDevice(val name: String, val ip: String, val port: Int)
}
