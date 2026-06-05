package lightly.monitor.easytier

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.easytier.jni.EasyTierJNI
import java.net.InetAddress
import kotlin.concurrent.thread

object EasyTierRouteNormalizer {
    fun parseRoute(cidr: String): Pair<String, Int> {
        val p = cidr.split("/")
        return toNetworkAddress(p[0], p[1].toInt()) to p[1].toInt()
    }

    fun toNetworkAddress(ip: String, prefixLength: Int): String {
        val bytes = InetAddress.getByName(ip).address
        var v = 0L
        for (b in bytes) v = (v shl 8) or (b.toLong() and 255)
        val mask = if (prefixLength == 0) 0 else (0xffffffffL shl (32 - prefixLength)) and 0xffffffffL
        val n = v and mask
        return listOf((n shr 24) and 255, (n shr 16) and 255, (n shr 8) and 255, n and 255).joinToString(".")
    }
}

class EasyTierVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    @Volatile private var running = false
    @Volatile private var stopRequested = false
    private var instanceName: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRequested = true
            cleanup()
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val ipv4 = intent?.getStringExtra(EXTRA_IPV4_ADDRESS)
        val cidrs = intent?.getStringArrayListExtra(EXTRA_PROXY_CIDRS) ?: arrayListOf()
        instanceName = intent?.getStringExtra(EXTRA_INSTANCE_NAME)
        if (ipv4.isNullOrBlank() || instanceName.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        stopRequested = false
        thread { setup(ipv4, cidrs) }
        return START_NOT_STICKY
    }

    private fun setup(ipv4: String, cidrs: List<String>) {
        try {
            val p = ipv4.split("/")
            val ip = p[0]
            val prefix = p.getOrNull(1)?.toIntOrNull() ?: 24
            val builder = Builder()
                .setSession("Lightly EasyTier")
                .addAddress(ip, prefix)
                .addRoute(EasyTierRouteNormalizer.toNetworkAddress(ip, prefix), prefix)
            cidrs.distinct().forEach { cidr ->
                runCatching {
                    val (route, length) = EasyTierRouteNormalizer.parseRoute(cidr)
                    if (isEasyTierOverlayRoute(route, length)) {
                        builder.addRoute(route, length)
                    } else {
                        Log.i(TAG, "Ignoring non-overlay VPN route: $cidr")
                    }
                }
            }
            vpnInterface = builder.establish()
            if (stopRequested || vpnInterface == null) return
            vpnInterface?.fd?.let { fd ->
                runCatching { EasyTierJNI.setTunFd(instanceName!!, fd) }
                    .onFailure {
                        Log.e(TAG, "Failed to pass TUN fd to EasyTier JNI", it)
                        stopSelf()
                        return
                    }
            }
            running = true
            while (running && !stopRequested && vpnInterface != null) Thread.sleep(1000)
        } finally {
            cleanup()
        }
    }

    private fun isEasyTierOverlayRoute(route: String, prefixLength: Int): Boolean {
        return route.startsWith(EASYTIER_OVERLAY_PREFIX) && prefixLength >= EASYTIER_OVERLAY_PREFIX_LENGTH
    }

    private fun cleanup() {
        running = false
        runCatching { vpnInterface?.close() }
        vpnInterface = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        cleanup()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopRequested = true
        cleanup()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopRequested = true
        cleanup()
        stopSelf()
        super.onRevoke()
    }

    companion object {
        private const val TAG = "EasyTierVpnService"
        const val ACTION_STOP = "lightly.monitor.action.STOP_EASYTIER_VPN"
        const val EXTRA_IPV4_ADDRESS = "ipv4_address"
        const val EXTRA_PROXY_CIDRS = "proxy_cidrs"
        const val EXTRA_INSTANCE_NAME = "instance_name"
        private const val EASYTIER_OVERLAY_PREFIX = "10.126.126."
        private const val EASYTIER_OVERLAY_PREFIX_LENGTH = 24
    }
}
