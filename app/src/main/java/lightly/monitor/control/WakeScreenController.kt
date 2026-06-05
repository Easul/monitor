package lightly.monitor.control

import android.content.Context
import android.os.PowerManager

class WakeScreenController(context: Context) {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var wakeLock: PowerManager.WakeLock? = null
    fun wake(timeoutMs: Long = 10_000L) { release(); wakeLock = powerManager.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "LightlyMonitor:WakeScreen").apply { acquire(timeoutMs) } }
    fun release() { if (wakeLock?.isHeld == true) wakeLock?.release(); wakeLock = null }
}
