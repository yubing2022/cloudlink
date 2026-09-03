package com.yourname.cloudlink.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yourname.cloudlink.data.model.HomeDevice

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
                    // Refresh: while in flight, swap the static icon for a
                    // spinning indicator so the user gets clear visual
                    // feedback that something happened.
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
                    DeviceList(
                        devices = state.visibleDevices,
                        onDeviceClick = onDeviceClick,
                    )
            }
        }
    }
}

@Composable
private fun DeviceList(
    devices: List<HomeDevice>,
    onDeviceClick: (HomeDevice) -> Unit,
) {
    val byArea = remember(devices) {
        devices.groupBy { it.area ?: "未分组" }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        byArea.forEach { (area, devs) ->
            item(key = "area_$area") {
                Text(
                    text = "📍 $area",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
            items(devs.filter { it.entities.isNotEmpty() }, key = { it.haDeviceId }) { device ->
                DeviceCard(device = device, onClick = { onDeviceClick(device) })
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: HomeDevice,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Home,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                val subtitle = listOfNotNull(device.area, device.manufacturer, device.model)
                    .filter { it.isNotBlank() }
                    .joinToString(" • ")
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val onlineCount = device.entities.count { e ->
                    e.state in listOf("on", "playing", "home", "open", "active")
                }
                val subtitleLine2 = if (device.entities.size > 1) {
                    "${device.entities.size} 个 entity"
                } else if (device.entities.size == 1) {
                    "1 个 entity"
                } else {
                    ""
                }
                if (subtitleLine2.isNotEmpty()) {
                    Text(
                        text = subtitleLine2,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "详情",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
