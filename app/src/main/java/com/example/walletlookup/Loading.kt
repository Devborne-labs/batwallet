package com.example.walletlookup

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The ring's line: about a seventh of its size, never thinner than 2 dp. */
private fun DrawScope.ringStroke(): Float = (size.minDimension * 0.14f).coerceAtLeast(2.dp.toPx())

/** The faint ring a spinner or the pull progress runs on. */
private fun DrawScope.ringTrack(color: Color, stroke: Float) {
    val inset = stroke / 2
    drawArc(color.copy(alpha = 0.18f), 0f, 360f, useCenter = false, topLeft = Offset(inset, inset),
        size = Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
}

/**
 * Batwallet's loading sign, used for everything that loads: a gold arc with a bright head and a fading tail, turning
 * over a faint ring. [color] defaults to the accent; a card can pass its own (the safety card's state colour).
 */
@Composable
fun BatSpinner(modifier: Modifier = Modifier, size: Dp = 18.dp, color: Color = MaterialTheme.colorScheme.primary) {
    val turn = rememberInfiniteTransition(label = "spinner")
    val angle by turn.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "angle")
    Canvas(modifier.size(size)) {
        val stroke = ringStroke()
        ringTrack(color, stroke)
        val inset = stroke / 2
        rotate(angle) {
            // A sweep gradient starts at 3 o'clock like the arc: the tail fades in from nothing to full gold at the head.
            drawArc(
                Brush.sweepGradient(0f to color.copy(alpha = 0f), 0.72f to color, 1f to color, center = center),
                startAngle = 0f, sweepAngle = 260f, useCenter = false, topLeft = Offset(inset, inset),
                size = Size(this.size.width - stroke, this.size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
    }
}

/** How far a pull has gone ([progress] 0…1), as the same ring filling up from the top. */
@Composable
private fun PullRing(progress: Float, size: Dp, color: Color = MaterialTheme.colorScheme.primary) {
    Canvas(Modifier.size(size)) {
        val stroke = ringStroke()
        ringTrack(color, stroke)
        val inset = stroke / 2
        drawArc(color, -90f, 360f * progress.coerceIn(0f, 1f), useCenter = false, topLeft = Offset(inset, inset),
            size = Size(this.size.width - stroke, this.size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/**
 * The pull-to-refresh sign: a small pill below the top edge. While pulling it follows the finger and its ring fills
 * ("Pull to update", then "Release to update"); while updating it shows [BatSpinner] and "Updating…".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoxScope.BatRefreshIndicator(state: PullToRefreshState, isRefreshing: Boolean) {
    // 0 hidden … 1 fully shown: it follows the finger while pulling and stays out while it updates.
    val resting by animateFloatAsState(if (isRefreshing) 1f else 0f, tween(250), label = "refresh")
    val pulled = state.distanceFraction
    val reveal = maxOf(resting, pulled.coerceIn(0f, 1f))
    if (reveal <= 0.01f) return
    // Still "Updating…" while it fades away after the update, rather than flicking to "Pull to update".
    val updating = isRefreshing || (resting > 0.01f && pulled <= 0f)
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.align(Alignment.TopCenter)
            .graphicsLayer {
                translationY = -48.dp.toPx() + reveal * 60.dp.toPx()
                alpha = reveal
            }
            .shadow(8.dp, shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), shape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (updating) BatSpinner(size = 16.dp) else PullRing(pulled, 16.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                updating -> "Updating…"
                pulled >= 1f -> "Release to update"
                else -> "Pull to update"
            },
            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Pull down to refresh, with Batwallet's own sign ([BatRefreshIndicator]) instead of the plain grey circle.
 * [isRefreshing] shows "Updating…" in it; a screen that already shows its own status (Home's total card) passes false
 * and only the pull itself is shown.
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
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        state = state,
        indicator = { BatRefreshIndicator(state, isRefreshing) },
        content = content,
    )
}
