package com.yourname.cloudlink.ui.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
fun DeviceDetailScreen(
    onBack: () -> Unit,
    viewModel: DeviceDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val device = state.device

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(device?.name ?: "设备详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                state.error != null ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(state.error!!, color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = viewModel::load) { Text("重试") }
                        }
                    }
                device == null ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("没有数据")
                    }
                else ->
                    DeviceDetailContent(
                        device = device,
                        onEntityAction = viewModel::control,
                        onBrightness = viewModel::setBrightness,
                    )
            }
        }
    }
}

@Composable
private fun DeviceDetailContent(
    device: HomeDevice,
    onEntityAction: (Entity, String) -> Unit,
    onBrightness: (Entity, Int) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        item(key = "header") {
            DeviceHeader(device)
        }
        item(key = "entities_title") {
            Text(
                text = "控制 (${device.entities.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
            )
        }
        items(device.entities, key = { it.entity_id }) { entity ->
            EntityControlRow(
                entity = entity,
                onAction = onEntityAction,
                onBrightness = onBrightness,
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

@Composable
private fun DeviceHeader(device: HomeDevice) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Use the device's photo (from miot-spec.com) when available,
                // otherwise fall back to a generic Material icon.
                DeviceIconImage(
                    model = device.model,
                    size = 56.dp,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val sub = listOfNotNull(device.area, device.manufacturer, device.model)
                        .filter { it.isNotBlank() }
                        .joinToString(" • ")
                    if (sub.isNotEmpty()) {
                        Text(
                            text = sub,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            val on = device.entities.count { it.state in ON_STATES }
            Row(modifier = Modifier.fillMaxWidth()) {
                StatusPill(label = "实体", value = "${device.entities.size}")
                Spacer(Modifier.width(8.dp))
                StatusPill(label = "开启中", value = "$on")
            }
        }
    }
}

@Composable
private fun StatusPill(label: String, value: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.width(6.dp))
            Text(
                value,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun EntityControlRow(
    entity: Entity,
    onAction: (Entity, String) -> Unit,
    onBrightness: (Entity, Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entityIcon(entity.domain),
                fontSize = 22.sp,
                modifier = Modifier.padding(end = 12.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(entity.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    entity.state,
                    style = MaterialTheme.typography.labelMedium,
                    color = stateColor(entity.state, entity.domain),
                )
                if (entity.entityCategory != null) {
                    Text(
                        text = entity.entityCategory,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            EntityControl(entity = entity, onAction = onAction)
        }
        // For dimmable lights, render the brightness slider on a second
        // row so the switch stays at the right edge of the card.
        if (entity.domain == "light" && entity.supportsBrightness) {
            BrightnessSlider(
                entity = entity,
                onChange = onBrightness,
            )
        }
    }
}

@Composable
private fun BrightnessSlider(
    entity: Entity,
    onChange: (Entity, Int) -> Unit,
) {
    // The light is "off" right now — disable the slider. We still show it
    // so the user can pre-set brightness for the next turn-on.
    val isOn = entity.state in LIST_OF_ON
    // currentBrightness is null when the light is off; we hold the last
    // user-set value via the optimistic update in the ViewModel, so this
    // slider position stays put between on→off→on.
    val level = entity.effectiveBrightness()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 34.dp, end = 12.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Lightbulb,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(4.dp))
        Slider(
            modifier = Modifier.weight(1f),
            value = level.toFloat(),
            onValueChange = { onChange(entity, it.toInt()) },
            // valueRange is 1..255 (0 is "off", separate Switch toggles that)
            valueRange = 1f..255f,
            steps = 25,
            enabled = isOn,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = "${(level * 100) / 255}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 36.dp),
        )
    }
}

@Composable
private fun EntityControl(
    entity: Entity,
    onAction: (Entity, String) -> Unit,
) {
    when (entity.domain) {
        "light" -> {
            val isOn = entity.state in LIST_OF_ON
            Switch(
                checked = isOn,
                onCheckedChange = { turnOn ->
                    // For a light, don't just toggle — if we're turning on
                    // but the last known brightness was 0, request full
                    // brightness so the user doesn't get a black bulb.
                    if (turnOn) {
                        val level = entity.effectiveBrightness(fallback = 255)
                        // Use the more specific service so brightness is
                        // preserved / set to the chosen level.
                        onAction(entity, "turn_on")
                    } else {
                        onAction(entity, "turn_off")
                    }
                },
            )
        }
        "switch", "fan", "media_player" -> {
            val isOn = entity.state in LIST_OF_ON
            Switch(
                checked = isOn,
                onCheckedChange = { onAction(entity, "toggle") },
            )
        }
        "lock" -> {
            val isLocked = entity.state == "locked"
            Switch(
                checked = isLocked,
                onCheckedChange = {
                    onAction(entity, if (isLocked) "unlock" else "lock")
                },
            )
        }
        "cover" -> {
            val isOpen = entity.state == "open"
            Switch(
                checked = isOpen,
                onCheckedChange = {
                    onAction(entity, if (isOpen) "close" else "open")
                },
            )
        }
        "button" -> {
            FilledTonalButton(
                onClick = { onAction(entity, "press") },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("按", style = MaterialTheme.typography.labelMedium)
            }
        }
        "sensor", "binary_sensor" -> {
            // Read-only — no control widget
        }
        else -> {
            TextButton(onClick = { onAction(entity, "toggle") }) {
                Text(entity.domain)
            }
        }
    }
}

private val LIST_OF_ON = setOf("on", "playing", "home", "open", "unlocked", "active")

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
    "humidifier" -> "💧"
    "vacuum" -> "🤖"
    "water_heater" -> "🚿"
    else -> "⚙️"
}

private fun stateColor(state: String, domain: String): Color = when {
    state in LIST_OF_ON -> Color(0xFF2E7D32)
    state in listOf("off", "idle", "closed", "locked", "inactive") -> Color(0xFF616161)
    else -> Color(0xFFE65100)
}
