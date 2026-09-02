package com.yourname.cloudlink.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.hiddenDataStore by preferencesDataStore(name = "cloudlink_hidden_devices")

private val KEY_HIDDEN = stringSetPreferencesKey("hidden_ha_device_ids")

/**
 * Stores the set of HA device IDs the user has chosen to hide on the home
 * screen. Persisted via DataStore so it survives app restarts.
 *
 * Empty set = show everything (default).
 */
@Singleton
class HiddenDevicesStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val hidden: Flow<Set<String>> = context.hiddenDataStore.data.map { prefs ->
        prefs[KEY_HIDDEN] ?: emptySet()
    }

    suspend fun getHidden(): Set<String> = hidden.first()

    suspend fun setHidden(ids: Set<String>) {
        context.hiddenDataStore.edit { prefs ->
            if (ids.isEmpty()) {
                prefs.remove(KEY_HIDDEN)
            } else {
                prefs[KEY_HIDDEN] = ids
            }
        }
    }

    suspend fun toggle(deviceId: String) {
        context.hiddenDataStore.edit { prefs ->
            val current = prefs[KEY_HIDDEN] ?: emptySet()
            prefs[KEY_HIDDEN] = if (deviceId in current) current - deviceId else current + deviceId
        }
    }
}
