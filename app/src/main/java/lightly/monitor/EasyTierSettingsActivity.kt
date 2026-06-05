package lightly.monitor

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import lightly.monitor.data.EasyTierConfig
import lightly.monitor.data.EasyTierProfileStore
import lightly.monitor.data.MimoApiConfig
import lightly.monitor.data.MimoApiStore

class EasyTierSettingsActivity : AppCompatActivity() {
    private lateinit var store: EasyTierProfileStore
    private lateinit var mimoStore: MimoApiStore
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_easytier_settings)
        store = EasyTierProfileStore(this)
        mimoStore = MimoApiStore(this)
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        lifecycleScope.launch { loadSelectedProfile(); loadMimoSettings() }
        findViewById<Button>(R.id.saveProfileButton).setOnClickListener { lifecycleScope.launch { saveProfile(); saveMimoSettings() } }
    }
    private suspend fun loadSelectedProfile() {
        val profiles = store.loadProfiles()
        val selected = profiles.firstOrNull { it.id == store.getSelectedProfileId() } ?: profiles.first()
        findViewById<EditText>(R.id.profileNameInput).setText(selected.name)
        findViewById<EditText>(R.id.instanceNameInput).setText(selected.config.instanceName)
        findViewById<EditText>(R.id.networkNameInput).setText(selected.config.networkName)
        findViewById<EditText>(R.id.networkSecretInput).setText(selected.config.networkSecret ?: "")
        findViewById<EditText>(R.id.ipv4Input).setText(selected.config.ipv4 ?: "")
        findViewById<CheckBox>(R.id.autoStartControlledSwitch).isChecked = store.isAutoStartControlledEnabled()
        findViewById<EditText>(R.id.peersInput).setText(selected.config.peers.joinToString("\n"))
    }
    private suspend fun loadMimoSettings() {
        val config = mimoStore.load()
        findViewById<EditText>(R.id.mimoBaseUrlInput).setText(config.baseUrl)
        findViewById<EditText>(R.id.mimoApiKeyInput).setText(config.apiKey)
    }
    private suspend fun saveMimoSettings() {
        mimoStore.save(MimoApiConfig(
            baseUrl = text(R.id.mimoBaseUrlInput).ifBlank { MimoApiConfig.DEFAULT_BASE_URL },
            apiKey = text(R.id.mimoApiKeyInput)
        ))
    }
    private suspend fun saveProfile() {
        val existing = store.loadProfiles()
        val selectedId = store.getSelectedProfileId() ?: existing.first().id
        val old = existing.firstOrNull { it.id == selectedId } ?: existing.first()
        val config = EasyTierConfig(
            instanceName = text(R.id.instanceNameInput).ifBlank { "lightly_monitor" },
            networkName = text(R.id.networkNameInput).ifBlank { "default_network" },
            networkSecret = text(R.id.networkSecretInput),
            ipv4 = text(R.id.ipv4Input),
            peers = text(R.id.peersInput).lines().map { it.trim() }.filter { it.isNotEmpty() },
            hostname = text(R.id.instanceNameInput).ifBlank { "lightly_monitor" },
        )
        val updated = old.copy(name = text(R.id.profileNameInput).ifBlank { config.networkName }, config = config, updatedAt = System.currentTimeMillis())
        store.saveProfiles(existing.map { if (it.id == old.id) updated else it })
        store.setSelectedProfileId(updated.id)
        store.setAutoStartControlledEnabled(findViewById<CheckBox>(R.id.autoStartControlledSwitch).isChecked)
        findViewById<TextView>(R.id.settingsStatusText).text = "配置已保存"
    }
    private fun text(id: Int): String {
        val input = findViewById<EditText>(id)
        val value = input.text.toString().trim()
        val hint = input.hint?.toString()?.trim().orEmpty()
        return when {
            value == hint -> ""
            hint.isNotBlank() && value.startsWith(hint) -> value.removePrefix(hint).trim()
            else -> value
        }
    }
}
