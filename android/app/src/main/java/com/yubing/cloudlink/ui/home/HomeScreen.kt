package com.yubing.cloudlink.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ToggleOn
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yubing.cloudlink.data.icon.DeviceIconImage
import com.yubing.cloudlink.data.icon.categoryIcon
import com.yubing.cloudlink.data.model.Entity
import com.yubing.cloudlink.data.model.HomeDevice
import com.yubing.cloudlink.data.model.QuickActionKind
import com.yubing.cloudlink.data.model.quickActionFor
import com.yubing.cloudlink.ui.components.EmptyState
import com.yubing.cloudlink.ui.components.ErrorState
import com.yubing.cloudlink.ui.components.LoadingState
import com.yubing.cloudlink.ui.theme.statusColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onLogout: () -> Unit,
    onSettings: () -> Unit = {},
    onDeviceClick: (HomeDevice) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "我的设备",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = subtitle(state),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                actions = {
                    var menuExpanded by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("设置") },
                            onClick = {
                                menuExpanded = false
                                onSettings()
                            },
                            leadingIcon = {
                                Icon(Icons.Outlined.Settings, contentDescription = null)
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        // 下拉刷新：用户的第一反应是下拉，不用再去顶栏找图标。
        // 首页背景用柔和青蓝渐变（替代纯白），让纯白卡片浮于其上形成层次，
        // 参考华为「图片背景 + 白卡」的高级观感。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(homeBackgroundBrush()),
        ) {
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::loadDevices,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.isLoading && state.devices.isEmpty() -> LoadingState()
                state.error != null && state.devices.isEmpty() ->
                    ErrorState(message = state.error!!, onRetry = viewModel::loadDevices)
                state.devices.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.DevicesOther,
                    title = "还没有设备",
                    subtitle = "在 Home Assistant 里接入设备后，它们会出现在这里",
                    actionLabel = "刷新",
                    onAction = viewModel::loadDevices,
                )
                state.visibleDevices.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.VisibilityOff,
                    title = "所有设备都已被隐藏",
                    subtitle = "在设置里可以选择要显示在首页的设备",
                    actionLabel = "打开设置",
                    onAction = onSettings,
                )
                else -> DeviceGrid(
                    devices = state.visibleDevices,
                    onDeviceClick = onDeviceClick,
                    onTogglePrimary = viewModel::togglePrimary,
                )
            }
        }
        }
    }
}

private fun subtitle(state: com.yubing.cloudlink.ui.home.HomeUiState): String {
    val count = state.visibleDevices.size
    val base = if (count == 0) "暂无设备" else "$count 台设备"
    // 这里的「已连接服务器」是**状态**（WebSocket 已连接），直白表达链路已建立；
    // WS 未连接时只显示设备数，不谎报已连接。
    return if (state.wsConnected) "$base · 已连接服务器" else base
}

/** 首页背景渐变：浅色用柔和青蓝、深色用深青，让纯白卡片浮于其上形成层次。 */
@Composable
private fun homeBackgroundBrush(): Brush {
    val isDark = isSystemInDarkTheme()
    return if (isDark) {
        Brush.verticalGradient(listOf(Color(0xFF0B0E10), Color(0xFF101416)))
    } else {
        Brush.verticalGradient(listOf(Color(0xFFDCEEF8), Color(0xFFF2F7FB)))
    }
}

@Composable
private fun DeviceGrid(
    devices: List<HomeDevice>,
    onDeviceClick: (HomeDevice) -> Unit,
    onTogglePrimary: (HomeDevice) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        // 「留白」的三个维度分别对应下面三个值，别再混为一谈：
        //   horizontalArrangement = 卡片之间的左右间距
        //   verticalArrangement   = 卡片之间的上下间距
        //   contentPadding.horizontal = 卡片与屏幕左右边缘的间距
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(devices.filter { it.entities.isNotEmpty() }, key = { it.haDeviceId }) { device ->
            DeviceCard(
                device = device,
                onClick = { onDeviceClick(device) },
                onTogglePrimary = { onTogglePrimary(device) },
            )
        }
    }
}

