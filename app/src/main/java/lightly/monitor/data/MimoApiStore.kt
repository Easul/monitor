package lightly.monitor.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.mimoApiDataStore by preferencesDataStore("mimo_api_settings")

class MimoApiStore(private val context: Context) {
    private val baseUrlKey = stringPreferencesKey("mimo_base_url")
    private val apiKeyKey = stringPreferencesKey("mimo_api_key")

    suspend fun load(): MimoApiConfig {
        val values = context.mimoApiDataStore.data.first()
        return MimoApiConfig(
            baseUrl = values[baseUrlKey] ?: "",
            apiKey = values[apiKeyKey] ?: ""
        )
    }

    suspend fun save(config: MimoApiConfig) {
        context.mimoApiDataStore.edit { values ->
            values[baseUrlKey] = config.normalizedBaseUrl()
            values[apiKeyKey] = config.apiKey.trim()
        }
    }
}
