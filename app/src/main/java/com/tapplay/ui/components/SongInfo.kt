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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.view.ViewConfiguration
import kotlinx.coroutines.launch

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
 * toggle or a volume step from this now-dead surface.
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
                            // Visual ramp only: pure VFX, reset any drift from the
                            // previous gesture. It is NOT the completion source of
                            // truth (the in-loop uptime check below is) and never
                            // invokes anything.
                            val ramp = scope.launch {
                                glow.snapTo(0f)
                                glow.animateTo(1f, tween(holdTimeoutMs, easing = LinearEasing))
                            }
                            // Explicit event loop. NOTHING is consumed before the
                            // hold timeout fires — that preserves the quick tap by
                            // construction: the ambient generator still sees an
                            // unconsumed up → Tap(CENTER) (parent toggles playback).
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null || !change.pressed) {
                                    // Early lift / cancel (or pointer lost): undo the
                                    // ramp, consume nothing — gesture ends passively.
                                    ramp.cancel()
                                    if (glow.value > 0f) {
                                        scope.launch { glow.animateTo(0f, tween(120)) }
                                    }
                                    return@awaitEachGesture
                                }
                                if (change.uptimeMillis - down.uptimeMillis >= holdTimeoutMs) {
                                    // Hold complete: own the pointer stream. Kill the
                                    // ramp, reset the glow, consume the completion
                                    // event, THEN reopen the queue, then drain every
                                    // remaining event until the finger lifts — all
                                    // changes consumed.
                                    // Known accepted micro-race (proposal): a finger can
                                    // cross the ~50dp swipe threshold inside the ~1-frame
                                    // window between ramp completion and this check —
                                    // ambient may emit ≤1 stray volume step. Accept (rare).
                                    ramp.cancel()
                                    // snapTo is a plain suspend fn: not callable in
                                    // the restricted AwaitPointerEventScope — fire it
                                    // on the normal scope (visual reset only).
                                    scope.launch { glow.snapTo(0f) }
                                    event.changes.forEach { it.consume() }
                                    onHoldToOpen?.invoke()
                                    break
                                }
                            }
                            while (true) {
                                val drain = awaitPointerEvent()
                                drain.changes.forEach { it.consume() }
                                if (drain.changes.none { it.pressed }) break
                            }
                            // Drain ended with the final lift → awaitEachGesture
                            // starts a clean, fresh gesture on the next down.
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
