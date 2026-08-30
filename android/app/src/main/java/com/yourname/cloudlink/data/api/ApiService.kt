package com.yourname.cloudlink.data.api

import com.yourname.cloudlink.data.model.Device
import com.yourname.cloudlink.data.model.DeviceActionRequest
import com.yourname.cloudlink.data.model.LoginRequest
import com.yourname.cloudlink.data.model.RegisterRequest
import com.yourname.cloudlink.data.model.TokenResponse
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
    suspend fun refresh(@Header("Authorization") refresh: String): TokenResponse

    @GET("api/devices")
    suspend fun listDevices(@Header("Authorization") auth: String): List<Device>

    @POST("api/devices/{entityId}/action")
    suspend fun controlDevice(
        @Header("Authorization") auth: String,
        @Path("entityId") entityId: String,
        @Body req: DeviceActionRequest,
    )
}
