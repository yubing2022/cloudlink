package com.yourname.cloudlink.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yourname.cloudlink.data.model.Entity
import com.yourname.cloudlink.data.model.HomeDevice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onLogout: () -> Unit,
    onSettings: () -> Unit = {},
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
                    // Refresh: while in flight, swap the static icon for a
                    // spinning indicator so the user gets clear visual
                    // feedback that something happened.
                    if (state.isRefreshing) {
                        androidx.compose.foundation.layout.Box(
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
                    DeviceList(
                        devices = state.visibleDevices,
                        onEntityAction = viewModel::control,
                        onDeviceToggle = viewModel::togglePrimary,
                    )
            }
        }
    }
}

@Composable
private fun DeviceList(
    devices: List<HomeDevice>,
    onEntityAction: (Entity, String) -> Unit,
    onDeviceToggle: (HomeDevice) -> Unit,
) {
    val byArea = remember(devices) {
        devices.groupBy { it.area ?: "未分组" }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
    ) {
        byArea.forEach { (area, devs) ->
            item(key = "area_$area") {
                Text(
                    text = "📍 $area",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            items(devs.filter { it.entities.isNotEmpty() }, key = { it.haDeviceId }) { device ->
                DeviceCard(
                    device = device,
                    onEntityAction = onEntityAction,
                    onTogglePrimary = { onDeviceToggle(device) },
                )
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: HomeDevice,
    onEntityAction: (Entity, String) -> Unit,
    onTogglePrimary: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header: device name + area + quick action
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Home,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val subtitle = listOfNotNull(device.manufacturer, device.model, device.area)
                        .joinToString(" • ")
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // Quick toggle: only show if there's a togglable primary entity
                device.primaryEntity?.let { primary ->
                    if (primary.domain in listOf("light", "switch", "fan", "media_player")) {
                        Switch(
                            checked = primary.state in listOf("on", "playing", "home", "open"),
                            onCheckedChange = { onTogglePrimary() },
                        )
                    }
                }
            }
            // Entities: all entities of this device as sub-controls
            if (device.entities.size > 1) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    device.entities.forEach { entity ->
                        EntityRow(
                            entity = entity,
                            onAction = onEntityAction,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EntityRow(
    entity: Entity,
    onAction: (Entity, String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = entityIcon(entity.domain),
            fontSize = 18.sp,
            modifier = Modifier.padding(end = 8.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entity.name, style = MaterialTheme.typography.bodyMedium)
                if (entity.entityCategory != null) {
                    Spacer(Modifier.width(4.dp))
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                entity.entityCategory,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                        modifier = Modifier.height(20.dp),
                    )
                }
            }
            Text(
                entity.state,
                style = MaterialTheme.typography.labelSmall,
                color = stateColor(entity.state, entity.domain),
            )
        }
        EntityActionButton(entity, onAction)
    }
}

@Composable
private fun EntityActionButton(
    entity: Entity,
    onAction: (Entity, String) -> Unit,
) {
    when (entity.domain) {
        "light", "switch", "fan", "media_player" -> {
            val isOn = entity.state in listOf("on", "playing", "home", "open")
            Switch(
                checked = isOn,
                onCheckedChange = { onAction(entity, "toggle") },
            )
        }
        "button" -> {
            FilledTonalButton(
                onClick = { onAction(entity, "press") },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("按", style = MaterialTheme.typography.labelMedium)
            }
        }
        "sensor", "binary_sensor" -> {
            // Read-only, no action
        }
        else -> {
            // Try generic toggle
            TextButton(onClick = { onAction(entity, "toggle") }) {
                Text(entity.domain)
            }
        }
    }
}

private fun entityIcon(domain: String) = when (domain) {
    "light" -> "💡"
    "switch" -> "🔌"
    "fan" -> "🌀"
    "media_player" -> "🎵"
    "sensor" -> "📊"
    "binary_sensor" -> "🔘"
    "button" -> "🔲"
    "climate" -> "🌡️"
    "cover" -> "🪟"
    "lock" -> "🔒"
    "camera" -> "📷"
    else -> "⚙️"
}

private fun stateColor(state: String, domain: String): Color = when {
    state in listOf("on", "playing", "home", "open", "unlocked", "active") -> Color(0xFF2E7D32)
    state in listOf("off", "idle", "closed", "locked", "inactive") -> Color(0xFF616161)
    else -> Color(0xFFE65100)
}
