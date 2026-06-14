package com.dinz.photoviewer.ui.timeline

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dinz.photoviewer.data.MediaStoreRepository
import com.dinz.photoviewer.data.Photo
import com.dinz.photoviewer.data.tagging.Tag
import com.dinz.photoviewer.data.tagging.TagRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TimelineUiState(
    val loading: Boolean = true,
    val photos: List<Photo> = emptyList(),
    val errorMessage: String? = null,
)

/** Multi-select state for bulk organising (spec 4.6). */
data class SelectionState(
    val active: Boolean = false,
    val selectedIds: Set<Long> = emptySet(),
) {
    val count: Int get() = selectedIds.size
}

/** Tag search combination mode. */
enum class SearchMode { AND, OR }

/** A tag album: representative photo and how many photos carry the tag. */
data class AlbumCover(val tag: String, val photo: Photo, val count: Int)

/** Progress of the background auto-tagging pass. */
data class TaggingProgress(
    val done: Int = 0,
    val total: Int = 0,
) {
    val running: Boolean get() = total > 0 && done < total
}

@OptIn(ExperimentalCoroutinesApi::class)
class TimelineViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MediaStoreRepository(application)
    private val tagRepository = TagRepository(application)

    private val _uiState = MutableStateFlow(TimelineUiState())
    val uiState: StateFlow<TimelineUiState> = _uiState.asStateFlow()

    // ---- Auto-tagging (spec 6) ----

    /** Active engine label (e.g. "WD Tagger v3 (ONNX)" or the stub fallback). */
    val engineName: String = tagRepository.engineName

    val availableTags: StateFlow<List<String>> = tagRepository.observeAllTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Tag "albums": representative photo + count, for the search overview grid. */
    val albums: StateFlow<List<AlbumCover>> = combine(
        _uiState,
        tagRepository.observeTagCovers(),
    ) { state, covers ->
        val byId = state.photos.associateBy { it.id }
        covers.mapNotNull { c -> byId[c.photoId]?.let { AlbumCover(c.tag, it, c.count) } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _tagging = MutableStateFlow(TaggingProgress())
    val tagging: StateFlow<TaggingProgress> = _tagging.asStateFlow()

    // ---- Search (tag filtering lives on the dedicated Search screen) ----

    private val _searchTags = MutableStateFlow<Set<String>>(emptySet())
    val searchTags: StateFlow<Set<String>> = _searchTags.asStateFlow()

    private val _searchMode = MutableStateFlow(SearchMode.AND)
    val searchMode: StateFlow<SearchMode> = _searchMode.asStateFlow()

    private val _searchText = MutableStateFlow("")
    val searchText: StateFlow<String> = _searchText.asStateFlow()

    /** Known tags filtered by the current text query (chip suggestions). */
    val tagSuggestions: StateFlow<List<String>> = combine(availableTags, _searchText) { tags, q ->
        if (q.isBlank()) tags else tags.filter { it.contains(q.trim(), ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Photos matching the selected search tags via AND/OR. Empty selection => empty results. */
    val searchResults: StateFlow<List<Photo>> = combine(
        _uiState,
        combine(_searchTags, _searchMode) { tags, mode -> tags to mode }
            .flatMapLatest { (tags, mode) ->
                when {
                    tags.isEmpty() -> flowOf<Set<Long>?>(null)
                    mode == SearchMode.AND ->
                        tagRepository.observePhotosWithAllTags(tags.toList()).map { it.toHashSet() }
                    else ->
                        tagRepository.observePhotosWithAnyTags(tags.toList()).map { it.toHashSet() }
                }
            },
    ) { state, ids ->
        if (ids == null) emptyList() else state.photos.filter { it.id in ids }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setSearchText(text: String) { _searchText.value = text }

    fun setSearchMode(mode: SearchMode) { _searchMode.value = mode }

    fun toggleSearchTag(tag: String) {
        val current = _searchTags.value
        _searchTags.value = if (tag in current) current - tag else current + tag
    }

    /** Commit the free-text field as a search tag (allows tags not in the suggestion list). */
    fun commitSearchText() {
        val t = _searchText.value.trim()
        if (t.isNotEmpty()) {
            _searchTags.value = _searchTags.value + t
            _searchText.value = ""
        }
    }

    fun clearSearch() {
        _searchTags.value = emptySet()
        _searchText.value = ""
    }

    // ---- Per-photo tags & manual editing ----

    fun observePhotoTags(photoId: Long): Flow<List<Tag>> =
        tagRepository.observeTagsForPhoto(photoId)

    /** Computes+caches tags for a photo if not done yet (no-op if already tagged). */
    suspend fun ensureTagsForPhoto(photo: Photo) {
        tagRepository.tagsForPhoto(photo)
    }

    fun addTagToPhoto(photoId: Long, tag: String) {
        viewModelScope.launch { tagRepository.addTag(photoId, tag) }
    }

    fun removeTagFromPhoto(photoId: Long, tag: String) {
        viewModelScope.launch { tagRepository.removeTag(photoId, tag) }
    }

    private val _selection = MutableStateFlow(SelectionState())
    val selection: StateFlow<SelectionState> = _selection.asStateFlow()

    /** Enter selection mode with the long-pressed photo already selected. */
    fun startSelection(id: Long) {
        _selection.value = SelectionState(active = true, selectedIds = setOf(id))
    }

    /** Set a photo's selected state. Leaving zero selected exits selection mode. */
    fun setSelected(id: Long, selected: Boolean) {
        val current = _selection.value
        if (!current.active) return
        val ids = if (selected) current.selectedIds + id else current.selectedIds - id
        _selection.value = if (ids.isEmpty()) SelectionState() else current.copy(selectedIds = ids)
    }

    fun toggle(id: Long) = setSelected(id, id !in _selection.value.selectedIds)

    fun clearSelection() {
        _selection.value = SelectionState()
    }

    /** Drop deleted photos from the timeline and exit selection mode. */
    fun onDeleted(ids: Set<Long>) {
        val current = _uiState.value
        _uiState.value = current.copy(photos = current.photos.filterNot { it.id in ids })
        clearSelection()
        viewModelScope.launch { tagRepository.forget(ids) }
    }

    fun loadPhotos() {
        _uiState.value = _uiState.value.copy(loading = true, errorMessage = null)
        viewModelScope.launch {
            runCatching { repository.loadPhotos() }
                .onSuccess { photos ->
                    _uiState.value = TimelineUiState(loading = false, photos = photos)
                    startBackgroundTagging(photos)
                }
                .onFailure { t ->
                    _uiState.value = TimelineUiState(
                        loading = false,
                        photos = emptyList(),
                        errorMessage = t.message ?: "読み込みに失敗しました",
                    )
                }
        }
    }

    private fun startBackgroundTagging(photos: List<Photo>) {
        viewModelScope.launch {
            tagRepository.tagMissing(photos) { done, total ->
                _tagging.value = TaggingProgress(done = done, total = total)
            }
        }
    }
}
