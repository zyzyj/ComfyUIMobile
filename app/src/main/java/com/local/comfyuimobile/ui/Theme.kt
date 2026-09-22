package com.local.comfyuimobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * 设计令牌（android-design-skill：保留 M3 的可访问性与行为，替换它的视觉默认值）。
 *
 * 原来的配色是 M3 默认的紫蓝（primary 0xFF5747B8）——那是"一看就是 Material 模板"
 * 的头号特征。这里换成一套自主的中性灰 + 单一青绿强调色：灰阶承担层级，
 * 强调色只用在真正需要抓眼球的地方（主按钮、当前态、图标点缀）。
 *
 * 命名沿用语义（primary / surface / ...），不另造一套，这样所有既有
 * MaterialTheme.colorScheme.* 引用无需改动。
 */

// —— 亮色 ——
private val Ink = Color(0xFF15171C)          // 正文/标题
private val InkMuted = Color(0xFF6B7280)     // 次级文字
private val Paper = Color(0xFFF6F7F9)        // 页面底色（冷灰，非纯白）
private val CardLight = Color(0xFFFFFFFF)    // 卡片
private val LineLight = Color(0xFFE3E6EB)    // 描边
private val Accent = Color(0xFF0E7C86)       // 强调色：青绿，避开 M3 默认紫
private val AccentContainer = Color(0xFFD6F1F3)

private val LightScheme = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = AccentContainer,
    onPrimaryContainer = Color(0xFF06373C),
    secondary = Color(0xFF4B5563),
    onSecondary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = CardLight,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEEF0F4),
    onSurfaceVariant = InkMuted,
    outline = LineLight,
    outlineVariant = Color(0xFFEDEFF3),
    error = Color(0xFFB3261E),
)

// —— 暗色 ——
private val PaperDark = Color(0xFF101215)
private val CardDark = Color(0xFF181B20)
private val InkDark = Color(0xFFECEFF3)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF4DD0D8),
    onPrimary = Color(0xFF00323A),
    primaryContainer = Color(0xFF114C52),
    onPrimaryContainer = Color(0xFFC9F0F3),
    secondary = Color(0xFFB6BDC9),
    onSecondary = Color(0xFF272B31),
    background = PaperDark,
    onBackground = InkDark,
    surface = CardDark,
    onSurface = InkDark,
    surfaceVariant = Color(0xFF242830),
    onSurfaceVariant = Color(0xFFA9B0BC),
    outline = Color(0xFF343A44),
    outlineVariant = Color(0xFF272C34),
    error = Color(0xFFF2B8B5),
)

/**
 * 字体层级：拉开档差。
 *
 * M3 默认的 headline/body 尺寸太接近，一屏看下来没有层次（skill 里
 * "没有视觉层级（全是一个字号）"是最常见的问题）。这里把标题加重、
 * 标签字距拉开，让"标题 / 正文 / 说明"三档一眼可辨。
 */
private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.2.sp),
)

/** 圆角：卡片用 14dp（比 M3 默认 12dp 略圆，更柔和；窗口不设圆角）。 */
private val AppShapes = androidx.compose.material3.Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
)

@Composable
fun ComfyMobileTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
