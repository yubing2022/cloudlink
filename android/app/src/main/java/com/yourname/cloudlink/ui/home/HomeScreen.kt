package com.yourname.cloudlink.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yourname.cloudlink.data.model.Entity
import com.yourname.cloudlink.data.model.HomeDevice

private val ON_STATES = setOf("on", "playing", "home", "open", "unlocked", "active")

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
                        Text("我的设备")
                        if (state.wsConnected) {
                            Text("实时同步中", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.MoreVert, contentDescription = "设置")
                    }
                    if (state.isRefreshing) {
                        Box(
                            modifier = Modifier.size(40.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    } else {
                        IconButton(onClick = viewModel::loadDevices) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                    }
                    IconButton(onClick = { viewModel.logout(onLogout) }) {
                        Icon(Icons.Filled.ExitToApp, contentDescription = "退出")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading && state.devices.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.error != null && state.devices.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(state.error!!, color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = viewModel::loadDevices) { Text("重试") }
                        }
                    }
                state.devices.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("还没有设备")
                    }
                state.visibleDevices.isEmpty() && state.devices.isNotEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("所有设备都已被隐藏")
                            Text(
                                "点击右上角 ⋮ → 设置 可以选择要显示的设备",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = onSettings) { Text("打开设置") }
                        }
                    }
                else ->
                    DeviceGrid(
                        devices = state.visibleDevices,
                        onDeviceClick = onDeviceClick,
                        onTogglePrimary = viewModel::togglePrimary,
                    )
            }
        }
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
    val status = primary?.let { statusLabel(it) } ?: "离线"
    val location = device.area?.takeIf { it.isNotBlank() } ?: "未分组"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp)) {
            // Title + status/area line (华为样式: 设备名 + "状态 | 位置")
            Text(
                text = device.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "  |  ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = location,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            // Big device icon, centered
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                DeviceIconLarge(device = device)
            }
            // Bottom row: action button aligned to the end
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                QuickActionButton(
                    primary = primary,
                    onToggle = onTogglePrimary,
                )
            }
        }
    }
}

@Composable
private fun DeviceIconLarge(device: HomeDevice) {
    val primary = device.primaryEntity
    val onState = primary?.state in ON_STATES
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(
                if (onState) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = iconFor(device, primary),
            contentDescription = null,
            modifier = Modifier.size(36.dp),
            tint = if (onState) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun QuickActionButton(
    primary: Entity?,
    onToggle: () -> Unit,
) {
    if (primary == null) return
    val isOn = primary.state in ON_STATES
    val icon = when (primary.domain) {
        "light", "switch", "fan" -> Icons.Filled.Check
        "media_player" -> Icons.Filled.PlayArrow
        "button" -> Icons.Filled.PlayArrow
        "cover" -> Icons.Filled.Settings
        "lock" -> Icons.Filled.Settings
        else -> Icons.Filled.Settings
    }
    val tint = if (isOn) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant
    val container = if (isOn) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant

    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(container)
            .clickable { onToggle() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = "操作",
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

private fun iconFor(device: HomeDevice, primary: Entity?): ImageVector {
    val domain = primary?.domain ?: ""
    return when (domain) {
        "light" -> Icons.Filled.Star
        "switch" -> Icons.Filled.Check
        "fan" -> Icons.Filled.Settings
        "media_player" -> Icons.Filled.Notifications
        "button" -> Icons.Filled.PlayArrow
        "cover" -> Icons.Filled.Settings
        "lock" -> Icons.Filled.Settings
        "sensor" -> if (device.name.contains("温", ignoreCase = true)) Icons.Filled.Settings
                     else Icons.Filled.Settings
        "binary_sensor" -> Icons.Filled.Settings
        "climate" -> Icons.Filled.Settings
        else -> Icons.Filled.Close
    }
}

private fun statusLabel(entity: Entity): String = when {
    entity.domain == "button" -> "可触发"
    entity.state == "unavailable" || entity.state == "unknown" -> "离线"
    entity.state in ON_STATES -> "在线"
    entity.domain in listOf("light", "switch", "fan", "media_player", "cover", "lock") -> "关闭"
    else -> "在线"
}
