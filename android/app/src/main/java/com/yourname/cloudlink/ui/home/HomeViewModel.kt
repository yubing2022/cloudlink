package com.yourname.cloudlink.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.cloudlink.data.api.ApiService
import com.yourname.cloudlink.data.local.TokenStore
import com.yourname.cloudlink.data.model.DeviceActionRequest
import com.yourname.cloudlink.data.model.Entity
import com.yourname.cloudlink.data.model.HomeDevice
import com.yourname.cloudlink.data.ws.DeviceEvent
import com.yourname.cloudlink.data.ws.DeviceWebSocket
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException
import javax.inject.Inject

data class HomeUiState(
    val devices: List<HomeDevice> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val wsConnected: Boolean = false,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val api: ApiService,
    private val tokenStore: TokenStore,
    private val ws: DeviceWebSocket,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        loadDevices()
        observeWs()
    }

    fun loadDevices() {
        _state.value = _state.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            try {
                val token = tokenStore.getAccessToken() ?: return@launch
                val devices = api.listDevices("Bearer $token")
                _state.value = _state.value.copy(devices = devices, isLoading = false)
                ws.connect("ws://118.31.225.109:8000", token)
            } catch (e: HttpException) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = "加载失败: HTTP ${e.code()}",
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = "网络错误: ${e.message}",
                )
            }
        }
    }

    fun control(entity: Entity, action: String) {
        viewModelScope.launch {
            try {
                val token = tokenStore.getAccessToken() ?: return@launch
                val actualAction = if (entity.domain == "button") {
                    "press"
                } else {
                    action
                }
                api.controlDevice(
                    "Bearer $token",
                    entity.entity_id,
                    DeviceActionRequest(entity.domain, actualAction, mapOf("entity_id" to entity.entity_id)),
                )
            } catch (_: Exception) {
                // ignore; state_change will update UI
            }
        }
    }

    fun togglePrimary(device: HomeDevice) {
        device.primaryEntity?.let { control(it, "toggle") }
    }

    fun logout(onDone: () -> Unit) {
        viewModelScope.launch {
            ws.disconnect()
            tokenStore.clear()
            onDone()
        }
    }

    private fun observeWs() {
        viewModelScope.launch {
            ws.events.collect { evt ->
                when (evt) {
                    is DeviceEvent.Connected ->
                        _state.value = _state.value.copy(wsConnected = true)
                    is DeviceEvent.Disconnected ->
                        _state.value = _state.value.copy(wsConnected = false)
                    is DeviceEvent.StateChanged -> {
                        _state.value = _state.value.copy(
                            devices = _state.value.devices.map { d ->
                                val updatedEntities = d.entities.map { e ->
                                    if (e.entity_id == evt.entityId) {
                                        e.copy(state = evt.state, attributes = evt.attributes)
                                    } else e
                                }
                                d.copy(entities = updatedEntities)
                            }
                        )
                    }
                    DeviceEvent.Refresh -> {
                        // Refetch full list on any refresh signal
                        loadDevices()
                    }
                    is DeviceEvent.DeviceRemoved -> {
                        _state.value = _state.value.copy(
                            devices = _state.value.devices.filterNot {
                                false  // todo: 用 entityId 过滤；现在简单 refresh
                            }
                        )
                    }
                }
            }
        }
    }
}
