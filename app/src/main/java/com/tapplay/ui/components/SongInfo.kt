package com.tapplay.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.view.ViewConfiguration
import com.tapplay.util.GestureHandler
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Artist-hero song info typography (JetBrains Mono via AppTypography):
 * - Artist name: 46sp ExtraBold, tight tracking, pure white (top), 3-line max.
 * - Song title: 14sp Medium dim subtitle (bottom), 2-line max.
 * - Centered alignment in upper-middle screen area (placement unchanged).
 *
 * Stateful + interactive since the hold-to-open-queue change: while [onHoldToOpen]
 * is set, holding the artist text for the system long-press timeout progressively
 * glows the artist (white shadow ramp) and brightens the title, then invokes
 * [onHoldToOpen] once. Releasing early snaps the glow back in ~120 ms.
 *
 * Pointer ownership (queue-instant S1): the gesture stays a passive observer
 * until hold completion — quick taps are never consumed, so the parent's ambient
 * lift check still resolves Tap(CENTER) and toggles playback. Once the hold
 * completes (and the queue opens), every subsequent change of this gesture is
 * consumed: the ambient GestureHandler's lift check and mid-press abort both
 * bail on consumed changes, so a stale post-open stream can never resolve a
 * toggle or a volume step from this now-dead surface. Drift past 12dp before
 * the timeout also drains-to-lift (all consumed), recording dead-input intent
 * so the ambient can neither toggle nor step volume from this dead surface.
 */
@Composable
fun SongInfo(
    title: String,
    artist: String,
    modifier: Modifier = Modifier,
    onHoldToOpen: (() -> Unit)? = null,
) {
    val glow = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = artist.ifBlank { "TapPlay" },
            color = Color.White,
            fontSize = 46.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-1).sp,
            lineHeight = 50.sp,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                shadow = Shadow(
                    color = Color.White.copy(alpha = 0.8f * glow.value),
                    offset = Offset.Zero,
                    blurRadius = 18f * glow.value,
                ),
            ),
            modifier =
                if (onHoldToOpen == null) {
                    Modifier
                } else {
                    Modifier.pointerInput(onHoldToOpen) {
                        val holdTimeoutMs = ViewConfiguration.getLongPressTimeout()
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            // Visual ramp only: pure VFX, never the completion
                            // source of truth (the withTimeoutOrNull race below
                            // is) and never invokes anything.
                            val ramp = scope.launch {
                                glow.snapTo(0f)
                                glow.animateTo(1f, tween(holdTimeoutMs, easing = LinearEasing))
                            }
                            // Watchdog race: the deadline is a coroutine timer,
                            // NOT an in-loop uptime check — a perfectly still
                            // finger (zero pointer events) still resolves the
                            // hold at ~holdTimeoutMs. Contract: null => timeout;
                            // Held => tracked finger lifted/lost before timeout;
                            // Abandoned => drifted > LONG_PRESS_SLOP (12dp,
                            // shared with GestureHandler's ambient pending-press
                            // horizon) before timeout. null is forbidden from
                            // awaitHoldEnd, so null unambiguously means timeout.
                            val outcome = withTimeoutOrNull(holdTimeoutMs.toLong()) {
                                awaitHoldEnd(down, GestureHandler.LONG_PRESS_SLOP_DP.dp.toPx())
                            }
                            ramp.cancel() // every resolution kills the ramp
                            when (outcome) {
                                null -> {
                                    // (a) Timeout: own the stream, reopen the
                                    // queue, drain.
                                    scope.launch { glow.snapTo(0f) }
                                    onHoldToOpen?.invoke()
                                    drainUntilLift()
                                }
                                HoldOutcome.Held -> {
                                    // (b) Early lift/pointer-lost: reverse ~120ms,
                                    // consume NOTHING (ambient sees unconsumed up
                                    // → Tap → quick-tap toggle kept), no drain,
                                    // no invoke.
                                    if (glow.value > 0f) {
                                        scope.launch { glow.animateTo(0f, tween(120)) }
                                    }
                                }
                                HoldOutcome.Abandoned -> {
                                    // (c) Drift past slop = OURS (intent owned:
                                    // glow started). Dead input: reverse + drain-
                                    // to-lift (suppresses ambient Tap resolution /
                                    // toggle bug). Neither open nor toggle.
                                    if (glow.value > 0f) {
                                        scope.launch { glow.animateTo(0f, tween(120)) }
                                    }
                                    drainUntilLift()
                                }
                            }
                        }
                    }
                },
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = title.ifBlank { "Toca para reproducir" },
            color = Color.White.copy(alpha = 0.72f + 0.28f * glow.value),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private enum class HoldOutcome { Held, Abandoned }

/**
 * Tracks only the [down]-finger until it resolves: lift / pointer-lost →
 * [HoldOutcome.Held]; cumulative path-length drift past [slopPx] →
 * [HoldOutcome.Abandoned]. Consumes NOTHING (the quick tap stays intact for
 * the ambient generator until a resolution branch says otherwise) and never
 * returns null — so a [kotlinx.coroutines.withTimeoutOrNull] null around it
 * unambiguously means the deadline fired.
 */
private suspend fun AwaitPointerEventScope.awaitHoldEnd(
    down: PointerInputChange,
    slopPx: Float,
): HoldOutcome {
    var last = down.position
    var drift = 0f
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id }
        if (change == null || !change.pressed) return HoldOutcome.Held
        drift += (change.position - last).getDistance()
        last = change.position
        if (drift > slopPx) return HoldOutcome.Abandoned
    }
}

/**
 * Consumes every change of the current gesture until no pointer is pressed:
 * dead-input capture so the ambient GestureHandler can neither resolve a Tap
 * nor step volume from this surface. Verbatim from the previous drain region.
 */
private suspend fun AwaitPointerEventScope.drainUntilLift() {
    while (true) {
        val drain = awaitPointerEvent()
        drain.changes.forEach { it.consume() }
        if (drain.changes.none { it.pressed }) break
    }
}
