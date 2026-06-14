package com.dinz.photoviewer.data.db

import androidx.room.Entity
import androidx.room.Index

/**
 * One predicted tag for a photo. Composite PK (photoId, tag); indexed on tag for fast filtering.
 */
@Entity(
    tableName = "photo_tags",
    primaryKeys = ["photoId", "tag"],
    indices = [Index("tag")],
)
data class PhotoTagEntity(
    val photoId: Long,
    val tag: String,
    val confidence: Float,
    /** wd-tagger category: 0 = general, 4 = character, 9 = rating. */
    val category: Int,
)

/**
 * Marks a photo as already processed by the tagger (even if it produced zero tags), so the
 * background pass does not re-run inference on it.
 */
@Entity(tableName = "tagged_photos")
data class TaggedPhotoEntity(
    @androidx.room.PrimaryKey val photoId: Long,
    val taggedAt: Long,
    /** True once the user has manually added/removed tags; auto-tagging won't overwrite it. */
    val userEdited: Boolean = false,
)

/**
 * Representative cover for a tag "album": the highest-confidence photo for that tag and how many
 * photos carry it. Populated by an aggregate query (not a table).
 */
data class TagCover(
    val tag: String,
    val photoId: Long,
    val count: Int,
    val maxConf: Float,
)
