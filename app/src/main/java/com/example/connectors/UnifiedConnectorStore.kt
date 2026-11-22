package com.example.connectors

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.unifiedConnectorDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "unified_connector_store"
)

class UnifiedConnectorStore(private val context: Context) {

    private val mutex = Mutex()
    private val keyConfigJson = stringPreferencesKey("unified_connector_state_json")

    suspend fun loadState(): UnifiedConnectorState = mutex.withLock {
        val prefs = context.unifiedConnectorDataStore.data.first()
        UnifiedConnectorState.fromJson(prefs[keyConfigJson].orEmpty())
    }

    suspend fun saveState(state: UnifiedConnectorState) = mutex.withLock {
        context.unifiedConnectorDataStore.edit { prefs ->
            prefs[keyConfigJson] = state.toJson().toString()
        }
    }

    suspend fun updateState(transform: (UnifiedConnectorState) -> UnifiedConnectorState): UnifiedConnectorState = mutex.withLock {
        val prefs = context.unifiedConnectorDataStore.data.first()
        val current = UnifiedConnectorState.fromJson(prefs[keyConfigJson].orEmpty())
        val updated = transform(current)
        context.unifiedConnectorDataStore.edit { editPrefs ->
            editPrefs[keyConfigJson] = updated.toJson().toString()
        }
        updated
    }
}
