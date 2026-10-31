package com.yubing.cloudlink.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 品牌色板。
 *
 * 不再跟随系统动态取色：动态色是从壁纸抓的，每台机器长得都不一样，
 * App 也就没有了自己的样子。这里固定一套青蓝色，深浅两套都配齐。
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF00658F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC8E7FF),
    onPrimaryContainer = Color(0xFF001E2E),
    secondary = Color(0xFF4F616C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD2E5F0),
    onSecondaryContainer = Color(0xFF0B1E27),
    tertiary = Color(0xFF6B5778),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF2DAFA),
    onTertiaryContainer = Color(0xFF251431),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFBFCFE),
    onBackground = Color(0xFF191C1E),
    surface = Color(0xFFFBFCFE),
    onSurface = Color(0xFF191C1E),
    surfaceVariant = Color(0xFFDCE3E9),
    onSurfaceVariant = Color(0xFF41484D),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F7FA),
    surfaceContainer = Color(0xFFEFF2F6),
    surfaceContainerHigh = Color(0xFFE9ECF1),
    surfaceContainerHighest = Color(0xFFE3E7EC),
    outline = Color(0xFF71787E),
    outlineVariant = Color(0xFFC1C7CE),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF2E3134),
    inverseOnSurface = Color(0xFFF0F3F6),
    inversePrimary = Color(0xFF8FCCFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FCCFF),
    onPrimary = Color(0xFF00344B),
    primaryContainer = Color(0xFF004C6B),
    onPrimaryContainer = Color(0xFFC8E7FF),
    secondary = Color(0xFFB4CBDA),
    onSecondary = Color(0xFF1F333D),
    secondaryContainer = Color(0xFF374A54),
    onSecondaryContainer = Color(0xFFD2E5F0),
    tertiary = Color(0xFFD6BEE4),
    onTertiary = Color(0xFF3B2A48),
    tertiaryContainer = Color(0xFF534060),
    onTertiaryContainer = Color(0xFFF2DAFA),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF101416),
    onBackground = Color(0xFFE1E4E8),
    surface = Color(0xFF101416),
    onSurface = Color(0xFFE1E4E8),
    surfaceVariant = Color(0xFF41484D),
    onSurfaceVariant = Color(0xFFC1C7CE),
    surfaceContainerLowest = Color(0xFF0B0E10),
    surfaceContainerLow = Color(0xFF191C1E),
    surfaceContainer = Color(0xFF1D2023),
    surfaceContainerHigh = Color(0xFF282B2E),
    surfaceContainerHighest = Color(0xFF333639),
    outline = Color(0xFF8B9198),
    outlineVariant = Color(0xFF41484D),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFE1E4E8),
    inverseOnSurface = Color(0xFF2E3134),
    inversePrimary = Color(0xFF00658F),
)

/**
 * 语义色。Material 的 ColorScheme 里没有「在线 / 离线」这一档，
 * 硬编码 0xFF2E7D32 那种写法在深色模式下会直接不可读，所以单列一组随主题切换。
 */
data class StatusColors(
    val online: Color,
    val offline: Color,
    val warning: Color,
)

private val LightStatus = StatusColors(
    online = Color(0xFF1B7F4B),
    offline = Color(0xFF5F666B),
    warning = Color(0xFF9A5B00),
)

private val DarkStatus = StatusColors(
    online = Color(0xFF6FDB95),
    offline = Color(0xFF9BA1A7),
    warning = Color(0xFFE8B96B),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

/** Material3 默认的圆角偏小，卡片给到 20dp 才有现代感。 */
private val AppShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)

/**
 * 默认 Typography 的行高对中文偏紧，正文给足行高；标题加一点字重，
 * 让卡片标题和正文拉开层级。
 */
private val AppTypography = Typography(
    displaySmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp),
)

@Composable
fun CloudLinkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    CompositionLocalProvider(
        LocalStatusColors provides (if (darkTheme) DarkStatus else LightStatus),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = AppShapes,
            typography = AppTypography,
            content = content,
        )
    }
}

/** 便捷取值：`MaterialTheme.statusColors.online`。 */
val androidx.compose.material3.MaterialTheme.statusColors: StatusColors
    @Composable get() = LocalStatusColors.current
