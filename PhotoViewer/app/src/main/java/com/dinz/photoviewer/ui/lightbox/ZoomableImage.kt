package com.dinz.photoviewer.ui.lightbox

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.dinz.photoviewer.data.Photo
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val MAX_SCALE = 5f
private const val DOUBLE_TAP_SCALE = 2.5f

/**
 * Single zoomable photo page used inside the lightbox pager.
 *
 * Gestures:
 *  - double tap: zoom to the tapped point (2.5x) / back to fit.
 *  - pinch: free zoom 1x–5x, with panning while zoomed (offset clamped to the image bounds).
 *  - single tap: toggles the overlay UI (delegated upward).
 *  - vertical drag DOWN while at fit-scale: swipe-to-dismiss (close → grid).
 *  - vertical drag UP while at fit-scale: reveals the tag panel (does NOT close).
 *  - horizontal drags at fit-scale are left unconsumed so the pager can change photos.
 *
 * @param onTap toggles overlay chrome.
 * @param onDismissProgress reports the live downward dismiss translation in px (0 = none).
 * @param onDismissCommit fired when the swipe-down passes the threshold → close the lightbox.
 * @param onRevealTags fired when the user swipes up at fit-scale → show the tag panel.
 * @param onZoomChanged reports whether the page is currently zoomed (pager scroll is disabled then).
 */
@Composable
fun ZoomableImage(
    photo: Photo,
    dismissThresholdPx: Float,
    onTap: () -> Unit,
    onDismissProgress: (Float) -> Unit,
    onDismissCommit: () -> Unit,
    onRevealTags: () -> Unit,
    onZoomChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val scale = remember { Animatable(1f) }
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    val dismissY = remember { Animatable(0f) }

    var containerW by remember { mutableFloatStateOf(0f) }
    var containerH by remember { mutableFloatStateOf(0f) }

    fun maxOffsetX() = (containerW * (scale.value - 1f) / 2f).coerceAtLeast(0f)
    fun maxOffsetY() = (containerH * (scale.value - 1f) / 2f).coerceAtLeast(0f)

    fun clampOffsets() {
        scope.launch { offsetX.snapTo(offsetX.value.coerceIn(-maxOffsetX(), maxOffsetX())) }
        scope.launch { offsetY.snapTo(offsetY.value.coerceIn(-maxOffsetY(), maxOffsetY())) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged {
                containerW = it.width.toFloat()
                containerH = it.height.toFloat()
            }
            // Tap / double-tap handling.
            .pointerInput(photo.id) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tap ->
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (scale.value > 1.05f) {
                            scope.launch {
                                scale.animateTo(1f, spring())
                            }
                            scope.launch { offsetX.animateTo(0f, spring()) }
                            scope.launch { offsetY.animateTo(0f, spring()) }
                            onZoomChanged(false)
                        } else {
                            // Zoom toward the tapped point.
                            val targetScale = DOUBLE_TAP_SCALE
                            val cx = containerW / 2f
                            val cy = containerH / 2f
                            val focusX = (cx - tap.x) * (targetScale - 1f)
                            val focusY = (cy - tap.y) * (targetScale - 1f)
                            scope.launch { scale.animateTo(targetScale, spring()) }
                            scope.launch {
                                offsetX.animateTo(
                                    focusX.coerceIn(
                                        -(containerW * (targetScale - 1f) / 2f),
                                        (containerW * (targetScale - 1f) / 2f),
                                    ),
                                    spring(),
                                )
                            }
                            scope.launch {
                                offsetY.animateTo(
                                    focusY.coerceIn(
                                        -(containerH * (targetScale - 1f) / 2f),
                                        (containerH * (targetScale - 1f) / 2f),
                                    ),
                                    spring(),
                                )
                            }
                            onZoomChanged(true)
                        }
                    },
                )
            }
            // Pinch / pan / swipe-to-dismiss.
            .pointerInput(photo.id) {
                detectTransformAndDismiss(
                    isZoomed = { scale.value > 1.05f },
                    onZoom = { zoom ->
                        val newScale = (scale.value * zoom).coerceIn(1f, MAX_SCALE)
                        scope.launch { scale.snapTo(newScale) }
                        onZoomChanged(newScale > 1.05f)
                    },
                    onPan = { pan ->
                        scope.launch {
                            offsetX.snapTo(
                                (offsetX.value + pan.x).coerceIn(-maxOffsetX(), maxOffsetX())
                            )
                        }
                        scope.launch {
                            offsetY.snapTo(
                                (offsetY.value + pan.y).coerceIn(-maxOffsetY(), maxOffsetY())
                            )
                        }
                    },
                    onDismissDrag = { dy ->
                        scope.launch { dismissY.snapTo(dismissY.value + dy) }
                        onDismissProgress(dismissY.value)
                    },
                    onRevealUp = onRevealTags,
                    onDismissEnd = {
                        if (abs(dismissY.value) > dismissThresholdPx) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDismissCommit()
                        } else {
                            scope.launch {
                                dismissY.animateTo(
                                    0f,
                                    spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                                )
                                onDismissProgress(0f)
                            }
                        }
                    },
                    onPanEnd = {
                        // Settle after a pinch/pan: clamp offsets, or snap back to fit if barely zoomed.
                        if (scale.value <= 1.05f) {
                            scope.launch { scale.animateTo(1f, spring()) }
                            scope.launch { offsetX.animateTo(0f, spring()) }
                            scope.launch { offsetY.animateTo(0f, spring()) }
                            onZoomChanged(false)
                        } else {
                            clampOffsets()
                        }
                    },
                )
            },
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(photo.uri)
                .crossfade(true)
                .build(),
            contentDescription = photo.displayName,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    translationX = offsetX.value
                    translationY = offsetY.value + dismissY.value
                    // Shrink slightly while dragging down to dismiss for a tactile feel.
                    val shrink = (1f - (abs(dismissY.value) / (containerH.coerceAtLeast(1f) * 2f)))
                        .coerceIn(0.8f, 1f)
                    if (scale.value <= 1.05f) {
                        scaleX *= shrink
                        scaleY *= shrink
                    }
                },
        )
    }
}

