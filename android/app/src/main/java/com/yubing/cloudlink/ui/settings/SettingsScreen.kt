package com.yubing.cloudlink.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yubing.cloudlink.data.api.ApiService
import com.yubing.cloudlink.data.local.HiddenDevicesStore
import com.yubing.cloudlink.data.local.TokenStore
import com.yubing.cloudlink.data.model.HomeDevice
import com.yubing.cloudlink.mcp.CloudLinkMcpService
import com.yubing.cloudlink.mcp.CloudLinkMcpServer
import com.yubing.cloudlink.mcp.McpProbe
import com.yubing.cloudlink.mcp.McpRunState
import com.yubing.cloudlink.mcp.McpServiceStarter
import com.yubing.cloudlink.mcp.McpStatus
import com.yubing.cloudlink.mcp.McpStatusTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    @ApplicationContext private val appContext: Context,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    /** MCP server 自报的状态（进程内直读，实时）。 */
    val mcpStatus: StateFlow<McpStatus> = McpStatusTracker.status

    /**
     * 真实连一次回环端口的结果。自报状态有可能过期（比如服务被系统杀掉），
     * 所以 UI 里两者不一致时以这个为准。
     */
    private val _probe = MutableStateFlow<Boolean?>(null)
    val probe: StateFlow<Boolean?> = _probe.asStateFlow()

    init {
        load()
        probeMcp()
    }

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

    fun probeMcp() {
        viewModelScope.launch {
            val s = McpStatusTracker.status.value
            _probe.value = McpProbe.isListening(s.host, s.port)
        }
    }

    /**
     * 重启本地 MCP 服务。
     *
     * 端口是写死的 bind 行为：stopService 让 server socket 关掉，再 start 重新 bind。
     * 中间留出一点时间等端口释放，否则刚起的 server 又会撞上 TIME_WAIT。
     */
    fun restartMcp() {
        viewModelScope.launch {
            _probe.value = null
            appContext.stopService(Intent(appContext, CloudLinkMcpService::class.java))
            delay(400)
            McpServiceStarter.start(appContext)
            delay(800)
            probeMcp()
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
    val status by viewModel.mcpStatus.collectAsStateWithLifecycle()
    val reachable by viewModel.probe.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            McpServerCard(
                status = status,
                reachable = reachable,
                onRestart = viewModel::restartMcp,
                onProbe = viewModel::probeMcp,
            )
            HorizontalDivider()
            // 两个批量按钮留在各自的区块里：放到顶栏会和上面的 MCP 卡片混在一起，
            // 看不出它们只作用于设备列表。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "设备显示设置",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).padding(start = 16.dp),
                )
                TextButton(onClick = viewModel::showAll) { Text("全显示") }
                TextButton(onClick = viewModel::hideAll) { Text("全隐藏") }
            }
            Text(
                text = "勾选要在首页显示的设备。已取消勾选的设备不会从云端删除，只是不再出现在首页。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
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
                        modifier = Modifier.weight(1f),
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

// --------------------------------------------------------------------- MCP

private val GreenOk = Color(0xFF2E7D32)
private val AmberStarting = Color(0xFFB26A00)

@Composable
private fun McpServerCard(
    status: McpStatus,
    reachable: Boolean?,
    onRestart: () -> Unit,
    onProbe: () -> Unit,
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "本地 MCP 服务",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onProbe) {
                Icon(Icons.Default.Refresh, contentDescription = "重新检测")
            }
        }

        Spacer(Modifier.height(6.dp))

        // 端口与 URL：这是要填到 PalmClaw 里的那一串。
        val context = LocalContext.current
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = status.endpoint,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                ),
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("mcp", status.endpoint))
                    android.widget.Toast.makeText(context, "已复制端点地址", android.widget.Toast.LENGTH_SHORT).show()
                },
            ) { Text("复制") }
        }

        Spacer(Modifier.height(8.dp))

        StatusChip(status = status, reachable = reachable)

        Spacer(Modifier.height(10.dp))

        if (status.state == McpRunState.PORT_IN_USE && status.lastError != null) {
            Text(
                text = status.lastError!!,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
        }

        InfoRow("协议版本", CloudLinkMcpServer.LATEST_PROTOCOL)
        InfoRow("暴露工具", "${CloudLinkMcpServer.TOOL_COUNT} 个")
        Text(
            text = CloudLinkMcpServer.TOOL_NAMES,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        // 运行中时这一行每秒自刷新；否则不空转。
        val since = status.sinceMs
        if (status.state == McpRunState.RUNNING && since != null) {
            var now by remember(since) { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(since) {
                while (true) {
                    delay(1000)
                    now = System.currentTimeMillis()
                }
            }
            InfoRow("运行时长", durationText(now - since))
        } else {
            InfoRow("运行时长", "—")
        }
        InfoRow("已调用", "${status.toolCalls} 次")
        lastCallSummary(status)?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
        // 端口被占用的原因在上面的红字里已经说过，不重复。
        status.lastError?.takeIf { status.state != McpRunState.PORT_IN_USE }?.let {
            InfoRow("最近失败", it)
        }
        InfoRow("活跃会话", "${status.activeSessions}")
        InfoRow("端口", portLine(status, reachable))

        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onRestart) { Text("重启 MCP 服务") }
    }
}

@Composable
private fun StatusChip(status: McpStatus, reachable: Boolean?) {
    // 自报 RUNNING 但端口连不上 = 服务其实已经没了（进程被回收等情况），以探测为准。
    val effectivelyRunning = status.state == McpRunState.RUNNING && reachable != false
    val (text, color) = when {
        effectivelyRunning -> "运行中" to GreenOk
        status.state == McpRunState.RUNNING -> "已失效（进程不在）" to MaterialTheme.colorScheme.error
        status.state == McpRunState.STARTING -> "启动中…" to AmberStarting
        status.state == McpRunState.PORT_IN_USE -> "端口被占用" to MaterialTheme.colorScheme.error
        else -> "未运行" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.12f),
        contentColor = color,
    ) {
        Text(
            text = "● $text",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

private fun durationText(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> "${h} 小时 ${m} 分"
        m > 0 -> "${m} 分 ${s} 秒"
        else -> "${s} 秒"
    }
}

/**
 * 端口那一行。
 *
 * 回环探测只证明「有人在听」，不证明「听的是我们」——端口被别的进程占着时
 * 探测同样是通的。所以只有在自报 RUNNING 时探测结果才有意义（用于发现
 * 「状态还写着运行中、端口其实已经没了」），其它状态下不亮这个值。
 */
private fun portLine(status: McpStatus, reachable: Boolean?): String {
    val base = "${status.host}:${status.port}"
    return when (status.state) {
        McpRunState.RUNNING -> base + " · 回环检测：" + when (reachable) {
            true -> "可达"
            false -> "不可达（服务可能已失效）"
            null -> "检测中…"
        }
        McpRunState.PORT_IN_USE -> base + " · 被其它进程占用"
        McpRunState.STARTING -> base + " · 绑定中…"
        McpRunState.STOPPED -> base + " · 未监听"
    }
}

/** 最近一次调用的工具与时刻，缩进的小字，避免和「已调用」挤在同一行。 */
private fun lastCallSummary(status: McpStatus): String? {
    if (status.toolCalls == 0L) return null
    val time = status.lastCallMs?.let { SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it)) }
    return listOfNotNull(status.lastTool, time).joinToString(" · ").ifBlank { null }
}

// --------------------------------------------------------------- 设备可见性

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
