package com.dinz.photoviewer.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoTagDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTags(tags: List<PhotoTagEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markTagged(photo: TaggedPhotoEntity)

    @Query("SELECT photoId FROM tagged_photos")
    suspend fun taggedPhotoIds(): List<Long>

    /** All distinct tags currently in the cache, most frequent first. */
    @Query("SELECT tag FROM photo_tags GROUP BY tag ORDER BY COUNT(*) DESC, tag ASC")
    fun observeAllTags(): Flow<List<String>>

    /**
     * One cover per tag for the album view: the photo with the highest confidence (SQLite returns
     * the bare `photoId` from the MAX row), plus the photo count, most frequent tags first.
     */
    @Query(
        "SELECT tag AS tag, photoId AS photoId, COUNT(*) AS count, MAX(confidence) AS maxConf " +
            "FROM photo_tags GROUP BY tag ORDER BY count DESC, tag ASC",
    )
    fun observeTagCovers(): Flow<List<TagCover>>

    /** Photo ids that carry ALL of the given tags (AND filtering). */
    @Query(
        "SELECT photoId FROM photo_tags WHERE tag IN (:tags) " +
            "GROUP BY photoId HAVING COUNT(DISTINCT tag) = :tagCount",
    )
    fun observePhotosWithAllTags(tags: List<String>, tagCount: Int): Flow<List<Long>>

    /** Photo ids that carry ANY of the given tags (OR filtering). */
    @Query("SELECT DISTINCT photoId FROM photo_tags WHERE tag IN (:tags)")
    fun observePhotosWithAnyTags(tags: List<String>): Flow<List<Long>>

    @Query("SELECT * FROM photo_tags WHERE photoId = :photoId ORDER BY confidence DESC")
    suspend fun tagsForPhoto(photoId: Long): List<PhotoTagEntity>

    @Query("SELECT * FROM photo_tags WHERE photoId = :photoId ORDER BY confidence DESC")
    fun observeTagsForPhoto(photoId: Long): Flow<List<PhotoTagEntity>>

    @Query("DELETE FROM photo_tags WHERE photoId = :photoId AND tag = :tag")
    suspend fun deleteTag(photoId: Long, tag: String)

    @Query("DELETE FROM photo_tags WHERE photoId IN (:photoIds)")
    suspend fun deleteTagsFor(photoIds: List<Long>)

    @Query("DELETE FROM tagged_photos WHERE photoId IN (:photoIds)")
    suspend fun deleteTaggedMarks(photoIds: List<Long>)
}
