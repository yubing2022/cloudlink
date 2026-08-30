package com.yourname.cloudlink.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yourname.cloudlink.data.model.Device

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onLogout: () -> Unit,
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
                    IconButton(onClick = viewModel::loadDevices) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                    IconButton(onClick = { viewModel.logout(onLogout) }) {
                        Icon(Icons.Default.ExitToApp, contentDescription = "退出")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading && state.devices.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                state.error != null && state.devices.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                state.error!!,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = viewModel::loadDevices) { Text("重试") }
                        }
                    }
                }
                state.devices.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("还没有设备。先在 HA 里配置 cloudlink 集成。")
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp),
                    ) {
                        val grouped = state.devices.groupBy { it.domain }
                        grouped.forEach { (domain, items) ->
                            item {
                                Text(
                                    text = domainLabel(domain),
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.padding(8.dp),
                                )
                            }
                            items(items) { device ->
                                DeviceRow(
                                    device = device,
                                    onToggle = { viewModel.control(device, "toggle") },
                                    onTurnOn = { viewModel.control(device, "turn_on") },
                                    onTurnOff = { viewModel.control(device, "turn_off") },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun domainLabel(d: String) = when (d) {
    "light" -> "💡 灯"
    "switch" -> "🔌 开关"
    "sensor" -> "📊 传感器"
    "binary_sensor" -> "🔘 二元传感器"
    "button" -> "🔲 按钮"
    "media_player" -> "🎵 媒体"
    "fan" -> "🌀 风扇"
    "cover" -> "🪟 窗帘"
    "lock" -> "🔒 锁"
    "climate" -> "🌡️ 温控"
    else -> d
}

@Composable
private fun DeviceRow(
    device: Device,
    onToggle: () -> Unit,
    onTurnOn: () -> Unit,
    onTurnOff: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = device.state,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (device.state in listOf("on", "home", "playing", "open"))
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row {
                when (device.domain) {
                    "light", "switch", "media_player", "fan" -> {
                        Switch(
                            checked = device.state in listOf("on", "playing"),
                            onCheckedChange = { onToggle() },
                        )
                    }
                    "button" -> {
                        Button(onClick = onToggle) { Text("按") }
                    }
                    else -> {
                        Text(device.domain, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
