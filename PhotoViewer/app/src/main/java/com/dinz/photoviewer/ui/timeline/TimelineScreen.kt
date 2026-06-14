package com.dinz.photoviewer.ui.timeline

import android.app.Activity
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.dinz.photoviewer.data.Photo
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Timeline (main grid) screen.
 *
 * Implements: date-grouped grid, header fade on scroll, pinch to switch density layers with a
 * rubber-band spring, and a fast-seek scrollbar that pops up the year/month while dragging.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitionScope.TimelineScreen(
    photos: List<Photo>,
    animatedVisibilityScope: AnimatedVisibilityScope,
    viewModel: TimelineViewModel,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    onPhotoClick: (Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var scale by remember { mutableStateOf(TimelineScale.COMFORTABLE) }
    val pinch = remember { Animatable(1f) }

    // Decode thumbnails near the on-screen cell size to minimise white-placeholder time.
    val thumbPx = when (scale) {
        TimelineScale.COMFORTABLE -> 512
        TimelineScale.COMPACT -> 256
        TimelineScale.MONTHLY -> 128
    }

    val selection by viewModel.selection.collectAsState()
    val selectionActive = selection.active

    val tagging by viewModel.tagging.collectAsState()

    // System back exits selection mode before leaving the screen.
    androidx.activity.compose.BackHandler(enabled = selectionActive) {
        viewModel.clearSelection()
    }

    // Index lookup: flat-item key -> original photo index (for click → lightbox).
    val timelineItems = remember(photos, scale) { buildTimeline(photos, scale) }
    val seekLabels = remember(timelineItems) { buildSeekLabels(timelineItems) }
    val photoIndexByItem = remember(timelineItems) {
        val map = HashMap<Long, Int>()
        photos.forEachIndexed { i, p -> map[p.id] = i }
        map
    }

    // Header visibility driven by scroll direction.
    val headerVisible by rememberScrollUpState(gridState)

    // Bulk delete via the system delete-confirmation dialog (API 30+).
    var pendingDeleteIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onDeleted(pendingDeleteIds)
        }
        pendingDeleteIds = emptySet()
    }

    fun requestDeleteSelected() {
        val ids = selection.selectedIds
        if (ids.isEmpty()) return
        val uris = photos.filter { it.id in ids }.map { it.uri }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pendingDeleteIds = ids
            val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, uris)
            deleteLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
        } else {
            // Legacy best-effort delete.
            scope.launch {
                uris.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
                viewModel.onDeleted(ids)
            }
        }
    }

    // Drag-select bookkeeping.
    var dragPointer by remember { mutableStateOf<Offset?>(null) }
    var autoScrollSpeed by remember { mutableFloatStateOf(0f) }

    fun photoIdAt(pos: Offset): Long? {
        gridState.layoutInfo.visibleItemsInfo.forEach { info ->
            val o = info.offset
            val s = info.size
            if (pos.x >= o.x && pos.x <= o.x + s.width && pos.y >= o.y && pos.y <= o.y + s.height) {
                val key = info.key as? String ?: return null
                return if (key.startsWith("p_")) key.removePrefix("p_").toLongOrNull() else null
            }
        }
        return null
    }

    // Edge auto-scroll loop while drag-selecting; keeps painting under the held finger.
    LaunchedEffect(selectionActive) {
        if (!selectionActive) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val speed = autoScrollSpeed
            if (speed != 0f) {
                gridState.scrollBy(speed)
                dragPointer?.let { p -> photoIdAt(p)?.let { viewModel.setSelected(it, true) } }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        LazyVerticalGrid(
            columns = GridCells.Fixed(scale.columns),
            state = gridState,
            contentPadding = PaddingValues(top = 56.dp, bottom = 24.dp, start = 2.dp, end = 2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = pinch.value
                    scaleY = pinch.value
                }
                .pointerInput(scale) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var didZoom = false
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.size >= 2) {
                                val zoom = event.calculateZoom()
                                if (zoom != 1f) {
                                    didZoom = true
                                    scope.launch {
                                        pinch.snapTo((pinch.value * zoom).coerceIn(0.6f, 1.8f))
                                    }
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        } while (event.changes.any { it.pressed })

                        if (didZoom) {
                            val finalZoom = pinch.value
                            val newScale = when {
                                finalZoom > 1.2f -> scale.zoomIn()
                                finalZoom < 0.82f -> scale.zoomOut()
                                else -> scale
                            }
                            if (newScale != scale) {
                                scale = newScale
                                // Subtle click when the density layer snaps to a new level.
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            scope.launch {
                                pinch.animateTo(
                                    1f,
                                    spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                                )
                            }
                        }
                    }
                }
                // Drag-select: paint photos under the finger; auto-scroll near the edges.
                .pointerInput(selectionActive) {
                    if (!selectionActive) return@pointerInput
                    val edge = 120.dp.toPx()
                    val maxSpeed = 36f
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        dragPointer = down.position
                        photoIdAt(down.position)?.let { viewModel.setSelected(it, true) }
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.pressed }
                            if (change != null) {
                                dragPointer = change.position
                                photoIdAt(change.position)?.let { viewModel.setSelected(it, true) }
                                val y = change.position.y
                                val h = size.height.toFloat()
                                autoScrollSpeed = when {
                                    y < edge -> -maxSpeed * (1f - (y / edge)).coerceIn(0f, 1f)
                                    y > h - edge -> maxSpeed * (1f - ((h - y) / edge)).coerceIn(0f, 1f)
                                    else -> 0f
                                }
                                if (change.positionChanged()) change.consume()
                            }
                        } while (event.changes.any { it.pressed })
                        autoScrollSpeed = 0f
                        dragPointer = null
                    }
                },
        ) {
            timelineItems.forEach { entry ->
                when (entry) {
                    is TimelineItem.Header -> {
                        item(key = entry.key, span = { GridItemSpan(maxLineSpan) }) {
                            DateHeader(label = entry.label, compact = scale == TimelineScale.MONTHLY)
                        }
                    }

                    is TimelineItem.PhotoCell -> {
                        item(key = entry.key) {
                            PhotoThumb(
                                photo = entry.photo,
                                rounded = scale != TimelineScale.MONTHLY,
                                targetPx = thumbPx,
                                selectionActive = selectionActive,
                                selected = entry.photo.id in selection.selectedIds,
                                modifier = Modifier
                                    .sharedElement(
                                        rememberSharedContentState(key = "photo-${entry.photo.id}"),
                                        animatedVisibilityScope = animatedVisibilityScope,
                                    ),
                                onClick = {
                                    photoIndexByItem[entry.photo.id]?.let(onPhotoClick)
                                },
                                onToggle = { viewModel.toggle(entry.photo.id) },
                                onLongPress = { viewModel.startSelection(entry.photo.id) },
                            )
                        }
                    }
                }
            }
        }

        // Auto-hiding title header (hidden while selecting).
        AnimatedVisibility(
            visible = headerVisible && !selectionActive,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopHeader(tagging = tagging)
        }

        // Selection action bar.
        AnimatedVisibility(
            visible = selectionActive,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            SelectionBar(
                count = selection.count,
                onClose = { viewModel.clearSelection() },
                onDelete = { requestDeleteSelected() },
            )
        }

        // Fast-seek scrollbar (hidden while selecting to avoid gesture conflicts).
        if (!selectionActive) {
            FastScrollbar(
                gridState = gridState,
                itemCount = timelineItems.size,
                seekLabels = seekLabels,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(top = 56.dp, bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun SelectionBar(count: Int, onClose: () -> Unit, onDelete: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.systemBars)
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "選択を解除",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = "${count}枚を選択中",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            )
            IconButton(onClick = onDelete, enabled = count > 0) {
                Icon(
                    Icons.Filled.DeleteOutline,
                    contentDescription = "削除",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun TopHeader(tagging: TaggingProgress) {
    Surface(
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.85f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.systemBars)
                .height(48.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "フォト",
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            if (tagging.running) {
                Text(
                    text = "タグ付け中 ${tagging.done}/${tagging.total}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
        }
    }
}


@Composable
private fun DateHeader(label: String, compact: Boolean) {
    Text(
        text = label,
        fontSize = if (compact) 13.sp else 15.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(start = 10.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun PhotoThumb(
    photo: Photo,
    rounded: Boolean,
    targetPx: Int,
    selectionActive: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    // Selected photos shrink slightly; the checkmark pops in with a bounce.
    val imageScale by animateFloatAsState(
        targetValue = if (selected) 0.82f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "imageScale",
    )
    val checkScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "checkScale",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(Color(0xFF1E1E1E))
            .pointerInput(photo.id, selectionActive) {
                detectTapGestures(
                    onTap = { if (selectionActive) onToggle() else onClick() },
                    onLongPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress()
                    },
                )
            },
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(photo.uri)
                .size(targetPx)
                .crossfade(150)
                .build(),
            contentDescription = photo.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = imageScale
                    scaleY = imageScale
                }
                .clip(if (rounded) RoundedCornerShape(6.dp) else RoundedCornerShape(1.dp)),
        )

        // Selection indicator.
        if (selectionActive) {
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .size(22.dp)
                        .graphicsLayer { scaleX = checkScale; scaleY = checkScale }
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(2.dp),
                )
            } else {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.25f))
                        .border(2.dp, Color.White, CircleShape),
                )
            }
        }
    }
}

