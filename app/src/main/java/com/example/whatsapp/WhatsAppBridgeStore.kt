package com.example.whatsapp

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.whatsAppBridgeDataStore by preferencesDataStore(name = "whatsapp_bridge_store")

class WhatsAppBridgeStore(private val context: Context) {

    private val configJsonKey = stringPreferencesKey("wa_bridge_config_json")

    suspend fun loadConfig(): WhatsAppBridgeConfig {
        val raw = context.whatsAppBridgeDataStore.data.first()[configJsonKey].orEmpty()
        return WhatsAppBridgeConfig.fromJson(raw)
    }

    suspend fun updateConfig(transform: (WhatsAppBridgeConfig) -> WhatsAppBridgeConfig): WhatsAppBridgeConfig {
        var updated = WhatsAppBridgeConfig()
        context.whatsAppBridgeDataStore.edit { prefs ->
            val current = WhatsAppBridgeConfig.fromJson(prefs[configJsonKey].orEmpty())
            updated = transform(current)
            prefs[configJsonKey] = updated.toJson().toString()
        }
        return updated
    }
}
