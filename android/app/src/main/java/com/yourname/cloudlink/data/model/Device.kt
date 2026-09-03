package com.yourname.cloudlink.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * One HA entity (e.g. switch.cuco_cn_..._on_p_2_1).
 *
 * Belongs to a HomeDevice (the physical/logical device that hosts
 * multiple entities — e.g. a smart plug has switch + button + sensor
 * entities all in one device).
 */
@JsonClass(generateAdapter = true)
data class Entity(
    val id: Long,
    val entity_id: String,
    val domain: String,
    val name: String,
    val state: String,
    val attributes: Map<String, Any?> = emptyMap(),
    /** HA's entity_category: null=real, "config"=config entry,
     *  "diagnostic"=diagnostic. HA plugin filters out non-null. */
    @Json(name = "entity_category") val entityCategory: String? = null,
    @Json(name = "last_state_change") val lastStateChange: String? = null,
    @Json(name = "updated_at") val updatedAt: String? = null,
) {
    /**
     * True if this light supports brightness control. Looks at
     * `supported_color_modes` first (the modern API); falls back to the
     * `supported_features` bitmask.
     *
     * Rule:
     *  - `["onoff"]` only         → no brightness
     *  - any other mode present   → supports brightness (color_temp,
     *                               hs, rgb, rgbw, rgbww, white all imply
     *                               PWM-brightness support)
     *  - `unknown` mode           → no brightness
     *  - supported_color_modes missing → trust supported_features bit 0
     */
    val supportsBrightness: Boolean
        get() {
            val modes = (attributes["supported_color_modes"] as? List<*>)
                ?.mapNotNull { it as? String }
                ?: emptyList()
            if (modes.isNotEmpty()) {
                if ("onoff" in modes || "unknown" in modes) return false
                return modes.isNotEmpty()
            }
            val features = (attributes["supported_features"] as? Number)?.toInt() ?: 0
            return (features and 1) != 0  // bit 0 = SUPPORT_BRIGHTNESS
        }

    /**
     * Current brightness 0-255, or null if not reported (e.g. the light
     * is off and HA has no last-known value).
     */
    val brightness: Int?
        get() = (attributes["brightness"] as? Number)?.toInt()

    /**
     * When entity is off, brightness attribute is usually null. We fall
     * back to the last-known brightness (provided by the caller) so the
     * slider doesn't snap to 0 the moment the light turns off.
     */
    fun effectiveBrightness(fallback: Int = 128): Int = brightness ?: fallback
}

/**
 * One physical/logical device from HA's device registry.
 *
 * Contains all entities belonging to this device. The App can show the
 * device as a card with sub-controls for each entity.
 */
@JsonClass(generateAdapter = true)
data class HomeDevice(
    val id: Long,
    @Json(name = "ha_device_id") val haDeviceId: String,
    val name: String,
    val manufacturer: String? = null,
    val model: String? = null,
    val area: String? = null,
    @Json(name = "sw_version") val swVersion: String? = null,
    @Json(name = "hw_version") val hwVersion: String? = null,
    val entities: List<Entity> = emptyList(),
    @Json(name = "updated_at") val updatedAt: String? = null,
) {
    /** The "primary" entity is the most useful one to toggle (e.g. switch
     *  or light for power devices). Used for the quick-action button. */
    val primaryEntity: Entity?
        get() = entities.firstOrNull { e ->
            e.domain in listOf("light", "switch", "fan", "media_player", "climate")
        } ?: entities.firstOrNull()

    /** Domain → first entity of that domain in this device. */
    fun entityByDomain(domain: String): Entity? =
        entities.firstOrNull { it.domain == domain }
}

@JsonClass(generateAdapter = true)
data class DeviceActionRequest(
    val domain: String,
    val service: String,
    val data: Map<String, Any?> = emptyMap(),
)

@JsonClass(generateAdapter = true)
data class ApiError(
    val detail: String? = null,
)


@JsonClass(generateAdapter = true)
data class DeviceIconResponse(
    val model: String,
    @com.squareup.moshi.Json(name = "icon_url") val iconUrl: String,
)
