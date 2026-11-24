package de.stubebox.tv.core.categorylock

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import de.stubebox.tv.core.customize.ItemCategory
import de.stubebox.tv.core.customize.isSealed
import de.stubebox.tv.core.database.entity.RecordingEntity
import de.stubebox.tv.core.recording.SeriesRecording

/**
 * These recordings without those of a channel filed under a [sealed] category. [categoryOf] gives a
 * channel's category; a recording whose channel is gone (a re-sync gave it a new id) can no longer be
 * tied to a category, so it stays out while [anySealed] says the profile has a lock that is not open.
 */
fun Flow<List<RecordingEntity>>.withoutSealed(
    sealed: Flow<Set<Long>>,
    anySealed: Flow<Boolean>,
    categoryOf: suspend (channelId: Long) -> ItemCategory,
): Flow<List<RecordingEntity>> = withoutSealedBy(sealed, anySealed, categoryOf) { it.channelId }

/** The same for "record every showing" instructions: a locked channel's show stays out of the list too. */
@JvmName("seriesWithoutSealed")
fun Flow<List<SeriesRecording>>.withoutSealed(
    sealed: Flow<Set<Long>>,
    anySealed: Flow<Boolean>,
    categoryOf: suspend (channelId: Long) -> ItemCategory,
): Flow<List<SeriesRecording>> = withoutSealedBy(sealed, anySealed, categoryOf) { it.rule.channelId }

private fun <T> Flow<List<T>>.withoutSealedBy(
    sealed: Flow<Set<Long>>,
    anySealed: Flow<Boolean>,
    categoryOf: suspend (channelId: Long) -> ItemCategory,
    channelOf: (T) -> Long,
): Flow<List<T>> = combine(this, sealed, anySealed) { rows, ids, any ->
    if (ids.isEmpty() && !any) rows
    else rows.filterNot { categoryOf(channelOf(it)).isSealed({ any }) { id -> id in ids } }
}
