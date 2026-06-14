package com.dinz.photoviewer.data

import android.net.Uri

/**
 * A single local image, sourced from MediaStore.
 *
 * @param dateTaken epoch milliseconds of when the photo was taken (Exif DATE_TAKEN,
 *        falling back to DATE_ADDED). Used for timeline grouping and fast-seek.
 */
data class Photo(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val dateTaken: Long,
    val width: Int,
    val height: Int,
) {
    val aspectRatio: Float
        get() = if (height > 0) width.toFloat() / height.toFloat() else 1f
}
