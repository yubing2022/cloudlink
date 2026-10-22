package com.yubing.cloudlink.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yubing.cloudlink.data.api.ApiService
import com.yubing.cloudlink.data.local.TokenStore
import com.yubing.cloudlink.data.model.DeviceActionRequest
import com.yubing.cloudlink.data.model.Entity
import com.yubing.cloudlink.data.model.HomeDevice
import com.yubing.cloudlink.data.model.quickActionFor
import com.yubing.cloudlink.data.ws.DeviceEvent
import com.yubing.cloudlink.data.ws.DeviceWebSocket
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
        applyOptimistic(entity.entity_id, predictedStateFor(entity, action))
        // 与首页一致：开灯要带 brightness，否则会恢复上次那个可能是 1 的亮度
        val qa = quickActionFor(entity.domain)
        val extra = if (action == qa.turnOnService) qa.turnOnData(entity) else emptyMap()
        viewModelScope.launch {
            try {
                val token = tokenStore.getAccessToken() ?: return@launch
                val actualAction = if (entity.domain == "button") "press" else action
                api.controlDevice(
                    "Bearer $token",
                    entity.entity_id,
                    DeviceActionRequest(
                        entity.domain,
                        actualAction,
                        mapOf("entity_id" to entity.entity_id) + extra,
                    ),
                )
            } catch (_: Exception) {
                // ignore; WS state_change will update
            }
        }
    }

    /**
     * Set absolute brightness 0-255 on a dimmable light. Brightness==0 is
     * a no-op; for "off" use [control] with action="turn_off".
     *
     * Optimistically updates the entity's brightness attribute so the
     * slider doesn't lag. Also flips state to "on" if we're bringing it
     * from 0 → non-zero.
     */
    fun setBrightness(entity: Entity, brightness: Int) {
        val level = brightness.coerceIn(0, 255)
        // Optimistic update
        val dev = _state.value.device ?: return
        _state.value = _state.value.copy(
            device = dev.copy(
                entities = dev.entities.map { e ->
                    if (e.entity_id != entity.entity_id) return@map e
                    val newAttrs = e.attributes.toMutableMap().apply {
                        this["brightness"] = level
                    }
                    val newState = if (level == 0) "off" else "on"
                    e.copy(state = newState, attributes = newAttrs)
                }
            )
        )
        viewModelScope.launch {
            try {
                val token = tokenStore.getAccessToken() ?: return@launch
                api.controlDevice(
                    "Bearer $token",
                    entity.entity_id,
                    DeviceActionRequest(
                        domain = "light",
                        service = "turn_on",
                        data = mapOf(
                            "entity_id" to entity.entity_id,
                            "brightness" to level,
                        ),
                    ),
                )
            } catch (_: Exception) {
                // WS state_change will reconcile
            }
        }
    }

    private fun predictedStateFor(entity: Entity, action: String): String {
        val current = _state.value.device?.entities
            ?.firstOrNull { it.entity_id == entity.entity_id }
            ?.state
        return when (action) {
            "turn_on" -> "on"
            "turn_off" -> "off"
            "open_cover", "open_valve" -> "open"
            "close_cover", "close_valve" -> "closed"
            "unlock" -> "unlocked"
            "lock" -> "locked"
            "press" -> ""
            "toggle" -> {
                val isOn = quickActionFor(entity.domain).isOn(current ?: "")
                if (isOn) "off" else "on"
            }
            else -> ""
        }
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