@Composable
private fun DeviceCard(
    device: HomeDevice,
    onClick: () -> Unit,
    onTogglePrimary: () -> Unit,
) {
    val primary = device.primaryEntity
    val isOn = primary != null && quickActionFor(primary.domain).isOn(primary.state)
    val isOffline = device.entities.isNotEmpty() &&
        device.entities.all { it.state == "unavailable" || it.state == "unknown" }

    // 浅色用纯白卡片、深色用 surfaceContainer，浮于首页渐变背景之上形成层次
    // （参考华为「图片背景 + 白卡」），而不是浅灰卡贴白底那样发灰发空。
    val isDark = isSystemInDarkTheme()
    val cardColor = if (isDark) MaterialTheme.colorScheme.surfaceContainer
    else MaterialTheme.colorScheme.surfaceContainerLowest

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(containerColor = cardColor),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, top = 10.dp, end = 10.dp, bottom = 6.dp)
                .height(90.dp),
        ) {
            // 一级：设备名称（紧凑字号，避免大留白显空）
            Text(
                text = device.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            // 二级：状态 · 房间（小灰字，圆点引导）
            val area = device.area?.takeIf { it.isNotBlank() } ?: "未分组"
            val label = statusLabel(device)
            val dotColor = when (label) {
                "离线", "关闭" -> MaterialTheme.statusColors.offline
                else -> MaterialTheme.statusColors.online
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    // 用竖线分隔（与华为智慧生活「在线 | 客厅」一致），比圆点更清晰
                    text = "$label | $area",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.weight(1f))

            // 底部：图标（左，透明背景直接显示） + 快捷按钮（右下）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DeviceGlyph(device = device, isOn = isOn, isOffline = isOffline)
                Spacer(Modifier.weight(1f))
                QuickActionButton(
                    primary = primary,
                    isOn = isOn,
                    onToggle = onTogglePrimary,
                )
            }
        }
    }
}

/**
 * 设备图标：透明背景直接显示（不套圆形灰底，参考华为产品图标），
 * 靠 tint 区分开启(主色)/关闭(中性)/离线(更灰)。
 */
@Composable
private fun DeviceGlyph(
    device: HomeDevice,
    isOn: Boolean,
    isOffline: Boolean,
) {
    val tint = when {
        isOffline -> MaterialTheme.colorScheme.outlineVariant
        isOn -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val model = device.model
    if (model.isNullOrBlank()) {
        Icon(
            imageVector = device.categoryIcon(),
            contentDescription = null,
            modifier = Modifier.size(50.dp),
            tint = tint,
        )
    } else {
        DeviceIconImage(
            model = model,
            size = 50.dp,
            fallback = device.categoryIcon(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
        )
    }
}

/**
 * 快捷操作按钮。
 *
 * 图标**只表达动作**，不表达品类：电源键=开/关切换，拨杆=开合行程，
 * 播放键=执行一次。只读实体（sensor 等）不渲染按钮 —— 否则点下去发的是
 * `sensor.toggle`，后端只会回 service_not_found。
 */
@Composable
private fun QuickActionButton(
    primary: Entity?,
    isOn: Boolean,
    onToggle: () -> Unit,
) {
    if (primary == null) return
    val action = quickActionFor(primary.domain)
    val icon = when (action.kind) {
        QuickActionKind.TOGGLE -> Icons.Filled.PowerSettingsNew
        QuickActionKind.POSITION -> Icons.Outlined.ToggleOn
        QuickActionKind.MOMENTARY -> Icons.Filled.PlayArrow
        QuickActionKind.READ_ONLY -> return
    }
    // 瞬时动作没有「开/关」，统一按可执行处理，避免灰色按钮像是不可点
    val active = action.kind == QuickActionKind.MOMENTARY || isOn
    val tint = if (active) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurfaceVariant
    val container = if (active) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceContainerHighest

    Box(
        modifier = Modifier
            .size(QUICK_ACTION_SIZE)
            .clip(CircleShape)
            .background(container)
            .clickable { onToggle() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = when (action.kind) {
                QuickActionKind.MOMENTARY -> "执行"
                else -> if (isOn) "关闭" else "打开"
            },
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** 快捷按钮的直径，同时也是底部操作行的固定高度。 */
private val QUICK_ACTION_SIZE = 30.dp

/** 卡片底部那行状态文案。整台设备都 unavailable 才算离线。 */
private fun statusLabel(device: HomeDevice): String {
    val primary = device.primaryEntity
    if (device.entities.all { it.state == "unavailable" || it.state == "unknown" }) return "离线"
    if (primary == null) return "在线"
    val action = quickActionFor(primary.domain)
    return when {
        action.kind == QuickActionKind.MOMENTARY -> "可触发"
        action.kind == QuickActionKind.READ_ONLY -> "在线"
        action.isOn(primary.state) -> "开启"
        else -> "关闭"
    }
}
