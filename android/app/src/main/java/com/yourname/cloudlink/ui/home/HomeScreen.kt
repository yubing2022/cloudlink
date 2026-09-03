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
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DeviceHub
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.WindPower
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yourname.cloudlink.data.icon.DeviceIconImage
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

    // fillMaxHeight() makes every card in the grid row match the tallest
    // sibling, so two-line device names don't produce uneven layouts.
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .clickable { onClick() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 14.dp, vertical = 14.dp),
        ) {
            // Title + status/area line (华为样式: 设备名 + "状态 | 位置")
            Text(
                text = device.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
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
            // Spacer pushes the icon down to centre the visual weight in the
            // card regardless of whether the title area is taller.
            Spacer(Modifier.weight(1f))
            // Big device icon, centred
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                DeviceIconLarge(device = device)
            }
            Spacer(Modifier.weight(1f))
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
    val bg = if (onState) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        // Try miot-spec.com icon first; fall back to per-domain Material icon
        // (iconFor() picks a sensible default based on primary entity domain).
        val url = device.model  // model e.g. "yeelink.light.mbulb3"
        if (url.isNullOrBlank()) {
            Icon(
                imageVector = iconFor(device, primary),
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = if (onState) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            DeviceIconImage(
                model = url,
                size = 56.dp,
                fallback = iconFor(device, primary),
                contentDescription = null,
                contentScale = ContentScale.Fit,
            )
        }
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
        "light" -> Icons.Filled.Lightbulb
        "switch" -> Icons.Filled.ToggleOn
        "fan" -> Icons.Filled.WindPower
        "media_player" -> Icons.Filled.PowerSettingsNew
        "button" -> Icons.Filled.PlayArrow
        "cover" -> Icons.Filled.Bolt
        "lock" -> Icons.Filled.Lock
        else -> Icons.Filled.Bolt
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
    // Sensor domains don't tell us what is being measured, so the device
    // name is the best hint. Common Chinese patterns covered below.
    val name = device.name
    return when (domain) {
        "light" -> Icons.Filled.Lightbulb
        "switch" -> Icons.Filled.ToggleOn
        "fan" -> Icons.Filled.WindPower
        "media_player" -> Icons.Filled.Speaker
        "button" -> Icons.Filled.PlayArrow
        "cover" -> if (name.contains("窗", ignoreCase = true)) Icons.Filled.WifiOff
                    else Icons.Filled.Bolt
        "lock" -> Icons.Filled.Lock
        "climate" -> Icons.Filled.DeviceThermostat
        "sensor" -> when {
            name.contains("温", ignoreCase = true) -> Icons.Filled.DeviceThermostat
            name.contains("湿", ignoreCase = true) -> Icons.Filled.AcUnit
            name.contains("电", ignoreCase = true) || name.contains("功率", ignoreCase = true) -> Icons.Filled.Bolt
            name.contains("打印", ignoreCase = true) -> Icons.Filled.DeviceHub
            name.contains("备份", ignoreCase = true) -> Icons.Filled.Router
            else -> Icons.Filled.Sensors
        }
        "binary_sensor" -> Icons.Filled.Sensors
        "humidifier" -> Icons.Filled.AcUnit
        "vacuum" -> Icons.Filled.WindPower
        "water_heater" -> Icons.Filled.DeviceThermostat
        else -> Icons.Filled.DeviceHub
    }
}

private fun statusLabel(entity: Entity): String = when {
    entity.domain == "button" -> "可触发"
    entity.state == "unavailable" || entity.state == "unknown" -> "离线"
    entity.state in ON_STATES -> "在线"
    entity.domain in listOf("light", "switch", "fan", "media_player", "cover", "lock") -> "关闭"
    else -> "在线"
}
