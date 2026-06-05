package lightly.monitor.control

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

class AudioRouteController(context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    fun enterCommunicationMode() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        useBestOutputRoute()
    }

    fun useSpeaker(enabled: Boolean) {
        if (enabled) useBestOutputRoute() else choosePreferredCommunicationRoute()
    }

    private fun useBestOutputRoute() {
        val route = preferredOutputDevice()
        if (route != null || hasLegacyHeadsetRoute()) {
            choosePreferredCommunicationRoute()
        } else {
            audioManager.isSpeakerphoneOn = true
            raiseCommunicationVolume()
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) audioManager.isBluetoothScoOn = false
        }
    }

    fun availableRoutes(): List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        runCatching { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.routeName() } }.getOrElse { listOf("speaker") }
    } else {
        listOf("speaker")
    }

    fun leaveCommunicationMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.clearCommunicationDevice() }
        } else {
            runCatching { audioManager.stopBluetoothSco() }
            audioManager.isBluetoothScoOn = false
        }
        audioManager.isSpeakerphoneOn = false
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    private fun choosePreferredCommunicationRoute() {
        audioManager.isSpeakerphoneOn = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val route = preferredOutputDevice() ?: return
            runCatching { audioManager.setCommunicationDevice(route) }
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val route = preferredOutputDevice()
            if (route?.isBluetoothRoute() == true) {
                runCatching { audioManager.startBluetoothSco() }
                audioManager.isBluetoothScoOn = true
            }
            return
        }
        if (hasLegacyBluetoothRoute()) {
            runCatching { audioManager.startBluetoothSco() }
            audioManager.isBluetoothScoOn = true
        }
    }

    private fun raiseCommunicationVolume() {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        if (maxVolume > 0) runCatching { audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVolume, 0) }
    }

    private fun hasLegacyHeadsetRoute(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.M && (audioManager.isWiredHeadsetOn || hasLegacyBluetoothRoute())

    private fun hasLegacyBluetoothRoute(): Boolean = audioManager.isBluetoothScoOn || audioManager.isBluetoothA2dpOn

    private fun preferredOutputDevice(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return runCatching {
            val routes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.availableCommunicationDevices
            } else {
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
            }
            routes.firstOrNull { it.isWiredRoute() }
                ?: routes.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                ?: routes.firstOrNull { Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                ?: routes.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
                ?: routes.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        }.getOrNull()
    }

    private fun AudioDeviceInfo.isWiredRoute(): Boolean = type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || type == AudioDeviceInfo.TYPE_WIRED_HEADSET
    private fun AudioDeviceInfo.isBluetoothRoute(): Boolean = type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET)
    private fun AudioDeviceInfo.routeName(): String = when (type) { AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth"; AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired"; AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"; AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"; else -> productName?.toString() ?: "audio" }
}
