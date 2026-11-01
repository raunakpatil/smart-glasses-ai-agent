package com.ronie.app.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/*
 * How things move: quick, soft and physical. Presses sink a little on a spring, screens and cards rise into place
 * one after another (ease-out, ~0.4 s), and every tap gives a light tick. Nothing bounces for show.
 */

/** Apple-like ease-out: fast start, long gentle settle. */
val EaseOutSoft = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/** Press feedback: shrinks to [pressed] while held, springs back on release. */
@Composable
fun Modifier.pressScale(source: MutableInteractionSource, pressed: Float = 0.97f): Modifier {
    val down by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (down) pressed else 1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium), label = "press")
    return graphicsLayer { scaleX = s; scaleY = s }
}

/** Light haptic ticks. [tick] for taps and toggles, [confirm] when something is saved or connected. */
class Haptics(private val view: android.view.View) {
    fun tick() { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    fun click() { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK) }
    fun confirm() { view.performHapticFeedback(HapticFeedbackConstants.CONFIRM) }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

/** Hands out entrance order to the cards of one screen, so they rise in one after another. */
class RevealOrder { var next = 0 }

val LocalRevealOrder = compositionLocalOf<RevealOrder?> { null }

/** Gives everything inside a fresh entrance order (call once per screen / tab). */
@Composable
fun RevealScope(content: @Composable () -> Unit) {
    val order = remember { RevealOrder() }
    CompositionLocalProvider(LocalRevealOrder provides order, content = content)
}

/** Fades and lifts the element into place when it first appears; [index] staggers it (≈50 ms apart, capped). */
@Composable
fun Modifier.reveal(index: Int = LocalRevealOrder.current?.let { o -> remember { o.next++ } } ?: 0): Modifier {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(minOf(index, 8) * 50L)
        a.animateTo(1f, tween(480, easing = EaseOutSoft))
    }
    return graphicsLayer {
        alpha = a.value
        translationY = (1f - a.value) * 28.dp.toPx()
        val s = 0.97f + 0.03f * a.value
        scaleX = s; scaleY = s
    }
}
