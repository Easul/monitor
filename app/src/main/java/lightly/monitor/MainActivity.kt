package lightly.monitor

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import lightly.monitor.data.EasyTierConfig
import lightly.monitor.data.EasyTierNetworkProfile
import lightly.monitor.data.EasyTierProfileStore
import lightly.monitor.easytier.EasyTierManager
import lightly.monitor.easytier.EasyTierNetworkInfoAnalyzer
import lightly.monitor.easytier.StaticIpv4Allocator
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var profileStore: EasyTierProfileStore
    private lateinit var easyTierManager: EasyTierManager
    private var pendingConfig: String? = null
    private var pendingInstanceName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        profileStore = EasyTierProfileStore(this)
        easyTierManager = EasyTierManager(this)
        findViewById<Button>(R.id.settingsButton).setOnClickListener { startActivity(Intent(this, EasyTierSettingsActivity::class.java)) }
        findViewById<Button>(R.id.devicesButton).setOnClickListener { startActivity(Intent(this, DeviceListActivity::class.java)) }
        findViewById<Button>(R.id.controlledListenButton).setOnClickListener { startActivity(Intent(this, SessionActivity::class.java)) }
        findViewById<Button>(R.id.startVpnButton).setOnClickListener { startSelectedVpn() }
        findViewById<Button>(R.id.stopVpnButton).setOnClickListener { easyTierManager.stopVpn(); status("VPN stopped") }
        findViewById<Button>(R.id.exitAllButton).setOnClickListener { exitAllConnections() }
        requestBatteryOptimizationExemption()
    }

    private fun startSelectedVpn() = lifecycleScope.launch {
        val profiles = profileStore.loadProfiles()
        val selectedId = profileStore.getSelectedProfileId()
        val profile = profiles.firstOrNull { it.id == selectedId } ?: profiles.first()
        val preparedProfile = prepareStaticIpv4(profile)
        if (preparedProfile.id != profile.id || preparedProfile.config.ipv4 != profile.config.ipv4) {
            profileStore.saveProfiles(profiles.map { if (it.id == profile.id) preparedProfile else it })
            profileStore.setSelectedProfileId(preparedProfile.id)
        }
        val config = preparedProfile.config.toToml()
        if (!easyTierManager.parseConfig(config)) { status("Config parse failed: ${easyTierManager.getLastError()}"); return@launch }
        if (easyTierManager.isExternalVpnActive()) {
            pendingConfig = null
            pendingInstanceName = null
            status("检测到若轻或其他 VPN 已运行，已复用现有 VPN")
            return@launch
        }
        pendingConfig = config
        pendingInstanceName = preparedProfile.config.instanceName
        if (EasyTierManager.requestVpnPermission(this@MainActivity)) startPendingVpn()
    }

    private fun prepareStaticIpv4(profile: EasyTierNetworkProfile): EasyTierNetworkProfile {
        if (profile.config.dhcp || !profile.config.ipv4.isNullOrBlank()) return profile
        val assignedIp = StaticIpv4Allocator.choose(usedStaticIps(profile.config))
        status("已自动分配静态 IPv4：$assignedIp")
        return profile.copy(
            config = profile.config.copy(ipv4 = "$assignedIp/24"),
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun usedStaticIps(config: EasyTierConfig): Set<String> {
        val used = mutableSetOf<String>()
        easyTierManager.getNetworkInfo()?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching {
                val root = JSONObject(raw)
                EasyTierNetworkInfoAnalyzer.extractInstanceIpv4(root, config.instanceName)?.substringBefore('/')?.let(used::add)
                EasyTierNetworkInfoAnalyzer.buildPeerSummaries(root, config.instanceName).mapTo(used) { it.ip.substringBefore('/') }
            }
        }
        return used
    }

    private fun startPendingVpn() {
        val config = pendingConfig ?: return
        val instanceName = pendingInstanceName ?: return
        val started = easyTierManager.startVpn(config, instanceName)
        status(if (started) "EasyTier instance started; waiting for virtual IPv4" else "VPN start failed: ${easyTierManager.getLastError()}")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) { super.onActivityResult(requestCode, resultCode, data); if (requestCode == EasyTierManager.REQUEST_VPN_PERMISSION && resultCode == RESULT_OK) startPendingVpn() }
    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = Uri.parse("package:$packageName") })
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
        status("请允许应用不受电池优化限制，减少长时间无操作被清理")
    }
    private fun status(text: String) { findViewById<TextView>(R.id.statusText).text = text }

    private fun exitAllConnections() {
        stopService(Intent(this, ControlledEndpointService::class.java))
        easyTierManager.stopVpn()
        status("已退出所有连接")
        finishAffinity()
    }

    override fun onDestroy() { super.onDestroy() }

}
