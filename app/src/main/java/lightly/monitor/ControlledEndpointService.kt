package lightly.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import lightly.monitor.control.AudioRouteController
import lightly.monitor.data.EasyTierConfig
import lightly.monitor.data.EasyTierNetworkProfile
import lightly.monitor.data.EasyTierProfileStore
import lightly.monitor.easytier.EasyTierManager
import lightly.monitor.easytier.EasyTierNetworkInfoAnalyzer
import lightly.monitor.easytier.StaticIpv4Allocator
import lightly.monitor.session.SessionController
import org.json.JSONObject

class ControlledEndpointService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var store: EasyTierProfileStore
    private lateinit var easyTierManager: EasyTierManager
    private var audioRouteController: AudioRouteController? = null
    private var sessionController: SessionController? = null

    override fun onCreate() {
        super.onCreate()
        store = EasyTierProfileStore(this)
        easyTierManager = EasyTierManager(this)
        startForeground(NOTIFICATION_ID, notification("被控端后台运行中"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch { startControlledEndpoint() }
        return START_STICKY
    }

    private suspend fun startControlledEndpoint() {
        if (!store.isAutoStartControlledEnabled()) {
            stopSelf()
            return
        }
        startVpnIfPossible()
        if (sessionController == null) {
            audioRouteController = AudioRouteController(this).also { it.enterCommunicationMode() }
            sessionController = SessionController(this).apply { startServer() }
        }
    }

    private suspend fun startVpnIfPossible() {
        if (easyTierManager.isExternalVpnActive()) return
        if (VpnService.prepare(this) != null) return
        val profiles = store.loadProfiles()
        val selectedId = store.getSelectedProfileId()
        val profile = profiles.firstOrNull { it.id == selectedId } ?: profiles.first()
        val preparedProfile = prepareStaticIpv4(profile)
        if (preparedProfile.config.ipv4 != profile.config.ipv4) {
            store.saveProfiles(profiles.map { if (it.id == profile.id) preparedProfile else it })
            store.setSelectedProfileId(preparedProfile.id)
        }
        val config = preparedProfile.config.toToml()
        if (easyTierManager.parseConfig(config)) easyTierManager.startVpn(config, preparedProfile.config.instanceName)
    }

    private fun prepareStaticIpv4(profile: EasyTierNetworkProfile): EasyTierNetworkProfile {
        if (profile.config.dhcp || !profile.config.ipv4.isNullOrBlank()) return profile
        return profile.copy(
            config = profile.config.copy(ipv4 = "${StaticIpv4Allocator.choose(usedStaticIps(profile.config))}/24"),
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

    private fun notification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "被控端后台运行", NotificationManager.IMPORTANCE_LOW))
            return Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Lightly Monitor")
                .setContentText(text)
                .setOngoing(true)
                .build()
        }
        return Notification.Builder(this)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Lightly Monitor")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        sessionController?.stop()
        audioRouteController?.leaveCommunicationMode()
        easyTierManager.stopVpn()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "controlled_endpoint"
        private const val NOTIFICATION_ID = 19090
    }
}
