package de.stubebox.tv.core.live

import androidx.compose.runtime.Immutable
import de.stubebox.tv.core.parser.XtEpgEntry

/** Now-playing + up-next EPG for the focused channel (null entries when the guide is unavailable). */
@Immutable
data class EpgNowNext(
    val now: XtEpgEntry?,
    val next: XtEpgEntry?,
    val upcoming: List<XtEpgEntry> = emptyList(),
    val previous: XtEpgEntry? = null,
    /** Whole days of stored guide coverage for this channel (latest stop − earliest start).
     *  Null when unknown/short-EPG only. Drives the "EPG · Nd" hint in the preview metadata. */
    val coverageDays: Int? = null,
    /** The airing programme's XMLTV details (categories, year, rating, length) — stored guide only;
     *  null on the short-EPG path, which carries none. */
    val nowDetails: de.stubebox.tv.core.parser.EpgDetails? = null,
)
