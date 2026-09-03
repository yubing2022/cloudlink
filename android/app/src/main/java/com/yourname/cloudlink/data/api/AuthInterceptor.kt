package com.yourname.cloudlink.data.api

import com.yourname.cloudlink.data.local.TokenStore
import com.yourname.cloudlink.data.model.RefreshRequest
import dagger.Lazy
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OkHttp interceptor that:
 * 1. Injects the current `Authorization: Bearer <access_token>` header on
 *    every outgoing request.
 * 2. If the server returns 401 (access token expired), calls
 *    `/api/auth/refresh` with the saved refresh token, stores the new
 *    pair, and retries the original request once.
 *
 * The previous APK had no auto-refresh — the user saw "加载失败: HTTP 401"
 * every time the access token had expired (15-minute lifetime) and had to
 * log out / log in to recover.
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val tokenStore: TokenStore,
    // Lazy to break the circular dependency:
    // OkHttp → interceptor → ApiService → Retrofit → OkHttp.
    private val apiProvider: Lazy<ApiService>,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val access = runBlocking { tokenStore.getAccessToken() }
        val first = original.newBuilder().apply {
            if (!access.isNullOrEmpty()) header("Authorization", "Bearer $access")
        }.build()
        val response = chain.proceed(first)
        if (response.code != 401) return response

        // 401 → try to refresh once, then retry the request.
        val newAccess = runBlocking { tryRefresh() }
        response.close()
        if (newAccess.isNullOrEmpty()) {
            // Refresh failed — return the original 401; UI will surface it.
            return chain.proceed(original)
        }
        val retried = original.newBuilder()
            .header("Authorization", "Bearer $newAccess")
            .build()
        return chain.proceed(retried)
    }

    private suspend fun tryRefresh(): String? {
        val refresh = tokenStore.getRefreshToken() ?: return null
        return try {
            val resp = apiProvider.get().refresh(RefreshRequest(refresh))
            tokenStore.save(resp.access_token, resp.refresh_token, "")
            resp.access_token
        } catch (_: Exception) {
            null
        }
    }
}
