package com.local.comfyuimobile.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size

/**
 * 液态玻璃（Liquid Glass）设计件。
 *
 * 真正的「背景模糊」在 Compose 里没有官方 API（`Modifier.blur` 模糊的是自身内容，
 * 不是背后的东西；`RenderEffect` 那条路需要 API 31+ 且会连子内容一起糊掉）。
 * 业界通行做法是**模拟**玻璃：半透明填充 + 高光描边 + 柔和投影，底下垫一层
 * 有颜色的渐变背景——玻璃感来自「背景透出来的颜色」，所以背景必须有层次。
 *
 * 因此这套件分两部分：
 *  1. [AuroraBackground]：极光渐变底，是整个玻璃效果的载体；
 *  2. [GlassCard] / [glassSurfaceColor]：铺在渐变上的半透明面板。
 */

/** 极光渐变调色板（青绿主调 + 蓝紫 + 暖橘，低饱和以免压过正文）。 */
private val AuroraDay = listOf(
    Color(0xFFBFE9EC), // 浅青
    Color(0xFFD8E4FE), // 雾蓝
    Color(0xFFF3E7FB), // 淡紫
    Color(0xFFFDF0E4), // 暖沙
)
private val AuroraNight = listOf(
    Color(0xFF0B1F24),
    Color(0xFF12182B),
    Color(0xFF1B1430),
    Color(0xFF0D1418),
)

/**
 * 全屏极光渐变背景。
 *
 * 用两层斜向线性渐变叠出「光斑流动」的感觉：主渐变定基调，第二层从右下
 * 补一道暖光，避免整屏单色发闷。不用径向渐变是刻意的——径向在低端机上
 * 更容易出现色带（banding）。
 */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) AuroraNight else AuroraDay
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = colors,
                        start = Offset.Zero,
                        end = Offset.Infinite,
                    ),
                ),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color.Transparent,
                            colors.last().copy(alpha = if (dark) 0.55f else 0.75f),
                        ),
                        start = Offset(0f, 0f),
                        end = Offset.Infinite,
                    ),
                ),
        )
        content()
    }
}

/**
 * 玻璃面板底色。
 *
 * 浅色主题用「高透明白」（透出背景的极光），暗色主题用「低透明黑」——
 * 两者都靠 alpha 让底层渐变透出来，这是玻璃感的关键；给不透明色就变成
 * 普通卡片了。
 */
fun glassSurfaceColor(dark: Boolean): Color =
    if (dark) Color(0xFF1A2028).copy(alpha = 0.72f) else Color.White.copy(alpha = 0.62f)

/** 玻璃高光描边：左上偏亮、右下偏暗，模拟玻璃边缘的受光。 */
fun glassBorderColor(dark: Boolean): Color =
    if (dark) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.75f)

/**
 * 玻璃卡片。
 *
 * @param radius 圆角，默认与主题 medium 一致（14dp）。
 * @param strong 更实一些的玻璃（用于承载主要内容的卡片，如工作流列表项）。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    radius: Dp = 16.dp,
    strong: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val base = glassSurfaceColor(dark)
    Surface(
        modifier = modifier.shadow(
            elevation = if (strong) 6.dp else 3.dp,
            shape = RoundedCornerShape(radius),
            ambientColor = Color.Black.copy(alpha = 0.10f),
            spotColor = Color.Black.copy(alpha = 0.14f),
        ),
        shape = RoundedCornerShape(radius),
        color = if (strong && !dark) Color.White.copy(alpha = 0.78f) else base,
        border = BorderStroke(1.dp, glassBorderColor(dark)),
        content = content,
    )
}

/**
 * 按下时轻微缩放，给触感反馈。
 *
 * 这是「丝滑」里最重要的一条：Material 默认只有涟漪，点卡片时本体不动。
 * 加一点点缩小（0.97）配合 spring，手指按下就有「按到了实体」的感觉。
 * 只做缩放、不做位移，避免长列表里滚动时误触发。
 */
@Composable
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.97f,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "pressScale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * 玻璃态的圆形底（用于图标容器）。
 *
 * 与玻璃卡同源：半透明白底 + 细高光边，放在渐变上就是一个「凸起的小玻璃泡」。
 */
@Composable
fun glassCircleModifier(size: Dp = 30.dp): Modifier {
    val dark = isSystemInDarkTheme()
    return Modifier
        .size(size)
        .clip(CircleShape)
        .background(if (dark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.55f))
}
