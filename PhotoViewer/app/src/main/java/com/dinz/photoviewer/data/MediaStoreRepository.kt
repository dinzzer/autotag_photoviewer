package com.dinz.photoviewer.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads images from the device MediaStore. Read-only; grouping/sorting is done in the ViewModel.
 */
class MediaStoreRepository(private val context: Context) {

    suspend fun loadPhotos(): List<Photo> = withContext(Dispatchers.IO) {
        val photos = ArrayList<Photo>()

        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
        )
        // Newest first.
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media.DATE_ADDED} DESC"

        context.contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val takenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val takenMs = cursor.getLong(takenCol)
                // DATE_TAKEN is already in ms; DATE_ADDED is in seconds.
                val dateTaken = if (takenMs > 0L) takenMs else cursor.getLong(addedCol) * 1000L
                val uri = ContentUris.withAppendedId(collection, id)
                photos += Photo(
                    id = id,
                    uri = uri,
                    displayName = cursor.getString(nameCol) ?: "",
                    dateTaken = dateTaken,
                    width = cursor.getInt(widthCol),
                    height = cursor.getInt(heightCol),
                )
            }
        }
        photos
    }
}
