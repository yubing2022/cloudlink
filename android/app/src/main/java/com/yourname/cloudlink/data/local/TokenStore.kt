package com.yourname.cloudlink.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "cloudlink_prefs")

private val KEY_ACCESS = stringPreferencesKey("access_token")
private val KEY_REFRESH = stringPreferencesKey("refresh_token")
private val KEY_EMAIL = stringPreferencesKey("email")

@Singleton
class TokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val accessToken: Flow<String?> = context.dataStore.data.map { it[KEY_ACCESS] }
    val refreshToken: Flow<String?> = context.dataStore.data.map { it[KEY_REFRESH] }
    val email: Flow<String?> = context.dataStore.data.map { it[KEY_EMAIL] }

    suspend fun getAccessToken(): String? = context.dataStore.data.first()[KEY_ACCESS]

    suspend fun save(access: String, refresh: String, email: String) {
        context.dataStore.edit {
            it[KEY_ACCESS] = access
            it[KEY_REFRESH] = refresh
            it[KEY_EMAIL] = email
        }
    }

    suspend fun clear() {
        context.dataStore.edit {
            it.remove(KEY_ACCESS)
            it.remove(KEY_REFRESH)
            it.remove(KEY_EMAIL)
        }
    }
}
