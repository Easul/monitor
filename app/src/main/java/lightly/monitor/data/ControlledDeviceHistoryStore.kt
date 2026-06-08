package lightly.monitor.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

private val Context.controlledDeviceHistoryDataStore by preferencesDataStore("controlled_device_history")

class ControlledDeviceHistoryStore(private val context: Context) {
    private val devicesKey = stringPreferencesKey("controlled_devices")

    suspend fun loadDevices(): List<StoredControlledDevice> {
        val raw = context.controlledDeviceHistoryDataStore.data.first()[devicesKey]
        return decode(raw).sortedByDescending { it.lastSeenAt }.take(MAX_HISTORY_SIZE)
    }

    suspend fun rememberDevices(devices: List<StoredControlledDevice>) {
        if (devices.isEmpty()) return
        val now = System.currentTimeMillis()
        val known = loadDevices().associateBy { it.ip }.toMutableMap()
        devices.forEach { device ->
            known[device.ip] = device.copy(lastSeenAt = now)
        }
        val ordered = known.values.sortedByDescending { it.lastSeenAt }.take(MAX_HISTORY_SIZE)
        context.controlledDeviceHistoryDataStore.edit { values ->
            values[devicesKey] = JSONArray(ordered.map { it.toJson() }).toString()
        }
    }

    suspend fun deleteDevice(ip: String) {
        val remaining = loadDevices().filterNot { it.ip == ip }
        context.controlledDeviceHistoryDataStore.edit { values ->
            values[devicesKey] = JSONArray(remaining.map { it.toJson() }).toString()
        }
    }

    private fun decode(raw: String?): List<StoredControlledDevice> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(StoredControlledDevice::fromJson)
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val MAX_HISTORY_SIZE = 32
    }
}

data class StoredControlledDevice(val name: String, val ip: String, val port: Int, val lastSeenAt: Long) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("ip", ip)
        .put("port", port)
        .put("lastSeenAt", lastSeenAt)

    companion object {
        fun fromJson(json: JSONObject): StoredControlledDevice? {
            val ip = json.optString("ip").takeIf { it.isNotBlank() } ?: return null
            val port = json.optInt("port").takeIf { it > 0 } ?: return null
            return StoredControlledDevice(
                name = json.optString("name", "历史设备"),
                ip = ip,
                port = port,
                lastSeenAt = json.optLong("lastSeenAt", 0L)
            )
        }
    }
}
