package com.dinz.photoviewer.ui.timeline

import com.dinz.photoviewer.data.Photo
import java.util.Calendar
import java.util.Locale

/** Density layers for the timeline grid, switched via pinch. */
enum class TimelineScale(val columns: Int) {
    COMFORTABLE(3),   // 快適: 1行3枚 — details visible
    COMPACT(6),       // コンパクト: 1行6枚 — high overview
    MONTHLY(15);      // 月別見出し: 極小サムネイル

    fun zoomIn(): TimelineScale = when (this) {
        MONTHLY -> COMPACT
        COMPACT -> COMFORTABLE
        COMFORTABLE -> COMFORTABLE
    }

    fun zoomOut(): TimelineScale = when (this) {
        COMFORTABLE -> COMPACT
        COMPACT -> MONTHLY
        MONTHLY -> MONTHLY
    }
}

/** Flat items rendered by the LazyVerticalGrid. Headers span the full row width. */
sealed interface TimelineItem {
    val key: String

    data class Header(val label: String, override val key: String) : TimelineItem
    data class PhotoCell(val photo: Photo) : TimelineItem {
        override val key: String get() = "p_${photo.id}"
    }
}

/** A label shown by the fast-seek scrollbar, anchored to a position in the flat item list. */
data class SeekLabel(val text: String, val itemIndex: Int)

private val dayHeaderFormat = java.text.SimpleDateFormat("M月d日(E)", Locale.JAPAN)
private val monthHeaderFormat = java.text.SimpleDateFormat("yyyy年M月", Locale.JAPAN)

/**
 * Groups photos (already sorted newest-first) into a flat list of headers + cells.
 * Day-level grouping for COMFORTABLE/COMPACT, month-level for MONTHLY.
 */
fun buildTimeline(photos: List<Photo>, scale: TimelineScale): List<TimelineItem> {
    if (photos.isEmpty()) return emptyList()
    val byMonth = scale == TimelineScale.MONTHLY
    val items = ArrayList<TimelineItem>(photos.size + 32)
    val cal = Calendar.getInstance()
    var currentKey: String? = null

    for (photo in photos) {
        cal.timeInMillis = photo.dateTaken
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH)
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val groupKey = if (byMonth) "$year-$month" else "$year-$month-$day"
        if (groupKey != currentKey) {
            currentKey = groupKey
            val label = if (byMonth) monthHeaderFormat.format(cal.time)
            else dayHeaderFormat.format(cal.time)
            items += TimelineItem.Header(label = label, key = "h_$groupKey")
        }
        items += TimelineItem.PhotoCell(photo)
    }
    return items
}

/** Builds month/year markers for the fast-seek scrollbar from the flat item list. */
fun buildSeekLabels(items: List<TimelineItem>): List<SeekLabel> {
    val labels = ArrayList<SeekLabel>()
    val cal = Calendar.getInstance()
    var lastMonthKey: String? = null
    items.forEachIndexed { index, item ->
        if (item is TimelineItem.PhotoCell) {
            cal.timeInMillis = item.photo.dateTaken
            val key = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}"
            if (key != lastMonthKey) {
                lastMonthKey = key
                labels += SeekLabel(monthHeaderFormat.format(cal.time), index)
            }
        }
    }
    return labels
}