/**
 * Custom gesture loop combining pinch-zoom, pan (when zoomed) and a vertical swipe-to-dismiss
 * (when at fit-scale). Horizontal drags at fit-scale are left unconsumed so the parent pager
 * can page between photos.
 */
private suspend fun PointerInputScope.detectTransformAndDismiss(
    isZoomed: () -> Boolean,
    onZoom: (Float) -> Unit,
    onPan: (Offset) -> Unit,
    onDismissDrag: (Float) -> Unit,
    onRevealUp: () -> Unit,
    onDismissEnd: () -> Unit,
    onPanEnd: () -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var lockedToDismiss = false
        var lockedToReveal = false
        var lockedToPage = false
        var didPinchOrPan = false
        do {
            val event = awaitPointerEvent()
            val zoom = event.calculateZoom()
            val pan = event.calculatePan()
            val pointerCount = event.changes.count { it.pressed }

            if (pointerCount >= 2) {
                // Two fingers → pinch + pan the (zoomed) image.
                didPinchOrPan = true
                if (zoom != 1f) onZoom(zoom)
                if (isZoomed()) onPan(pan)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            } else if (isZoomed()) {
                // One finger while zoomed → pan within the image.
                didPinchOrPan = true
                onPan(pan)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            } else {
                // One finger at fit-scale → down = dismiss, up = reveal tags,
                // horizontal = paging (left unconsumed for the pager).
                if (!lockedToPage && !lockedToDismiss && !lockedToReveal) {
                    if (abs(pan.y) > abs(pan.x) && pan.y > 4f) {
                        lockedToDismiss = true
                    } else if (abs(pan.y) > abs(pan.x) && pan.y < -4f) {
                        lockedToReveal = true
                        onRevealUp()
                    } else if (abs(pan.x) > abs(pan.y) && abs(pan.x) > 4f) {
                        lockedToPage = true
                    }
                }
                if (lockedToDismiss) {
                    onDismissDrag(pan.y)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                } else if (lockedToReveal) {
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            }
        } while (event.changes.any { it.pressed })

        // Only settle when the gesture actually manipulated the image — a pure tap or a
        // horizontal page-swipe must not disturb the current zoom/dismiss state.
        when {
            lockedToDismiss -> onDismissEnd()
            didPinchOrPan -> onPanEnd()
        }
    }
}