// ---- small helpers ----

/** Tracks whether the user is scrolling up (header should show) or down (hide). */
@Composable
private fun rememberScrollUpState(
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
) = remember(gridState) {
    var previousIndex = gridState.firstVisibleItemIndex
    var previousOffset = gridState.firstVisibleItemScrollOffset
    derivedStateOf {
        if (gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0) {
            true
        } else {
            val up = if (previousIndex != gridState.firstVisibleItemIndex) {
                previousIndex > gridState.firstVisibleItemIndex
            } else {
                previousOffset >= gridState.firstVisibleItemScrollOffset
            }
            previousIndex = gridState.firstVisibleItemIndex
            previousOffset = gridState.firstVisibleItemScrollOffset
            up
        }
    }
}

/**
 * Right-edge fast-seek indicator. Dragging scrolls the grid proportionally and shows the
 * year/month bubble for the position under the thumb.
 */
@Composable
private fun FastScrollbar(
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    itemCount: Int,
    seekLabels: List<SeekLabel>,
    modifier: Modifier = Modifier,
) {
    if (itemCount == 0) return
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var fraction by remember { mutableFloatStateOf(0f) }

    // Reflect natural scroll position onto the thumb when not dragging.
    val displayFraction by remember {
        derivedStateOf {
            if (dragging) fraction
            else if (itemCount <= 1) 0f
            else gridState.firstVisibleItemIndex.toFloat() / (itemCount - 1)
        }
    }

    val currentLabel by remember {
        derivedStateOf {
            val targetIndex = (displayFraction * (itemCount - 1)).roundToInt()
            seekLabels.lastOrNull { it.itemIndex <= targetIndex }?.text
                ?: seekLabels.firstOrNull()?.text ?: ""
        }
    }

    Box(modifier = modifier.width(48.dp)) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxHeight()
                .width(36.dp)
                .pointerInput(itemCount) {
                    detectVerticalDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, _ ->
                        val h = size.height.toFloat().coerceAtLeast(1f)
                        fraction = (change.position.y / h).coerceIn(0f, 1f)
                        val target = (fraction * (itemCount - 1)).roundToInt()
                        scope.launch { gridState.scrollToItem(target) }
                    }
                },
        ) {
            // Thumb
            var trackHeightPx by remember { mutableFloatStateOf(0f) }
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(36.dp)
                    .onSizeChangedTrack { trackHeightPx = it },
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 4.dp)
                        .graphicsLayer { translationY = displayFraction * trackHeightPx * 0.92f }
                        .size(width = 8.dp, height = 44.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (dragging) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f)
                        ),
                )
            }
        }

        // Year/month popup bubble while dragging.
        if (dragging && currentLabel.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(end = 8.dp),
            ) {
                Text(
                    text = currentLabel,
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

private fun Modifier.onSizeChangedTrack(onChange: (Float) -> Unit): Modifier =
    this.onSizeChanged { onChange(it.height.toFloat()) }
