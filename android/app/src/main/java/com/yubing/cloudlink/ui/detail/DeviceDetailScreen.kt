package com.yubing.cloudlink.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import com.yubing.cloudlink.data.icon.DeviceIconImage
import com.yubing.cloudlink.data.icon.categoryIcon
import com.yubing.cloudlink.data.model.Entity
import com.yubing.cloudlink.data.model.FilledSlot
import com.yubing.cloudlink.data.model.HomeDevice
import com.yubing.cloudlink.data.model.QuickActionKind
import com.yubing.cloudlink.data.model.RestKind
import com.yubing.cloudlink.data.model.SlotRender
import com.yubing.cloudlink.data.model.filledSlots
import com.yubing.cloudlink.data.model.quickActionFor
import com.yubing.cloudlink.data.model.restEntities
import com.yubing.cloudlink.data.model.template

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
    // 详情页的行 = 品类模板的槽位，不是 device.entities 本身。
    // 见 DeviceTemplate.kt：同一品类的行结构、行顺序、行文案都与集成无关。
    val filled = remember(device) { device.filledSlots() }
    val used = remember(device) { filled.mapTo(HashSet()) { it.entity.entity_id } }
    val rest = remember(device) { device.restEntities(used) }
    val foldRest = remember(device) { device.template().foldRest }

    // PLUG（foldRest=true）：槽位吃不掉的一律折叠，否则小米那 20 多条私有
    // 参数一平铺，两个插座就又长得不一样了。
    // 通用品类（foldRest=false）：没有模板可依，剩余的照常平铺，保证功能不丢。
    val advanced = remember(device, rest) {
        val list = if (foldRest) {
            rest[RestKind.CONTROL].orEmpty() + rest[RestKind.CONFIG].orEmpty()
        } else {
            rest[RestKind.CONFIG].orEmpty()
        }
        list.sortedWith(compareBy({ it.domain }, { it.name }))
    }
    val info = remember(device, rest) {
        val list = if (foldRest) {
            rest[RestKind.READING].orEmpty() + rest[RestKind.DIAGNOSTIC].orEmpty()
        } else {
            rest[RestKind.DIAGNOSTIC].orEmpty()
        }
        list.sortedForReading()
    }
    val spareControls = remember(device, rest) {
        if (foldRest) emptyList() else rest[RestKind.CONTROL].orEmpty()
    }
    val spareReadings = remember(device, rest) {
        if (foldRest) emptyList() else rest[RestKind.READING].orEmpty().sortedForReading()
    }

    var advancedOpen by remember { mutableStateOf(false) }
    var infoOpen by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        item(key = "header") {
            DeviceHeader(device)
        }

        if (filled.isNotEmpty()) {
            item(key = "slots_title") {
                SectionTitle(text = "控制与状态")
            }
            item(key = "slots_card") {
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column {
                        filled.forEachIndexed { i, slot ->
                            if (i > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            SlotRow(
                                filled = slot,
                                onAction = onEntityAction,
                                onBrightness = onBrightness,
                            )
                        }
                    }
                }
            }
        }

        if (spareControls.isNotEmpty()) {
            item(key = "controls_title") { SectionTitle(text = "其他控制") }
            item(key = "controls_card") {
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column {
                        spareControls.forEachIndexed { i, entity ->
                            if (i > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            EntityControlRow(
                                entity = entity,
                                onAction = onEntityAction,
                                onBrightness = onBrightness,
                            )
                        }
                    }
                }
            }
        }

        if (spareReadings.isNotEmpty()) {
            item(key = "readings_title") { SectionTitle(text = "读数") }
            item(key = "readings_card") {
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column {
                        spareReadings.forEachIndexed { i, entity ->
                            if (i > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            EntityRow(entity = entity, deviceName = device.name)
                        }
                    }
                }
            }
        }

        if (advanced.isNotEmpty()) {
            item(key = "advanced") {
                FoldSection(
                    title = "高级设置",
                    count = advanced.size,
                    expanded = advancedOpen,
                    onToggle = { advancedOpen = !advancedOpen },
                ) {
                    advanced.forEachIndexed { i, entity ->
                        if (i > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        EntityControlRow(
                            entity = entity,
                            onAction = onEntityAction,
                            onBrightness = onBrightness,
                        )
                    }
                }
            }
        }

        if (info.isNotEmpty()) {
            item(key = "info") {
                FoldSection(
                    title = "设备信息",
                    count = info.size,
                    expanded = infoOpen,
                    onToggle = { infoOpen = !infoOpen },
                ) {
                    info.forEachIndexed { i, entity ->
                        if (i > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        EntityRow(entity = entity, deviceName = device.name)
                    }
                }
            }
        }

        item(key = "bottom") { Spacer(Modifier.height(16.dp)) }
    }
}

