package com.yubing.cloudlink.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

private fun subtitle(state: com.yubing.cloudlink.ui.home.HomeUiState): String {
    val count = state.visibleDevices.size
    val base = if (count == 0) "暂无设备" else "$count 台设备"
    // 这里的「已连接服务器」是**状态**（WebSocket 已连接），直白表达链路已建立；
    // WS 未连接时只显示设备数，不谎报已连接。
    return if (state.wsConnected) "$base · 已连接服务器" else base
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
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
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

    // 卡片底色统一（克制，像华为那样整卡一个色），靠图标徽标 + 状态点 + 快捷按钮
    // 的局部高亮来区分开启/关闭/离线，而不是把整卡染成主色——后者看着显糙。
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .height(156.dp),
        ) {
            // 一级：设备名称（最大、加粗）
            Text(
                text = device.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            // 二级：状态 · 房间（小灰字，圆点引导）。华为用「在线 | 客厅」，
            // 我们换成圆点 + 「状态 · 房间」，更克制也更好读。
            val area = device.area?.takeIf { it.isNotBlank() } ?: "未分组"
            val label = statusLabel(device)
            val dotColor = when (label) {
                "离线", "关闭" -> MaterialTheme.statusColors.offline
                else -> MaterialTheme.statusColors.online
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "$label · $area",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.weight(1f))

            // 底部：图标徽标（左） + 快捷按钮（右下）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DeviceIconBadge(device = device, isOn = isOn, isOffline = isOffline)
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
 * 设备图标徽标：圆形浅色容器 + 居中图标，参考华为卡片里「产品小场景」的观感。
 * 开启→主色浅底 + 主色图标；关闭/中性→浅灰底 + 中性色图标；离线→更灰。
 */
@Composable
private fun DeviceIconBadge(
    device: HomeDevice,
    isOn: Boolean,
    isOffline: Boolean,
) {
    val containerColor: Color
    val tint: Color
    when {
        isOffline -> {
            containerColor = MaterialTheme.colorScheme.surfaceContainer
            tint = MaterialTheme.colorScheme.outlineVariant
        }
        isOn -> {
            // 图标底与关闭态统一（surfaceContainerHighest），靠 tint 用主色来点亮，
            // 而不是整块浅蓝底——更接近华为那种「统一底 + 局部亮」的克制观感。
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
            tint = MaterialTheme.colorScheme.primary
        }
        else -> {
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        val model = device.model
        if (model.isNullOrBlank()) {
            Icon(
                imageVector = device.categoryIcon(),
                contentDescription = null,
                modifier = Modifier.size(30.dp),
                tint = tint,
            )
        } else {
            DeviceIconImage(
                model = model,
                size = 48.dp,
                fallback = device.categoryIcon(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
            )
        }
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
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 快捷按钮的直径，同时也是底部操作行的固定高度。 */
private val QUICK_ACTION_SIZE = 40.dp

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
