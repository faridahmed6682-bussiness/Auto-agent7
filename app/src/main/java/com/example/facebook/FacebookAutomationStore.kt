package com.example.facebook

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.facebookAutomationDataStore by preferencesDataStore(name = "facebook_automation_store")

class FacebookAutomationStore(private val context: Context) {

    private val configJsonKey = stringPreferencesKey("fb_automation_config_json")

    val configFlow: Flow<FacebookAutomationConfig> = context.facebookAutomationDataStore.data.map { prefs ->
        FacebookAutomationConfig.fromJson(prefs[configJsonKey].orEmpty())
    }

    suspend fun loadConfig(): FacebookAutomationConfig {
        val raw = context.facebookAutomationDataStore.data.first()[configJsonKey].orEmpty()
        return FacebookAutomationConfig.fromJson(raw)
    }

    suspend fun saveConfig(config: FacebookAutomationConfig) {
        context.facebookAutomationDataStore.edit { prefs ->
            prefs[configJsonKey] = config.toJson().toString()
        }
    }

    suspend fun updateConfig(transform: (FacebookAutomationConfig) -> FacebookAutomationConfig): FacebookAutomationConfig {
        var updated = FacebookAutomationConfig()
        context.facebookAutomationDataStore.edit { prefs ->
            val current = FacebookAutomationConfig.fromJson(prefs[configJsonKey].orEmpty())
            updated = transform(current)
            prefs[configJsonKey] = updated.toJson().toString()
        }
        return updated
    }
}
