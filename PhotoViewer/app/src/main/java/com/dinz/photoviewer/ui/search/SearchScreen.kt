package com.dinz.photoviewer.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.dinz.photoviewer.data.Photo
import com.dinz.photoviewer.ui.timeline.AlbumCover
import com.dinz.photoviewer.ui.timeline.SearchMode
import com.dinz.photoviewer.ui.timeline.TimelineItem
import com.dinz.photoviewer.ui.timeline.TimelineScale
import com.dinz.photoviewer.ui.timeline.TimelineViewModel
import com.dinz.photoviewer.ui.timeline.buildTimeline

/**
 * Search tab. Two states:
 *  - No tags selected → an album overview (one card per tag: representative photo + name + count).
 *  - Tags selected → the photo results for those tags, with a filter bar on top that auto-fades on
 *    scroll. Tapping an album card selects that tag and enters the results view; system back / clear
 *    returns to the album overview.
 */
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalLayoutApi::class)
@Composable
fun SharedTransitionScope.SearchScreen(
    viewModel: TimelineViewModel,
    animatedVisibilityScope: AnimatedVisibilityScope,
    resultsGridState: LazyGridState,
    onPhotoClick: (Int) -> Unit,
) {
    val selected by viewModel.searchTags.collectAsState()

    // Back returns from the results view to the album overview.
    BackHandler(enabled = selected.isNotEmpty()) { viewModel.clearSearch() }

    if (selected.isEmpty()) {
        AlbumOverview(viewModel = viewModel, onPickTag = { viewModel.toggleSearchTag(it) })
    } else {
        ResultsView(
            viewModel = viewModel,
            animatedVisibilityScope = animatedVisibilityScope,
            resultsGridState = resultsGridState,
            onPhotoClick = onPhotoClick,
        )
    }
}

@Composable
private fun AlbumOverview(
    viewModel: TimelineViewModel,
    onPickTag: (String) -> Unit,
) {
    val albums by viewModel.albums.collectAsState()
    val text by viewModel.searchText.collectAsState()
    val filtered = remember(albums, text) {
        if (text.isBlank()) albums
        else albums.filter { it.tag.contains(text.trim(), ignoreCase = true) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = viewModel::setSearchText,
                singleLine = true,
                placeholder = { Text("タグを検索 / 選んでアルバムを開く") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.commitSearchText() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "アルバム（${filtered.size}）",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = viewModel.engineName,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                )
            }
        }

        if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "タグがまだありません（タグ付けの完了をお待ちください）",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                filtered.forEach { album ->
                    item(key = "album_${album.tag}") {
                        AlbumCard(album = album, onClick = { onPickTag(album.tag) })
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumCard(album: AlbumCover, onClick: () -> Unit) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1E1E1E))
            .pointerInput(album.tag) { detectTapGestures { onClick() } },
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(album.photo.uri).size(512).crossfade(150).build(),
            contentDescription = album.tag,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)),
                    ),
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Column {
                Text(
                    text = album.tag,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    text = "${album.count}枚",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalLayoutApi::class)
@Composable
private fun SharedTransitionScope.ResultsView(
    viewModel: TimelineViewModel,
    animatedVisibilityScope: AnimatedVisibilityScope,
    resultsGridState: LazyGridState,
    onPhotoClick: (Int) -> Unit,
) {
    val suggestions by viewModel.tagSuggestions.collectAsState()
    val selected by viewModel.searchTags.collectAsState()
    val mode by viewModel.searchMode.collectAsState()
    val text by viewModel.searchText.collectAsState()
    val results by viewModel.searchResults.collectAsState()

    val groupedItems = remember(results) { buildTimeline(results, TimelineScale.COMPACT) }
    val indexById = remember(results) {
        HashMap<Long, Int>().apply { results.forEachIndexed { i, p -> put(p.id, i) } }
    }
    // The tag list auto-collapses while scrolling down (like the timeline header).
    val chipsVisible by rememberGridScrollUp(resultsGridState)

    Column(modifier = Modifier.fillMaxSize()) {
        // Pinned compact controls: text field + AND/OR + clear.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = viewModel::setSearchText,
                singleLine = true,
                placeholder = { Text("タグを追加して絞り込み") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.commitSearchText() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = mode == SearchMode.AND,
                    onClick = { viewModel.setSearchMode(SearchMode.AND) },
                    label = { Text("AND") },
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = mode == SearchMode.OR,
                    onClick = { viewModel.setSearchMode(SearchMode.OR) },
                    label = { Text("OR") },
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "アルバムへ",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .pointerInput(Unit) { detectTapGestures { viewModel.clearSearch() } }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

        // Auto-fading tag chips (selected + suggestions).
        AnimatedVisibility(
            visible = chipsVisible,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    selected.forEach { tag ->
                        InputChip(
                            selected = true,
                            onClick = { viewModel.toggleSearchTag(tag) },
                            label = { Text(tag, fontSize = 13.sp) },
                            trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "外す") },
                        )
                    }
                }
                val suggestable = suggestions.filterNot { it in selected }.take(40)
                if (suggestable.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(top = 6.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        suggestable.forEach { tag ->
                            AssistChip(
                                onClick = { viewModel.toggleSearchTag(tag) },
                                label = { Text(tag, fontSize = 13.sp) },
                            )
                        }
                    }
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            if (results.isEmpty()) {
                Text(
                    text = "該当する写真がありません",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(TimelineScale.COMPACT.columns),
                    state = resultsGridState,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    groupedItems.forEach { entry ->
                        when (entry) {
                            is TimelineItem.Header -> item(
                                key = entry.key,
                                span = { GridItemSpan(maxLineSpan) },
                            ) {
                                Text(
                                    text = entry.label,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.padding(start = 10.dp, top = 12.dp, bottom = 4.dp),
                                )
                            }

                            is TimelineItem.PhotoCell -> item(key = entry.key) {
                                ResultThumb(
                                    photo = entry.photo,
                                    modifier = Modifier.sharedElement(
                                        rememberSharedContentState(key = "photo-${entry.photo.id}"),
                                        animatedVisibilityScope = animatedVisibilityScope,
                                    ),
                                    onClick = { indexById[entry.photo.id]?.let(onPhotoClick) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultThumb(
    photo: Photo,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF1E1E1E))
            .pointerInput(photo.id) { detectTapGestures { onClick() } },
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(photo.uri).size(256).crossfade(150).build(),
            contentDescription = photo.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** True when at the top or scrolling up; false when scrolling down. */
@Composable
private fun rememberGridScrollUp(state: LazyGridState) = remember(state) {
    var prevIndex = state.firstVisibleItemIndex
    var prevOffset = state.firstVisibleItemScrollOffset
    derivedStateOf {
        if (state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0) {
            true
        } else {
            val up = if (prevIndex != state.firstVisibleItemIndex) {
                prevIndex > state.firstVisibleItemIndex
            } else {
                prevOffset >= state.firstVisibleItemScrollOffset
            }
            prevIndex = state.firstVisibleItemIndex
            prevOffset = state.firstVisibleItemScrollOffset
            up
        }
    }
}
