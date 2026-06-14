package com.dinz.photoviewer

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

/**
 * Custom Coil [ImageLoader] with a generous in-memory + disk cache and a 150 ms crossfade,
 * so fast scrolling shows cached thumbnails immediately and high-res swaps in smoothly
 * (the "zero-lag" feel of spec 5).
 */
class PhotoApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .crossfade(150)
            .build()
}
