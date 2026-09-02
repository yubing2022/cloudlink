package com.yourname.cloudlink.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yourname.cloudlink.data.api.ApiService
import com.yourname.cloudlink.data.local.HiddenDevicesStore
import com.yourname.cloudlink.data.local.TokenStore
import com.yourname.cloudlink.data.model.HomeDevice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException
import javax.inject.Inject

data class SettingsUiState(
    val devices: List<HomeDevice> = emptyList(),
    val hidden: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val api: ApiService,
    private val tokenStore: TokenStore,
    private val hiddenStore: HiddenDevicesStore,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.value = _state.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            try {
                val token = tokenStore.getAccessToken() ?: return@launch
                val devices = api.listDevices("Bearer $token")
                val hidden = hiddenStore.getHidden()
                _state.value = _state.value.copy(
                    devices = devices,
                    hidden = hidden,
                    isLoading = false,
                )
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

    fun toggleVisibility(deviceId: String) {
        viewModelScope.launch {
            hiddenStore.toggle(deviceId)
            _state.value = _state.value.copy(hidden = hiddenStore.getHidden())
        }
    }

    fun showAll() {
        viewModelScope.launch {
            hiddenStore.setHidden(emptySet())
            _state.value = _state.value.copy(hidden = emptySet())
        }
    }

    fun hideAll() {
        viewModelScope.launch {
            val allIds = _state.value.devices.map { it.haDeviceId }.toSet()
            hiddenStore.setHidden(allIds)
            _state.value = _state.value.copy(hidden = allIds)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设备显示设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::showAll) { Text("全显示") }
                    TextButton(onClick = viewModel::hideAll) { Text("全隐藏") }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text(
                text = "勾选要在首页显示的设备。已取消勾选的设备不会从云端删除，只是不再出现在首页。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            HorizontalDivider()
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(state.error!!, color = MaterialTheme.colorScheme.error)
                }
                state.devices.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("还没有设备")
                }
                else -> {
                    val byArea = state.devices.groupBy { it.area ?: "未分组" }
                    val total = state.devices.size
                    val hiddenInList = state.hidden.count { id ->
                        state.devices.any { it.haDeviceId == id }
                    }
                    val visible = total - hiddenInList
                    Text(
                        text = "显示 $visible / $total 个设备",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        byArea.forEach { (area, devs) ->
                            item(key = "area_$area") {
                                Text(
                                    text = "📍 $area",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                            items(devs, key = { it.haDeviceId }) { device ->
                                DeviceVisibilityRow(
                                    device = device,
                                    isVisible = device.haDeviceId !in state.hidden,
                                    onToggle = { viewModel.toggleVisibility(device.haDeviceId) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceVisibilityRow(
    device: HomeDevice,
    isVisible: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = isVisible, onCheckedChange = { onToggle() })
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
            Text(device.name, style = MaterialTheme.typography.bodyMedium)
            val subtitle = listOfNotNull(device.manufacturer, device.model)
                .joinToString(" • ")
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "${device.entities.size} entities",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
