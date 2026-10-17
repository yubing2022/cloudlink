package com.yubing.cloudlink.data.api

import com.yubing.cloudlink.data.model.HomeDevice
import com.yubing.cloudlink.data.model.DeviceActionRequest
import com.yubing.cloudlink.data.model.DeviceState
import com.yubing.cloudlink.data.model.LoginRequest
import com.yubing.cloudlink.data.model.RefreshRequest
import com.yubing.cloudlink.data.model.RegisterRequest
import com.yubing.cloudlink.data.model.TokenResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

interface ApiService {
    @POST("api/auth/register")
    suspend fun register(@Body req: RegisterRequest): TokenResponse

    @POST("api/auth/login")
    suspend fun login(@Body req: LoginRequest): TokenResponse

    @POST("api/auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): TokenResponse

    @GET("api/device-icon")
    suspend fun getDeviceIcon(@retrofit2.http.Query("model") model: String): com.yubing.cloudlink.data.model.DeviceIconResponse

    /** Returns devices (with their entities nested). Backend now groups
     *  entities by HA device registry entry. */
    @GET("api/devices")
    suspend fun listDevices(@Header("Authorization") auth: String): List<HomeDevice>

    @POST("api/devices/{entityId}/action")
    suspend fun controlDevice(
        @Header("Authorization") auth: String,
        @Path("entityId") entityId: String,
        @Body req: DeviceActionRequest,
    ): DeviceState

    /**
     * Single-entity state (flat shape), used by the AgentBridge capability layer.
     * Authorization header is injected by [AuthInterceptor]; no explicit param needed.
     */
    @GET("api/devices/{entityId}/state")
    suspend fun getEntityState(
        @Path("entityId") entityId: String,
    ): DeviceState
}
