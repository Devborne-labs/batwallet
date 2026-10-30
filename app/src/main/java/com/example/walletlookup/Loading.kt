package com.example.walletlookup

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/** How far the list slides down for a pull: the room the dots sit in. */
private val PullGap = 64.dp

/**
 * Three dots in a row across the canvas's width. [level] (0 resting … 1 lit) sets each dot's size, brightness and lift;
 * it's read while drawing, so an animation driving it only redraws.
 */
@Composable
private fun Dots(modifier: Modifier, color: Color, level: (Int) -> Float) {
    Canvas(modifier) {
        // Each dot is a quarter of the width, with an eighth between them: 2r + r + 2r + r + 2r = 8r.
        val r = size.width / 8f
        for (i in 0..2) {
            val l = level(i).coerceIn(0f, 1f)
            drawCircle(
                color.copy(alpha = color.alpha * (0.3f + 0.7f * l)),
                radius = r * (0.6f + 0.4f * l),
                center = Offset(r + 3 * r * i, center.y - 0.8f * r * l),
            )
        }
    }
}

/** One pass of the wave: each dot rises, brightens and settles in turn, then all three rest for a moment. */
private fun wave(phase: Float, dot: Int): Float {
    val t = phase - dot * 0.16f
    val u = t - floor(t)
    return if (u < 0.6f) sin(PI.toFloat() * u / 0.6f) else 0f
}

/**
 * Batwallet's loading sign, used for everything that loads: three gold dots rippling left to right. [size] is its
 * width. [color] defaults to the accent; a card can pass its own (the safety card's state colour).
 */
@Composable
fun BatSpinner(modifier: Modifier = Modifier, size: Dp = 18.dp, color: Color = MaterialTheme.colorScheme.primary) {
    val phase = rememberInfiniteTransition(label = "dots")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "phase")
    Dots(modifier.size(size), color) { wave(phase.value, it) }
}

/**
 * The dots in the gap a pull opens above the list. While pulling they light up one by one (all three: let go to
 * update); while updating they ripple.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoxScope.PullDots(state: PullToRefreshState, updating: Boolean) {
    val pulled = state.distanceFraction
    if (pulled <= 0.01f) return
    Box(
        Modifier.align(Alignment.TopCenter).fillMaxWidth().height(PullGap)
            .graphicsLayer {
                // Centred in the part of the gap that's open so far, fading in with it.
                translationY = (pulled.coerceIn(0f, 1f) - 1f) * PullGap.toPx() / 2
                alpha = pulled.coerceIn(0f, 1f)
            },
        contentAlignment = Alignment.Center,
    ) {
        if (updating) BatSpinner(size = 30.dp)
        else Dots(Modifier.size(30.dp), MaterialTheme.colorScheme.primary) { pulled * 3 - it }
    }
}

/**
 * Pull down to refresh: the list slides down and Batwallet's dots show in the gap above it ([PullDots]), never on top
 * of the list. Only a pull opens the gap; background updates show their own dots (in the rows, or Home's total card).
 * [isRefreshing] keeps the gap open until the update the pull started has finished; a screen that already shows its
 * own status passes false and the gap closes again after a moment.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatPullToRefresh(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    var pulled by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(pulled, isRefreshing) {
        if (!pulled) return@LaunchedEffect
        if (isRefreshing) {
            started = true
            return@LaunchedEffect
        }
        // Finished; or it never started (nothing to update, or the screen shows its own status): a moment to see it.
        delay(if (started) 250 else 800)
        pulled = false
        started = false
    }
    PullToRefreshBox(
        isRefreshing = pulled,
        onRefresh = {
            pulled = true
            started = false
            onRefresh()
        },
        // Clipped, so the gap and the slid-down list stay inside the list's own area (never over the header or tabs).
        modifier = modifier.clipToBounds(),
        state = state,
        indicator = { PullDots(state, pulled) },
    ) {
        Box(Modifier.fillMaxSize().graphicsLayer { translationY = state.distanceFraction.coerceIn(0f, 1.5f) * PullGap.toPx() }) {
            content()
        }
    }
}
