package com.yourname.cloudlink.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.cloudlink.data.api.ApiService
import com.yourname.cloudlink.data.local.TokenStore
import kotlinx.coroutines.flow.first
import com.yourname.cloudlink.data.model.LoginRequest
import com.yourname.cloudlink.data.model.RegisterRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException
import javax.inject.Inject

data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val success: Boolean = false,
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val api: ApiService,
    private val tokenStore: TokenStore,
) : ViewModel() {

    val state = MutableStateFlow(LoginUiState())

    init {
        // Pre-fill the email field with the last-used email so the user
        // only needs to type their password after re-installing or after
        // their token expires.
        viewModelScope.launch {
            val last = tokenStore.getLastEmail().orEmpty()
            if (last.isNotEmpty() && state.value.email.isEmpty()) {
                state.value = state.value.copy(email = last)
            }
        }
    }

    fun setEmail(v: String) { state.value = state.value.copy(email = v, error = null) }
    fun setPassword(v: String) { state.value = state.value.copy(password = v, error = null) }

    fun login() = launchAuth { req -> api.login(req) }
    fun register() = launchAuth { req -> api.register(RegisterRequest(req.email, req.password)) }

    private fun launchAuth(call: suspend (LoginRequest) -> com.yourname.cloudlink.data.model.TokenResponse) {
        val s = state.value
        if (s.email.isBlank() || s.password.length < 8) {
            state.value = s.copy(error = "请填写邮箱和至少 8 位密码")
            return
        }
        state.value = s.copy(isLoading = true, error = null)
        viewModelScope.launch {
            try {
                val resp = call(LoginRequest(s.email.trim(), s.password))
                tokenStore.save(resp.access_token, resp.refresh_token, s.email.trim())
                state.value = state.value.copy(isLoading = false, success = true)
            } catch (e: HttpException) {
                val msg = if (e.code() == 401) "邮箱或密码错误" else "登录失败: HTTP ${e.code()}"
                state.value = state.value.copy(isLoading = false, error = msg)
            } catch (e: Exception) {
                state.value = state.value.copy(isLoading = false, error = "网络错误: ${e.message}")
            }
        }
    }
}
