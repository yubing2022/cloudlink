package com.yubing.cloudlink.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Blinds
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Shower
import androidx.compose.material.icons.outlined.SmartButton
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yubing.cloudlink.data.model.quickActionFor
import com.yubing.cloudlink.ui.theme.statusColors

/**
 * domain → Material 图标。
 *
 * 之前这里是一串 emoji（💡🔌🌀…）。emoji 由系统字体渲染，颜色、粗细、
 * 基线都不可控，不同 ROM 长得不一样，跟 Material 图标混排更是直接掉档。
 * 一律换成 outlined 图标，颜色走主题。
 */
fun domainIcon(domain: String): ImageVector = when (domain) {
    "light" -> Icons.Outlined.Lightbulb
    "switch" -> Icons.Outlined.PowerSettingsNew
    "fan" -> Icons.Outlined.Air
    "media_player" -> Icons.Outlined.Speaker
    "sensor" -> Icons.Outlined.Sensors
    "binary_sensor" -> Icons.Outlined.RadioButtonChecked
    "button" -> Icons.Outlined.SmartButton
    "climate" -> Icons.Outlined.Thermostat
    "cover" -> Icons.Outlined.Blinds
    "lock" -> Icons.Outlined.Lock
    "camera" -> Icons.Outlined.Videocam
    "humidifier" -> Icons.Outlined.WaterDrop
    "vacuum" -> Icons.Outlined.CleaningServices
    "water_heater" -> Icons.Outlined.Shower
    "number" -> Icons.Outlined.Numbers
    "select" -> Icons.Outlined.Checklist
    "text" -> Icons.Outlined.TextFields
    else -> Icons.Outlined.DevicesOther
}

/**
 * 设备状态的颜色。
 *
 * 以前是硬编码 0xFF2E7D32 / 0xFF616161 / 0xFFE65100 三个值，不随主题走，
 * 深色模式下对比度直接失控。现在从主题的语义色取。
 */
@Composable
fun stateColor(state: String, domain: String): Color = when {
    state == "unavailable" || state == "unknown" -> MaterialTheme.statusColors.offline
    quickActionFor(domain).isOn(state) -> MaterialTheme.statusColors.online
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * 分区容器。全 App 的卡片统一走这里：同一套圆角（20dp）、同一套容器色
 * （surfaceContainerLow）、同一套内边距，避免每个页面各写一份导致参差。
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) { content() }
}

/** 分区标题：卡片外的那一行小标题。 */
@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(horizontal = 4.dp, vertical = 10.dp),
    )
}

/**
 * 空态。以前是一行纯文字居中，既没有层次也不说下一步怎么办。
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** 错误态：给一句人话 + 重试入口。 */
@Composable
fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.DevicesOther,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onRetry) { Text("重试") }
    }
}

/** 加载态。 */
@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.material3.CircularProgressIndicator(
            modifier = Modifier.size(32.dp),
            strokeWidth = 3.dp,
        )
    }
}
