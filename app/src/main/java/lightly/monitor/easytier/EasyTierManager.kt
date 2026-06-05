package lightly.monitor.easytier

import android.app.Activity
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.easytier.jni.EasyTierJNI
import org.json.JSONObject
import java.net.Inet4Address

class EasyTierManager(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var monitorRunnable: Runnable? = null
    private var runningInstanceName: String? = null
    private var runningConfig: String? = null
    private var currentIpv4: String? = null
    private var currentProxyCidrs: List<String> = emptyList()
    private var missingInfoTicks = 0
    private var notRunningTicks = 0
    private var restartInProgress = false
    private var lastError: String? = null

    fun parseConfig(config: String): Boolean {
        val result = callEasyTier("parseConfig") { EasyTierJNI.parseConfig(config) } ?: return false
        if (result != 0) {
            lastError = callEasyTier("getLastError") { EasyTierJNI.getLastError() } ?: "Config parse failed"
            return false
        }
        lastError = null
        return true
    }

    fun checkVpnPermission(): Boolean = VpnService.prepare(context) == null

    fun isExternalVpnActive(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return connectivityManager.allNetworks.any { network ->
            connectivityManager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    fun activeVpnIpv4Hosts(): List<String> {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return emptyList()
        val hosts = mutableListOf<String>()
        connectivityManager.allNetworks.forEach { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true) return@forEach
            connectivityManager.getLinkProperties(network)?.linkAddresses.orEmpty().forEach { linkAddress ->
                val address = linkAddress.address
                if (address is Inet4Address) {
                    val prefixLength = linkAddress.prefixLength.coerceIn(24, 30)
                    hosts += ipv4SubnetHosts(address, prefixLength)
                }
            }
        }
        return hosts.distinct()
    }

    fun startVpn(config: String, instanceName: String): Boolean {
        if (isExternalVpnActive()) {
            Log.i(TAG, "System VPN is active; reusing existing VPN instead of starting monitor VpnService")
            lastError = null
            return true
        }
        val result = callEasyTier("runNetworkInstance") { EasyTierJNI.runNetworkInstance(config) } ?: return false
        if (result != 0) {
            lastError = callEasyTier("getLastError") { EasyTierJNI.getLastError() } ?: "VPN start failed"
            return false
        }
        lastError = null
        runningConfig = config
        startMonitor(instanceName)
        return true
    }

    fun stopVpn() {
        stopMonitor()
        callEasyTier("stopAllInstances") { EasyTierJNI.stopAllInstances() }
        val stopIntent = Intent(context, EasyTierVpnService::class.java).apply { action = EasyTierVpnService.ACTION_STOP }
        runCatching { context.startService(stopIntent) }
        runCatching { context.stopService(Intent(context, EasyTierVpnService::class.java)) }
        runningConfig = null
    }

    fun getNetworkInfo(): String? = callEasyTier("collectNetworkInfos") { EasyTierJNI.collectNetworkInfos(10) }

    fun getLightlyNetworkInfo(): LightlyEasyTierNetworkInfo? {
        return runCatching {
            context.contentResolver.query(LIGHTLY_NETWORK_INFO_URI, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val rawJson = cursor.stringColumn("raw_network_info_json")?.takeIf { it.isNotBlank() } ?: return@use null
                LightlyEasyTierNetworkInfo(
                    instanceName = cursor.stringColumn("instance_name").orEmpty(),
                    rawNetworkInfoJson = rawJson,
                    virtualIpv4 = cursor.stringColumn("virtual_ipv4"),
                    updatedAt = cursor.longColumn("updated_at") ?: 0L,
                    isRunning = cursor.intColumn("is_running") == 1,
                    errorMessage = cursor.stringColumn("error_message")
                )
            }
        }.onFailure { error ->
            Log.w(TAG, "Lightly EasyTier provider query failed", error)
        }.getOrNull()
    }

    fun getLastError(): String? {
        return lastError ?: callEasyTier("getLastError") { EasyTierJNI.getLastError() }
    }

    private fun startMonitor(instanceName: String) {
        stopMonitor(); runningInstanceName = instanceName; currentIpv4 = null; currentProxyCidrs = emptyList(); missingInfoTicks = 0; notRunningTicks = 0; restartInProgress = false
        monitorRunnable = object : Runnable { override fun run() { try { monitorStatus() } finally { handler.postDelayed(this, 3000) } } }
        monitorRunnable?.let(handler::post)
    }
    private fun stopMonitor() { monitorRunnable?.let(handler::removeCallbacks); monitorRunnable = null; runningInstanceName = null; currentIpv4 = null; currentProxyCidrs = emptyList() }
    private fun monitorStatus() {
        val instanceName = runningInstanceName ?: return
        val infosJson = callEasyTier("collectNetworkInfos") { EasyTierJNI.collectNetworkInfos(10) }
        if (infosJson.isNullOrBlank()) { if (++missingInfoTicks >= 4) restartEasyTierInstance("missing-network-info"); return }
        missingInfoTicks = 0
        val root = runCatching { JSONObject(infosJson) }.getOrNull() ?: return
        val networkInfo = root.optJSONObject("map")?.optJSONObject(instanceName) ?: return
        if (!networkInfo.optBoolean("running", false)) { if (++notRunningTicks >= 2) restartEasyTierInstance("instance-not-running"); return }
        notRunningTicks = 0
        val virtualIpv4 = EasyTierNetworkInfoAnalyzer.extractInstanceIpv4(root, instanceName) ?: return
        val proxyCidrs = extractProxyCidrs(networkInfo)
        if (virtualIpv4 != currentIpv4 || proxyCidrs != currentProxyCidrs) { currentIpv4 = virtualIpv4; currentProxyCidrs = proxyCidrs; restartEasyTierVpnService(instanceName, virtualIpv4, proxyCidrs) }
    }
    private fun extractProxyCidrs(networkInfo: JSONObject): List<String> {
        val routes = networkInfo.optJSONArray("routes") ?: return emptyList()
        val proxyCidrs = mutableListOf<String>()
        for (i in 0 until routes.length()) { val cidrs = routes.optJSONObject(i)?.optJSONArray("proxy_cidrs") ?: continue; for (j in 0 until cidrs.length()) cidrs.optString(j).takeIf { it.isNotBlank() }?.let(proxyCidrs::add) }
        return proxyCidrs.distinct()
    }
    private fun restartEasyTierInstance(reason: String) {
        val config = runningConfig; val instanceName = runningInstanceName
        if (config.isNullOrBlank() || instanceName.isNullOrBlank() || restartInProgress) return
        restartInProgress = true
        try { callEasyTier("stopAllInstances") { EasyTierJNI.stopAllInstances() }; if ((callEasyTier("runNetworkInstance") { EasyTierJNI.runNetworkInstance(config) } ?: -1) == 0) { currentIpv4 = null; currentProxyCidrs = emptyList(); missingInfoTicks = 0; notRunningTicks = 0 } else Log.e(TAG, "Restart failed after $reason: ${getLastError()}") } finally { restartInProgress = false }
    }

    private inline fun <T> callEasyTier(operation: String, block: () -> T): T? {
        return try {
            block()
        } catch (error: UnsatisfiedLinkError) {
            lastError = "EasyTier native library is not compatible with this Android system: ${error.message}"
            Log.e(TAG, "EasyTier JNI $operation failed", error)
            null
        } catch (error: Throwable) {
            lastError = error.message ?: "EasyTier JNI $operation failed"
            Log.e(TAG, "EasyTier JNI $operation failed", error)
            null
        }
    }

    private fun ipv4SubnetHosts(address: Inet4Address, prefixLength: Int): List<String> {
        val value = address.address.fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xff) }
        val mask = (0xffffffffL shl (32 - prefixLength)) and 0xffffffffL
        val network = value and mask
        val broadcast = network or (mask xor 0xffffffffL)
        return (network + 1 until broadcast).map(::formatIpv4).let(::prioritizeOverlayHosts)
    }

    private fun prioritizeOverlayHosts(hosts: List<String>): List<String> {
        return hosts.sortedWith(compareBy<String> { host ->
            val last = host.substringAfterLast('.').toIntOrNull() ?: 255
            when (last) {
                in 100..150 -> 0
                in 2..99 -> 1
                else -> 2
            }
        }.thenBy { it.substringAfterLast('.').toIntOrNull() ?: 255 })
    }

    private fun formatIpv4(value: Long): String {
        return listOf((value shr 24) and 255, (value shr 16) and 255, (value shr 8) and 255, value and 255).joinToString(".")
    }

    private fun restartEasyTierVpnService(instanceName: String, ipv4: String, proxyCidrs: List<String>) {
        runCatching { context.startService(Intent(context, EasyTierVpnService::class.java).apply { action = EasyTierVpnService.ACTION_STOP }) }
        runCatching { context.stopService(Intent(context, EasyTierVpnService::class.java)) }
        context.startService(Intent(context, EasyTierVpnService::class.java).apply { putExtra(EasyTierVpnService.EXTRA_IPV4_ADDRESS, ipv4); putStringArrayListExtra(EasyTierVpnService.EXTRA_PROXY_CIDRS, ArrayList(proxyCidrs)); putExtra(EasyTierVpnService.EXTRA_INSTANCE_NAME, instanceName) })
    }

    private fun Cursor.stringColumn(name: String): String? {
        val index = getColumnIndex(name)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }

    private fun Cursor.intColumn(name: String): Int? {
        val index = getColumnIndex(name)
        return if (index >= 0 && !isNull(index)) getInt(index) else null
    }

    private fun Cursor.longColumn(name: String): Long? {
        val index = getColumnIndex(name)
        return if (index >= 0 && !isNull(index)) getLong(index) else null
    }

    companion object { private const val TAG = "EasyTierManager"; private val LIGHTLY_NETWORK_INFO_URI = Uri.parse("content://lightly.tool.easytier/network_info"); const val REQUEST_VPN_PERMISSION = 7201; fun requestVpnPermission(activity: Activity): Boolean { val intent = VpnService.prepare(activity) ?: return true; activity.startActivityForResult(intent, REQUEST_VPN_PERMISSION); return false } }
}

data class LightlyEasyTierNetworkInfo(
    val instanceName: String,
    val rawNetworkInfoJson: String,
    val virtualIpv4: String?,
    val updatedAt: Long,
    val isRunning: Boolean,
    val errorMessage: String?
)
