package com.jiexiy.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.jiexiy.app.ui.navigation.MainTab
import com.jiexiy.app.ui.theme.ThemeController
import com.jiexiy.app.ui.theme.effectsDefault
import com.jiexiy.app.ui.theme.effectsFast
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.launchIn

/**
 * 液态玻璃底部导航栏：
 * - 浮动胶囊容器（最大宽度 560dp，圆角 = 高度 / 2）
 * - 背景模糊 + 玻璃折射效果（边缘有光泽感）
 * - 选中标签用弹簧动画跟随手指移动
 * - 触摸高亮（径向渐变）
 * - 玻璃质感渐变背景 + 边框 + 阴影
 *
 * 参考 wenxi 的 LiquidBottomNavigation 实现。
 */
@Composable
fun LiquidGlassBottomBar(
    currentTab: MainTab,
    onTabSelected: (MainTab) -> Unit
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    // 玻璃效果开关
    val glassEnabled = ThemeController.glassBottomBar

    // 标签列表
    val tabs = remember { MainTab.values().toList() }
    val selectedIndex = remember { mutableStateOf(tabs.indexOf(currentTab).coerceAtLeast(0)) }

    // 监听外部选中变化
    LaunchedEffect(currentTab) {
        val newIdx = toLandscapeIndex(currentTab, tabs)
        if (selectedIndex.value != newIdx) {
            selectedIndex.value = newIdx
        }
    }

    // 容器尺寸
    val barSize = remember { mutableStateOf(IntSize.Zero) }

    // 位置动画（支持拖拽）
    val position = remember {
        Animatable(selectedIndex.value.toFloat())
    }

    // 触摸位置
    val touchOffset = remember { mutableStateOf<Offset?>(null) }

    // 高亮强度
    val glowProgress = remember { Animatable(0f) }

    // 按压状态
    var isPressed by remember { mutableStateOf(false) }
    val pressScale = remember {
        Animatable(1f)
    }

    // 拖拽状态
    var dragOffset by remember { mutableStateOf<Float?>(null) }

    // 颜色
    val dark = isSystemInDarkTheme()
    val colors = MaterialTheme.colorScheme
    val activeColor = colors.primary
    val inactiveColor = colors.onSurfaceVariant
    val glassColor = if (dark) Color(0xFF1C1D21) else Color.White
    val borderAlpha = if (dark) 0.48f else 0.65f
    val shadowColor = lerp(
        if (dark) Color.Black else colors.onSurface,
        colors.primary,
        if (dark) 0.08f else 0.16f
    )

    // 高度
    val barHeight = 64.dp
    val innerPadding = 4.dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .height(barHeight)
                .onSizeChanged { barSize.value = it }
                .graphicsLayer {
                    // 整体缩放（按压时略微缩小）
                    scaleX = pressScale.value
                    scaleY = pressScale.value
                }
                .shadow(
                    elevation = 12.dp,
                    shape = CircleShape,
                    spotColor = shadowColor.copy(alpha = if (dark) 0.14f else 0.055f),
                    ambientColor = shadowColor.copy(alpha = if (dark) 0.14f else 0.055f)
                )
                .clip(CircleShape)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            lerp(
                                Color.White.copy(alpha = if (dark) 0.025f else 0.16f),
                                glassColor,
                                0.5f
                            ),
                            glassColor
                        )
                    )
                )
                .border(
                    width = 0.8.dp,
                    color = colors.outlineVariant.copy(alpha = borderAlpha),
                    shape = CircleShape
                )
                // 背景模糊
                .then(
                    if (glassEnabled && android.os.Build.VERSION.SDK_INT >= 31) {
                        Modifier.blur(20.dp)
                    } else {
                        Modifier
                    }
                )
                // 触摸高亮
                .drawBehind {
                    val touch = touchOffset.value
                    val glow = glowProgress.value
                    if (touch != null && glow > 0f) {
                        val highlightAlpha = glow * (if (dark) 0.09f else 0.26f)
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = highlightAlpha),
                                    Color.White.copy(alpha = 0f)
                                ),
                                center = touch,
                                radius = size.height * 0.725f
                            ),
                            center = touch,
                            radius = size.height * 0.725f
                        )
                    }
                }
        ) {
            // 选中标签（Lens）
            val positionValue = position.value.coerceIn(-0.035f, tabs.size - 1 + 0.035f)
            val tabWidth = if (barSize.value.width > 0) {
                (barSize.value.width - with(density) { (innerPadding * 2).toPx() }) / tabs.size
            } else {
                0f
            }

            val lensOffset = with(density) {
                (innerPadding.toPx() + positionValue * tabWidth).roundToInt()
            }

            Box(
                modifier = Modifier
                    .offset { IntOffset(lensOffset, with(density) { innerPadding.toPx() }.roundToInt()) }
                    .size(
                        width = with(density) { tabWidth.roundToInt().toDp() },
                        height = barHeight - innerPadding * 2
                    )
                    .graphicsLayer {
                        val stretch = abs(position.velocity) * 0.003f
                        scaleX = 1f + pressScale.value * 0.035f + stretch
                        scaleY = 1f + pressScale.value * 0.035f - stretch * 0.4f
                    }
                    .clip(CircleShape)
                    .background(
                        color = if (dark) Color(0xFF2A2B30) else Color.White.copy(alpha = 0.9f)
                    )
                    .border(
                        width = 0.8.dp,
                        color = activeColor.copy(alpha = if (dark) 0.18f else 0.09f),
                        shape = CircleShape
                    )
            )

            // 标签内容
            Row(
                modifier = Modifier
                    .padding(innerPadding),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                tabs.forEachIndexed { index, tab ->
                    val isSelected = index == position.value.roundToInt()
                    val label = tab.title
                    val icon = if (isSelected) tab.selectedIcon else tab.unselectedIcon

                    val interactionSource = remember { MutableInteractionSource() }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(barHeight - innerPadding * 2)
                            .clip(CircleShape)
                            .hoverable(interactionSource)
                            .pointerInput(tab) {
                                // 触摸跟踪
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Press) {
                                            isPressed = true
                                            val touch = event.changes.firstOrNull()?.position
                                            if (touch != null) touchOffset.value = touch
                                        }
                                        if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Release ||
                                            event.type == androidx.compose.ui.input.pointer.PointerEventType.Exit) {
                                            isPressed = false
                                            touchOffset.value = null
                                        }
                                    }
                                }
                            }
                            .pointerInput(tab) {
                                // 拖拽支持
                                detectHorizontalDragGestures(
                                    onDragStart = { offset ->
                                        isPressed = true
                                        touchOffset.value = offset
                                    },
                                    onHorizontalDrag = { change, dragAmount ->
                                        change.consume()
                                        val totalOffset = change.position
                                        touchOffset.value = totalOffset
                                        val newPosition = (position.value + dragAmount / tabWidth)
                                        scope.launch {
                                            position.snapTo(newPosition)
                                        }
                                    },
                                    onDragEnd = {
                                        isPressed = false
                                        touchOffset.value = null
                                        val targetIndex = position.value.roundToInt().coerceIn(0, tabs.size - 1)
                                        val targetTab = tabs[targetIndex]
                                        if (targetTab != tabs[selectedIndex.value]) {
                                            selectedIndex.value = targetIndex
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onTabSelected(targetTab)
                                        }
                                        scope.launch {
                                            position.animateTo(
                                                targetIndex.toFloat(),
                                                animationSpec = spring(
                                                    stiffness = 550f,
                                                    dampingRatio = 38f
                                                )
                                            )
                                        }
                                    },
                                    onDragCancel = {
                                        isPressed = false
                                        touchOffset.value = null
                                        scope.launch {
                                            position.animateTo(
                                                selectedIndex.value.toFloat(),
                                                animationSpec = spring(
                                                    stiffness = 550f,
                                                    dampingRatio = 38f
                                                )
                                            )
                                        }
                                    }
                                )
                            }
                            .pointerInput(tab) {
                                // 点击选择
                                detectHorizontalDragGestures(
                                    onDragStart = { },
                                    onHorizontalDrag = { change, _ -> },
                                    onDragEnd = { },
                                    onDragCancel = { }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clickable {
                                    val newIndex = index
                                    if (newIndex != selectedIndex.value) {
                                        selectedIndex.value = newIndex
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onTabSelected(tabs[newIndex])
                                        scope.launch {
                                            position.animateTo(
                                                newIndex.toFloat(),
                                                animationSpec = spring(
                                                    stiffness = 550f,
                                                    dampingRatio = 38f
                                                )
                                            )
                                        }
                                    }
                                }
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = label,
                                tint = if (isSelected) activeColor else inactiveColor,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) activeColor else inactiveColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.width(56.dp)
                            )
                        }
                    }
                }
            }
        }

        // 按压动画
        LaunchedEffect(isPressed) {
            val target = if (isPressed) 0.965f else 1f
            pressScale.animateTo(target, animationSpec = spring(dampingRatio = 0.55f, stiffness = 900f))
        }

        // 高亮动画
        LaunchedEffect(isPressed) {
            val target = if (isPressed) 1f else 0f
            glowProgress.animateTo(target, animationSpec = androidx.compose.animation.core.tween(160))
        }
    }
}

/**
 * 将 MainTab 转换为标签索引（支持横屏隐藏部分标签的情况）。
 */
private fun toLandscapeIndex(tab: MainTab, tabs: List<MainTab>): Int {
    return tabs.indexOf(tab).coerceAtLeast(0)
}
