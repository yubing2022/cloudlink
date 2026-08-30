package com.yourname.cloudlink.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Device(
    val id: Long,
    val entity_id: String,
    val domain: String,
    val name: String,
    val state: String,
    val attributes: Map<String, Any?> = emptyMap(),
    @Json(name = "ha_instance_id") val haInstanceId: Long,
    @Json(name = "updated_at") val updatedAt: String? = null,
)

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
