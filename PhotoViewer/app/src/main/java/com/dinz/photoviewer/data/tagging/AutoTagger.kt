package com.dinz.photoviewer.data.tagging

import com.dinz.photoviewer.data.Photo
import kotlin.math.abs

/**
 * Auto-tagging contract.
 *
 * The production implementation is intended to run an anime-specialised model such as a
 * danbooru / wd-tagger ONNX network on-device (via ONNX Runtime or TFLite) and return the
 * predicted tags above a confidence threshold. That ML backend is out of scope for this
 * milestone, so [StubAutoTagger] supplies deterministic placeholder tags that exercise the
 * full tag UI and filtering pipeline. Swap the binding in the ViewModel to go live.
 */
interface AutoTagger {
    /** Human-readable name of the active engine, surfaced in the UI. */
    val engineName: String

    /** Returns the list of tags for [photo]. Should be safe to call off the main thread. */
    suspend fun tagsFor(photo: Photo): List<Tag>
}

/** A single predicted tag with a confidence in [0f, 1f]. */
data class Tag(val name: String, val confidence: Float)

/**
 * Deterministic placeholder tagger. Produces a stable, plausible-looking set of anime-style
 * tags derived from the photo id so the UI and filtering behave realistically without a model.
 */
class StubAutoTagger : AutoTagger {

    override val engineName: String = "スタブ（モデル未配置）"

    private val vocabulary = listOf(
        "1girl", "1boy", "solo", "long_hair", "short_hair", "smile", "blue_eyes",
        "blonde_hair", "school_uniform", "outdoors", "sky", "cloud", "scenery",
        "flower", "cat", "night", "city", "monochrome", "twintails", "glasses",
        "hat", "ribbon", "sword", "armor", "fantasy", "ocean", "sunset", "rain",
    )

    override suspend fun tagsFor(photo: Photo): List<Tag> {
        val seed = abs(photo.id * 2654435761L)
        val count = 3 + (seed % 4).toInt() // 3..6 tags
        val tags = LinkedHashSet<Tag>()
        var s = seed
        while (tags.size < count) {
            s = s * 6364136223846793005L + 1442695040888963407L
            val idx = (abs(s) % vocabulary.size).toInt()
            val conf = 0.55f + (abs(s shr 8) % 45).toInt() / 100f // 0.55..0.99
            tags += Tag(vocabulary[idx], conf)
        }
        return tags.sortedByDescending { it.confidence }
    }
}
