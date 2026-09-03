package com.yourname.cloudlink.ui.detail

import androidx.lifecycle.SavedStateHandle
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

data class DeviceDetailUiState(
    val device: HomeDevice? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class DeviceDetailViewModel @Inject constructor(
    private val api: ApiService,
    private val tokenStore: TokenStore,
    private val ws: DeviceWebSocket,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(DeviceDetailUiState())
    val state: StateFlow<DeviceDetailUiState> = _state.asStateFlow()

    val haDeviceId: String = savedStateHandle.get<String>("haDeviceId").orEmpty()

    init {
        load()
        observeWs()
    }

    fun load() {
        _state.value = _state.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            try {
                val token = tokenStore.getAccessToken() ?: return@launch
                val devices = api.listDevices("Bearer $token")
                val found = devices.firstOrNull { it.haDeviceId == haDeviceId }
                if (found == null) {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = "找不到设备 (id=$haDeviceId)",
                    )
                } else {
                    _state.value = _state.value.copy(
                        device = found,
                        isLoading = false,
                    )
                }
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
        // Optimistic update
        applyOptimistic(entity.entity_id, predictedStateFor(entity.entity_id, action))
        viewModelScope.launch {
            try {
                val token = tokenStore.getAccessToken() ?: return@launch
                val actualAction = if (entity.domain == "button") "press" else action
                api.controlDevice(
                    "Bearer $token",
                    entity.entity_id,
                    DeviceActionRequest(entity.domain, actualAction, mapOf("entity_id" to entity.entity_id)),
                )
            } catch (_: Exception) {
                // ignore; WS state_change will update
            }
        }
    }

    private fun predictedStateFor(entityId: String, action: String): String {
        if (action == "turn_on" || action == "open") return "on"
        if (action == "turn_off" || action == "close") return "off"
        if (action == "press") return ""
        if (action == "toggle") {
            val current = _state.value.device?.entities
                ?.firstOrNull { it.entity_id == entityId }
                ?.state
            return if (current in ON_STATES) "off" else "on"
        }
        return ""
    }

    private fun applyOptimistic(entityId: String, newState: String) {
        if (newState.isEmpty()) return
        val dev = _state.value.device ?: return
        _state.value = _state.value.copy(
            device = dev.copy(
                entities = dev.entities.map { e ->
                    if (e.entity_id == entityId) e.copy(state = newState) else e
                }
            )
        )
    }

    private fun observeWs() {
        viewModelScope.launch {
            ws.events.collect { evt ->
                if (evt is DeviceEvent.StateChanged) {
                    val dev = _state.value.device ?: return@collect
                    val updated = dev.entities.any { it.entity_id == evt.entityId }
                    if (!updated) return@collect
                    _state.value = _state.value.copy(
                        device = dev.copy(
                            entities = dev.entities.map { e ->
                                if (e.entity_id == evt.entityId) {
                                    e.copy(state = evt.state, attributes = evt.attributes)
                                } else e
                            }
                        )
                    )
                }
            }
        }
    }
}

private val ON_STATES = setOf("on", "playing", "home", "open", "unlocked", "active")
