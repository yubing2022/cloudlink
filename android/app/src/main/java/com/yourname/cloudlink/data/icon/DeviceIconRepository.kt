package com.yourname.cloudlink.data.icon

import android.content.Context
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.yourname.cloudlink.data.api.ApiService
import com.yourname.cloudlink.data.model.DeviceIconResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import java.io.File

/**
 * Two-level cache for miot-spec.com device icons.
 *
 *   memory cache (Map<model, String?>)  ←─ hot path
 *        │ miss
 *        ▼
 *   persistent cache (cacheDir/device_icons.json)
 *        │ miss
 *        ▼
 *   GET /api/device-icon?model=…   (backend scrapes miot-spec.com, caches server-side)
 *
 * The two outcomes of a model:
 *   * URL string  — found, render <AsyncImage>
 *   * null        — verified not on miot-spec, fall back to Material icon
 *
 * The persistent file is a tiny JSON, written on every successful fetch
 * so a reinstall/restart doesn't trigger another network call.
 */
@Singleton
class DeviceIconRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: ApiService,
    private val moshi: Moshi,
) {
    private val mutex = Mutex()
    private val found: MutableMap<String, String> = mutableMapOf()
    private val notFound: MutableSet<String> = mutableSetOf()
    private var loaded = false
    private val adapter = moshi.adapter(CacheFile::class.java)

    private val cacheFile: File
        get() = File(context.cacheDir, "device_icons.json")

    private fun loadIfNeeded() {
        if (loaded) return
        loaded = true
        try {
            if (cacheFile.exists()) {
                val data = adapter.fromJson(cacheFile.readText()) ?: return
                found.putAll(data.found)
                notFound.addAll(data.notFound)
            }
        } catch (_: Exception) {
            // ignore corrupt cache; will rebuild
        }
    }

    private fun persist() {
        try {
            val data = CacheFile(found.toMap(), notFound.toSet())
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(adapter.toJson(data))
        } catch (_: Exception) {
            // best-effort
        }
    }

    /**
     * Returns the icon URL for `model`, or null if miot-spec.com has no
     * page for it (or the call failed). Caches both outcomes.
     */
    suspend fun getIconUrl(model: String): String? = mutex.withLock {
        if (model.isBlank()) return null
        loadIfNeeded()
        found[model]?.let { return it }
        if (model in notFound) return null
        val url = try {
            api.getDeviceIcon(model).iconUrl
        } catch (_: Exception) {
            // 404 or network error → mark as "no icon" so we don't keep retrying
            null
        }
        if (url != null) {
            found[model] = url
        } else {
            notFound.add(model)
        }
        persist()
        url
    }

    @JsonClass(generateAdapter = true)
    internal data class CacheFile(
        val found: Map<String, String> = emptyMap(),
        val notFound: Set<String> = emptySet(),
    )
}
