package com.yourname.cloudlink.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.cloudlink.data.api.ApiService
import com.yourname.cloudlink.data.local.HiddenDevicesStore
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
    /** Raw list from cloud (every device the user owns). */
    val devices: List<HomeDevice> = emptyList(),
    /** Subset of `devices` after applying the user-configured device-hide filter. */
    val visibleDevices: List<HomeDevice> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val wsConnected: Boolean = false,
)

private val ON_STATES = setOf("on", "playing", "home", "open", "unlocked", "active")

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val api: ApiService,
    private val tokenStore: TokenStore,
    private val ws: DeviceWebSocket,
    private val hiddenDeviceIds: HiddenDevicesStore,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val _hiddenIds = MutableStateFlow<Set<String>>(emptySet())

    init {
        // Observe hidden-id changes persistently so the home list refreshes
        // automatically after the user edits the filter in Settings.
        viewModelScope.launch {
            hiddenDeviceIds.hidden.collect { newIds ->
                _hiddenIds.value = newIds
                _state.value = _state.value.copy(
                    visibleDevices = _state.value.devices
                        .filterNot { it.haDeviceId in newIds },
                )
            }
        }
        loadDevices()
        observeWs()
    }

    fun loadDevices() {
        _state.value = _state.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            try {
                val token = tokenStore.getAccessToken() ?: return@launch
                val rawDevices = api.listDevices("Bearer $token")
                _state.value = _state.value.copy(
                    devices = rawDevices,
                    visibleDevices = rawDevices.filterNot { it.haDeviceId in _hiddenIds.value },
                    isLoading = false,
                )
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
        // Optimistic UI update so the Switch reflects the action immediately.
        // The WS state_change that follows will confirm or correct.
        applyOptimisticState(entity.entity_id, predictedStateFor(entity.entity_id, action))

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
                // ignore; WS state_change will eventually update UI
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

    private fun predictedStateFor(entityId: String, action: String): String {
        if (action == "turn_on" || action == "open") return "on"
        if (action == "turn_off" || action == "close") return "off"
        if (action == "press") return ""  // buttons have no togglable state
        if (action == "toggle") {
            val current = _state.value.devices
                .flatMap { it.entities }
                .firstOrNull { it.entity_id == entityId }
                ?.state
            return if (current in ON_STATES) "off" else "on"
        }
        return ""
    }

    private fun applyOptimisticState(entityId: String, newState: String) {
        if (newState.isEmpty()) return
        val newDevices = _state.value.devices.map { d ->
            d.copy(entities = d.entities.map { e ->
                if (e.entity_id == entityId) e.copy(state = newState) else e
            })
        }
        _state.value = _state.value.copy(
            devices = newDevices,
            visibleDevices = newDevices.filterNot { it.haDeviceId in _hiddenIds.value },
        )
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
                        val newDevices = _state.value.devices.map { d ->
                            val updatedEntities = d.entities.map { e ->
                                if (e.entity_id == evt.entityId) {
                                    e.copy(state = evt.state, attributes = evt.attributes)
                                } else e
                            }
                            d.copy(entities = updatedEntities)
                        }
                        _state.value = _state.value.copy(
                            devices = newDevices,
                            visibleDevices = newDevices.filterNot { it.haDeviceId in _hiddenIds.value },
                        )
                    }
                    DeviceEvent.Refresh -> loadDevices()
                    is DeviceEvent.DeviceRemoved -> {
                        // todo: 用 entityId 过滤；现在简单 refresh
                        loadDevices()
                    }
                }
            }
        }
    }
}
