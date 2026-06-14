package com.dinz.photoviewer.ui

import android.Manifest
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dinz.photoviewer.R
import com.dinz.photoviewer.data.Photo
import com.dinz.photoviewer.ui.lightbox.LightboxScreen
import com.dinz.photoviewer.ui.search.SearchScreen
import com.dinz.photoviewer.ui.theme.PhotoViewerTheme
import com.dinz.photoviewer.ui.timeline.TimelineScreen
import com.dinz.photoviewer.ui.timeline.TimelineViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

private enum class AppTab { TIMELINE, SEARCH }

private data class LightboxRequest(val photos: List<Photo>, val index: Int)

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PhotoViewerApp(viewModel: TimelineViewModel) {
    PhotoViewerTheme {
        PermissionGate(onGranted = { viewModel.loadPhotos() }) {
            val state by viewModel.uiState.collectAsState()

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    state.loading -> {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    }

                    state.photos.isEmpty() -> {
                        Text(
                            text = state.errorMessage ?: stringResource(R.string.no_photos),
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }

                    else -> {
                        val timelinePhotos = state.photos
                        val searchResults by viewModel.searchResults.collectAsState()
                        val knownTags by viewModel.availableTags.collectAsState()
                        val selection by viewModel.selection.collectAsState()
                        var tab by remember { mutableStateOf(AppTab.TIMELINE) }
                        var lightbox by remember { mutableStateOf<LightboxRequest?>(null) }
                        // Hoisted so scroll position (and the hero return target) survive the lightbox.
                        val timelineGridState = rememberLazyGridState()
                        val searchGridState = rememberLazyGridState()

                        SharedTransitionLayout {
                            val sharedScope = this
                            AnimatedContent(
                                targetState = lightbox,
                                transitionSpec = { fadeIn() togetherWith fadeOut() },
                                label = "lightbox",
                            ) { request ->
                                val avScope = this
                                if (request == null) {
                                    // Search tab (album view, no deeper state) → back returns to the
                                    // timeline tab. Deeper states (selection, search filter) are
                                    // handled by the screens' own BackHandlers, which take priority.
                                    val searchTags by viewModel.searchTags.collectAsState()
                                    androidx.activity.compose.BackHandler(
                                        enabled = tab == AppTab.SEARCH &&
                                            searchTags.isEmpty() &&
                                            !selection.active,
                                    ) { tab = AppTab.TIMELINE }

                                    Column(modifier = Modifier.fillMaxSize()) {
                                        Box(modifier = Modifier.weight(1f)) {
                                            when (tab) {
                                                AppTab.TIMELINE -> with(sharedScope) {
                                                    TimelineScreen(
                                                        photos = timelinePhotos,
                                                        animatedVisibilityScope = avScope,
                                                        viewModel = viewModel,
                                                        gridState = timelineGridState,
                                                        onPhotoClick = {
                                                            lightbox = LightboxRequest(timelinePhotos, it)
                                                        },
                                                    )
                                                }

                                                AppTab.SEARCH -> with(sharedScope) {
                                                    SearchScreen(
                                                        viewModel = viewModel,
                                                        animatedVisibilityScope = avScope,
                                                        resultsGridState = searchGridState,
                                                        onPhotoClick = {
                                                            lightbox = LightboxRequest(searchResults, it)
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                        // Hide the tab bar during multi-select to free the screen.
                                        if (!selection.active) {
                                            NavigationBar {
                                                NavigationBarItem(
                                                    selected = tab == AppTab.TIMELINE,
                                                    onClick = { tab = AppTab.TIMELINE },
                                                    icon = {
                                                        Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
                                                    },
                                                    label = { Text("フォト") },
                                                )
                                                NavigationBarItem(
                                                    selected = tab == AppTab.SEARCH,
                                                    onClick = { tab = AppTab.SEARCH },
                                                    icon = {
                                                        Icon(Icons.Filled.Search, contentDescription = null)
                                                    },
                                                    label = { Text("検索") },
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    with(sharedScope) {
                                        LightboxScreen(
                                            photos = request.photos,
                                            initialIndex = request.index,
                                            animatedVisibilityScope = avScope,
                                            knownTags = knownTags,
                                            tagsFlow = { viewModel.observePhotoTags(it) },
                                            ensureTags = { viewModel.ensureTagsForPhoto(it) },
                                            onAddTag = { id, t -> viewModel.addTagToPhoto(id, t) },
                                            onRemoveTag = { id, t -> viewModel.removeTagFromPhoto(id, t) },
                                            onClose = { lightbox = null },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun PermissionGate(
    onGranted: () -> Unit,
    content: @Composable () -> Unit,
) {
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val permissionState = rememberPermissionState(permission)
    val granted = permissionState.status.isGranted

    LaunchedEffect(granted) {
        if (granted) onGranted()
    }

    if (granted) {
        content()
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.layout.Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.permission_rationale),
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(24.dp),
                )
                Button(onClick = { permissionState.launchPermissionRequest() }) {
                    Text(stringResource(R.string.grant_permission))
                }
            }
        }
    }
}
