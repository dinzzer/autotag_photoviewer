package com.dinz.photoviewer.ui.lightbox

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dinz.photoviewer.data.Photo
import com.dinz.photoviewer.data.tagging.Tag
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

private val infoDateFormat = SimpleDateFormat("yyyy年M月d日(E) HH:mm", Locale.JAPAN)

/**
 * Lightbox (full-screen detail) screen. Hosts a pager of [ZoomableImage] pages, an auto-hiding
 * overlay chrome, swipe-down-to-dismiss with background fade, and a swipe-up tag panel.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitionScope.LightboxScreen(
    photos: List<Photo>,
    initialIndex: Int,
    animatedVisibilityScope: AnimatedVisibilityScope,
    knownTags: List<String>,
    tagsFlow: (Long) -> Flow<List<Tag>>,
    ensureTags: suspend (Photo) -> Unit,
    onAddTag: (Long, String) -> Unit,
    onRemoveTag: (Long, String) -> Unit,
    onClose: () -> Unit,
) {
    if (photos.isEmpty()) return
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, photos.lastIndex),
        pageCount = { photos.size },
    )

    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val dismissThresholdPx = screenHeightPx * 0.2f

    var overlayVisible by remember { mutableStateOf(true) }
    var tagsVisible by remember { mutableStateOf(false) }
    var zoomed by remember { mutableStateOf(false) }
    var dismissPx by remember { mutableFloatStateOf(0f) }

    // System back: close the tag panel first, then close the lightbox (→ back to the grid).
    androidx.activity.compose.BackHandler {
        if (tagsVisible) tagsVisible = false else onClose()
    }

    // Background opacity falls off as the photo is dragged toward dismissal.
    val dismissFraction = (abs(dismissPx) / (screenHeightPx * 0.6f)).coerceIn(0f, 1f)
    val bgAlpha = 1f - dismissFraction * 0.85f

    val currentPhoto = photos[pagerState.currentPage.coerceIn(0, photos.lastIndex)]

    // Live tags for the current photo from the cache (reflects edits); compute on first view.
    val tags by remember(currentPhoto.id) { tagsFlow(currentPhoto.id) }
        .collectAsState(initial = emptyList())
    LaunchedEffect(currentPhoto.id) { ensureTags(currentPhoto) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = bgAlpha)),
    ) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !zoomed,
            pageSpacing = 16.dp, // margin between photos (spec 4.3)
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val photo = photos[page]
            val isCurrent = page == pagerState.currentPage
            ZoomableImage(
                photo = photo,
                dismissThresholdPx = dismissThresholdPx,
                onTap = { overlayVisible = !overlayVisible },
                onDismissProgress = { if (isCurrent) dismissPx = it },
                onDismissCommit = onClose,
                onRevealTags = { if (isCurrent) tagsVisible = true },
                onZoomChanged = { if (isCurrent) zoomed = it },
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (isCurrent) {
                            Modifier.sharedElement(
                                rememberSharedContentState(key = "photo-${photo.id}"),
                                animatedVisibilityScope = animatedVisibilityScope,
                            )
                        } else Modifier
                    ),
            )
        }

        // Top overlay chrome.
        AnimatedVisibility(
            visible = overlayVisible && !zoomed,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopBar(
                dateText = infoDateFormat.format(Date(currentPhoto.dateTaken)),
                onBack = onClose,
            )
        }

        // Bottom: swipe-up handle to reveal the tag panel.
        AnimatedVisibility(
            visible = overlayVisible && !zoomed && !tagsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            SwipeUpHandle(onOpen = { tagsVisible = true })
        }

        // Tag panel (auto tags), slides up from the bottom.
        AnimatedVisibility(
            visible = tagsVisible && !zoomed,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            TagPanel(
                photo = currentPhoto,
                tags = tags,
                knownTags = knownTags,
                onAddTag = { onAddTag(currentPhoto.id, it) },
                onRemoveTag = { onRemoveTag(currentPhoto.id, it) },
                onClose = { tagsVisible = false },
            )
        }
    }
}

@Composable
private fun TopBar(dateText: String, onBack: () -> Unit) {
    Surface(color = Color.Black.copy(alpha = 0.35f), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "戻る",
                    tint = Color.White,
                )
            }
            Text(
                text = dateText,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp),
            )
            IconButton(onClick = { /* delete: wire to MediaStore delete intent later */ }) {
                Icon(Icons.Filled.DeleteOutline, contentDescription = "削除", tint = Color.White)
            }
        }
    }
}

@Composable
private fun SwipeUpHandle(onOpen: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 20.dp)
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, dragAmount ->
                    if (dragAmount < -6f) onOpen() // dragging up
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Sell,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "  タグを表示  ▲",
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 13.sp,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagPanel(
    photo: Photo,
    tags: List<Tag>,
    knownTags: List<String>,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
    onClose: () -> Unit,
) {
    var editMode by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val currentNames = remember(tags) { tags.map { it.name }.toHashSet() }

    Surface(
        color = Color(0xF21A1A1A),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                // Only allow swipe-down-to-close when not editing (avoids fighting the keyboard).
                .pointerInput(editMode) {
                    if (!editMode) {
                        detectVerticalDragGestures { _, dragAmount ->
                            if (dragAmount > 6f) onClose()
                        }
                    }
                },
        ) {
            Box(
                modifier = Modifier
                    .padding(bottom = 10.dp)
                    .align(Alignment.CenterHorizontally)
                    .size(width = 36.dp, height = 4.dp)
                    .background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(2.dp)),
            )

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "タグ",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                    )
                    Text(
                        text = "${photo.width} × ${photo.height}",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                    )
                }
                Text(
                    text = if (editMode) "完了" else "編集",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .pointerInput(Unit) { detectTapGestures { editMode = !editMode } }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }

            Spacer(Modifier.height(10.dp))

            if (tags.isEmpty()) {
                Text(
                    text = if (editMode) "タグを追加してください" else "タグがありません",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 13.sp,
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    tags.forEach { tag ->
                        EditableTagChip(
                            tag = tag,
                            editMode = editMode,
                            onRemove = { onRemoveTag(tag.name) },
                        )
                    }
                }
            }

            if (editMode) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        singleLine = true,
                        placeholder = { Text("タグを入力して追加") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (input.isNotBlank()) { onAddTag(input.trim()); input = "" }
                        }),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        if (input.isNotBlank()) { onAddTag(input.trim()); input = "" }
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = "追加", tint = Color.White)
                    }
                }

                val suggestions = remember(knownTags, currentNames) {
                    knownTags.filterNot { it in currentNames }.take(40)
                }
                if (suggestions.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "候補から追加",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        suggestions.forEach { name ->
                            AssistChip(
                                onClick = { onAddTag(name) },
                                label = { Text(name, fontSize = 13.sp) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditableTagChip(tag: Tag, editMode: Boolean, onRemove: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
        shape = RoundedCornerShape(16.dp),
        modifier = if (editMode) {
            Modifier.pointerInput(tag.name) { detectTapGestures { onRemove() } }
        } else Modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = tag.name, color = Color.White, fontSize = 13.sp)
            if (editMode) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "削除",
                    tint = Color.White,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(16.dp),
                )
            } else {
                Text(
                    text = "  ${(min(tag.confidence, 1f) * 100).toInt()}%",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                )
            }
        }
    }
}