/**
 * 读数排序：真正的读数（温度、湿度 …）在前，电池电量这类维护信息垫底。
 *
 * 温湿度计的实体名是「电池 电池电量 / 温湿度传感器 温度 / …」，
 * 纯按名字排会把电池顶到第一行 —— 用户每次进来第一眼看到的是电量。
 */
private fun List<Entity>.sortedForReading(): List<Entity> = sortedWith(
    compareBy(
        { if (it.attributes["device_class"] == "battery") 1 else 0 },
        { it.domain },
        { it.name },
    )
)

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
    )
}

/**
 * 可折叠分区。默认收起 —— 「高级设置」里全是各集成私有的参数，
 * 「设备信息」里是固件版本这类诊断数据，都不该占据首屏。
 */
@Composable
private fun FoldSection(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$count",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            Column(modifier = Modifier.padding(vertical = 4.dp)) { content() }
        }
    }
}

/**
 * 详情页上的一个**槽位行**。
 *
 * 左侧是模板给的固定文案（「实时功率」，不是集成给的「功耗参数 电功率
 * （单位：1W）」），右侧是控件或读数。两边都不出现实体原名 —— 这正是
 * 两个品牌的插座详情页能长得一样的原因。
 */
@Composable
private fun SlotRow(
    filled: FilledSlot,
    onAction: (Entity, String) -> Unit,
    onBrightness: (Entity, Int) -> Unit,
) {
    val entity = filled.entity
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = filled.slot.title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            when (filled.slot.render) {
                SlotRender.SWITCH -> {
                    val qa = quickActionFor(entity.domain)
                    Switch(
                        checked = qa.isOn(entity.state),
                        onCheckedChange = { onAction(entity, qa.serviceFor(entity.state)) },
                    )
                }
                SlotRender.READING -> {
                    // 读数用中性色：功率 0 W、温度 41 °C 着色成橙色会读成告警。
                    // 只有「故障状态」这类健康的读数才需要区分正常 / 异常。
                    Text(
                        text = entity.displayValue(),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (entity.isAbnormal()) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                SlotRender.MOMENTARY -> {
                    FilledTonalButton(
                        onClick = { onAction(entity, quickActionFor(entity.domain).turnOnService) },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("执行", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        // 可调亮度的灯：滑块放第二行，开关保持在卡片右缘。
        if (entity.domain == "light" && entity.supportsBrightness) {
            BrightnessSlider(entity = entity, onChange = onBrightness)
        }
    }
}

@Composable
private fun DeviceHeader(device: HomeDevice) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Use the device's photo (from miot-spec.com) when available,
                // otherwise fall back to the category icon (printer / camera /
                // speaker …) so a device without a product photo still reads
                // as what it is instead of a generic placeholder.
                DeviceIconImage(
                    model = device.model,
                    size = 56.dp,
                    fallback = device.categoryIcon(),
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
            val on = device.entities.count { quickActionFor(it.domain).isOn(it.state) }
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

/**
 * 原始实体行 —— 只用在「其他控制 / 高级设置 / 设备信息」这些本来就要
 * 暴露集成原始信息的地方，这里显示实体原名才是对的。
 */
@Composable
private fun EntityControlRow(
    entity: Entity,
    onAction: (Entity, String) -> Unit,
    onBrightness: (Entity, Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entityIcon(entity.domain),
                fontSize = 22.sp,
                modifier = Modifier.padding(end = 12.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(entity.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    entity.displayValue(),
                    style = MaterialTheme.typography.labelMedium,
                    color = stateColor(entity.state, entity.domain),
                )
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

/**
 * 只读实体的展示行。
 *
 * 名字要剥掉设备名前缀：HA 的实体名是「次卧温湿度计 温湿度传感器 温度」，
 * 而这一整页的标题已经写明了是哪台设备，前缀纯属噪音。
 */
@Composable
private fun EntityRow(entity: Entity, deviceName: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = entityIcon(entity.domain),
            fontSize = 22.sp,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            text = entity.shortName(deviceName),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = entity.displayValue(),
            style = MaterialTheme.typography.titleSmall,
            color = if (entity.isAbnormal()) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** 「次卧温湿度计 温湿度传感器 温度」→「温湿度传感器 温度」。 */
private fun Entity.shortName(deviceName: String): String =
    name.removePrefix(deviceName).trim().ifEmpty { name }

@Composable
private fun BrightnessSlider(
    entity: Entity,
    onChange: (Entity, Int) -> Unit,
) {
    // The light is "off" right now — disable the slider. We still show it
    // so the user can pre-set brightness for the next turn-on.
    val isOn = quickActionFor(entity.domain).isOn(entity.state)
    // currentBrightness is null when the light is off; we hold the last
    // user-set value via the optimistic update in the ViewModel, so this
    // slider position stays put between on→off→on.
    val level = entity.effectiveBrightness()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp),
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

/**
 * 单个实体的操作控件。
 *
 * service 一律由 [quickActionFor] 决定，不在这里按 domain 分支 ——
 * 首页卡片、槽位行、高级设置三处共用同一张表，避免各自漂移。
 */
@Composable
private fun EntityControl(
    entity: Entity,
    onAction: (Entity, String) -> Unit,
) {
    val qa = quickActionFor(entity.domain)
    when (qa.kind) {
        QuickActionKind.TOGGLE, QuickActionKind.POSITION -> {
            Switch(
                checked = qa.isOn(entity.state),
                onCheckedChange = { onAction(entity, qa.serviceFor(entity.state)) },
            )
        }
        QuickActionKind.MOMENTARY -> {
            FilledTonalButton(
                onClick = { onAction(entity, qa.turnOnService) },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("执行", style = MaterialTheme.typography.labelMedium)
            }
        }
        QuickActionKind.READ_ONLY -> Unit
    }
}

/**
 * 「这个读数是不是表示设备出问题了」。
 *
 * 两种集成两种表达：佛山用 binary_sensor + device_class=problem（on/off），
 * 小米用枚举 sensor，state 直接是「无故障 / 过温 / 过载」。都要判。
 */
private fun Entity.isAbnormal(): Boolean {
    if (state == "unavailable" || state == "unknown") return false
    if (domain == "binary_sensor" && attributes["device_class"] == "problem") {
        return state == "on"
    }
    if (state.contains("故障") && !state.contains("无故障")) return true
    return state.contains("异常") || state.contains("过载") || state.contains("过温")
}

/**
 * 读数显示值：state + 单位。
 *
 * binary_sensor 的 on / off 对用户没有意义，按 device_class 翻译成
 * 「异常 / 正常」；unavailable / unknown 统一显示「不可用」，避免出现
 * 满屏 unknown。
 */
private fun Entity.displayValue(): String {
    val unit = attributes["unit_of_measurement"] as? String
    val raw = when {
        state == "unavailable" || state == "unknown" -> "不可用"
        domain == "binary_sensor" && attributes["device_class"] == "problem" ->
            if (state == "on") "异常" else "正常"
        else -> state
    }
    return if (unit.isNullOrBlank() || raw == "不可用") raw else "$raw $unit"
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
    "humidifier" -> "💧"
    "vacuum" -> "🤖"
    "water_heater" -> "🚿"
    "number" -> "🔢"
    "select" -> "📋"
    "text" -> "📝"
    else -> "⚙️"
}

private fun stateColor(state: String, domain: String): Color = when {
    quickActionFor(domain).isOn(state) -> Color(0xFF2E7D32)
    state in listOf("off", "idle", "closed", "locked", "inactive") -> Color(0xFF616161)
    state in listOf("unavailable", "unknown") -> Color(0xFF616161)
    else -> Color(0xFFE65100)
}
