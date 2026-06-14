package com.dinz.photoviewer.data.tagging

import android.content.Context
import com.dinz.photoviewer.data.Photo
import com.dinz.photoviewer.data.db.AppDatabase
import com.dinz.photoviewer.data.db.PhotoTagEntity
import com.dinz.photoviewer.data.db.TagCover
import com.dinz.photoviewer.data.db.TaggedPhotoEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Owns the tag cache (Room) and the tagging backend. Tags are computed lazily/in the background
 * and persisted, so the UI reads from the database and inference runs at most once per photo.
 */
class TagRepository(context: Context) {

    private val dao = AppDatabase.get(context).photoTagDao()
    private val tagger: AutoTagger = TaggerFactory.create(context)

    /** Name of the active engine (e.g. "WD Tagger v3 (ONNX)" or the stub fallback). */
    val engineName: String get() = tagger.engineName

    fun observeAllTags(): Flow<List<String>> = dao.observeAllTags()

    /** Per-tag album covers (representative photo id + count). */
    fun observeTagCovers(): Flow<List<TagCover>> = dao.observeTagCovers()

    /** Photo ids matching ALL [tags] (AND). Empty => emit empty (caller treats as "no filter"). */
    fun observePhotosWithAllTags(tags: List<String>): Flow<List<Long>> =
        if (tags.isEmpty()) flowOf(emptyList()) else dao.observePhotosWithAllTags(tags, tags.size)

    /** Photo ids matching ANY [tags] (OR). */
    fun observePhotosWithAnyTags(tags: List<String>): Flow<List<Long>> =
        if (tags.isEmpty()) flowOf(emptyList()) else dao.observePhotosWithAnyTags(tags)

    /** Live tags for a single photo (reflects manual edits immediately). */
    fun observeTagsForPhoto(photoId: Long): Flow<List<Tag>> =
        dao.observeTagsForPhoto(photoId).map { rows -> rows.map { Tag(it.tag, it.confidence) } }

    /** Returns cached tags for a photo, computing+storing them on first access. */
    suspend fun tagsForPhoto(photo: Photo): List<Tag> {
        val cached = dao.tagsForPhoto(photo.id)
        if (cached.isNotEmpty()) return cached.map { Tag(it.tag, it.confidence) }
        return tagAndStore(photo)
    }

    private suspend fun tagAndStore(photo: Photo): List<Tag> {
        val tags = runCatching { tagger.tagsFor(photo) }.getOrDefault(emptyList())
        dao.insertTags(tags.map { PhotoTagEntity(photo.id, it.name, it.confidence, category = 0) })
        dao.markTagged(TaggedPhotoEntity(photo.id, System.currentTimeMillis()))
        return tags
    }

    /** Tags any not-yet-processed photos one by one, reporting progress. */
    suspend fun tagMissing(photos: List<Photo>, onProgress: (done: Int, total: Int) -> Unit) {
        val already = dao.taggedPhotoIds().toHashSet()
        val pending = photos.filterNot { it.id in already }
        val total = pending.size
        onProgress(0, total)
        if (total == 0) return
        var done = 0
        for (photo in pending) {
            runCatching { tagAndStore(photo) }
            done++
            onProgress(done, total)
        }
    }

    suspend fun forget(ids: Set<Long>) {
        val list = ids.toList()
        dao.deleteTagsFor(list)
        dao.deleteTaggedMarks(list)
    }

    /** Manually add a tag to a photo (confidence 1.0). Marks the photo as user-edited. */
    suspend fun addTag(photoId: Long, tag: String) {
        val clean = tag.trim()
        if (clean.isEmpty()) return
        dao.insertTags(listOf(PhotoTagEntity(photoId, clean, confidence = 1f, category = 0)))
        dao.markTagged(TaggedPhotoEntity(photoId, System.currentTimeMillis(), userEdited = true))
    }

    /** Manually remove a tag from a photo. Marks the photo as user-edited. */
    suspend fun removeTag(photoId: Long, tag: String) {
        dao.deleteTag(photoId, tag)
        dao.markTagged(TaggedPhotoEntity(photoId, System.currentTimeMillis(), userEdited = true))
    }
}
