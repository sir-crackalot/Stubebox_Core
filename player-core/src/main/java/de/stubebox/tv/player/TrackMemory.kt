package de.stubebox.tv.player

import de.stubebox.tv.core.player.PlaybackPrefsStore

/**
 * Remembered audio / subtitle choice for one item, turned into what an engine
 * should do once it knows the stream's tracks.
 *
 * A remembered choice is stored as a *language*, not a track id — ids change between streams of the
 * same channel, and between episodes of the same series, while "the German track" does not. When the
 * stream has no track in that language nothing changes, so the global preferred language still applies.
 */
internal object TrackMemory {

    /** The audio track to switch to, or null when none matches or a matching one is already playing. */
    fun audioToSelect(tracks: List<TrackOption>, lang: String?): TrackOption? {
        if (lang.isNullOrBlank()) return null
        if (tracks.any { it.selected && subtitleLanguageMatches(lang, it.lang) }) return null
        return tracks.firstOrNull { subtitleLanguageMatches(lang, it.lang) }
    }

    sealed interface SubtitleAction {
        data object None : SubtitleAction
        data object Off : SubtitleAction
        data class Select(val track: TrackOption) : SubtitleAction
    }

    /**
     * What to do with subtitles. [allowImage] is false where a bitmap subtitle would cost an engine
     * handoff (mpv VOD) or cannot be drawn at all (mpv live) — a remembered choice never triggers that.
     */
    fun subtitleAction(tracks: List<TrackOption>, choice: String?, allowImage: Boolean): SubtitleAction {
        if (choice.isNullOrBlank()) return SubtitleAction.None
        if (choice == PlaybackPrefsStore.SUBTITLES_OFF) {
            return if (tracks.any { it.selected }) SubtitleAction.Off else SubtitleAction.None
        }
        if (tracks.any { it.selected && subtitleLanguageMatches(choice, it.lang) }) return SubtitleAction.None
        val track = tracks.firstOrNull { subtitleLanguageMatches(choice, it.lang) && (allowImage || !it.image) }
        return track?.let { SubtitleAction.Select(it) } ?: SubtitleAction.None
    }
}

/**
 * One load's remembered track languages, the same on both engines. Each half is applied once, when both
 * the answer from the database and that kind's track list are in — whichever arrives last does it. A pick
 * by the user closes that half too, so a database answer arriving after it cannot undo it.
 */
internal class TrackRecall(val key: String) {
    private var loaded = false
    private var audio: String? = null
    private var sub: String? = null
    var audioDone = false
    private var subDone = false

    /** The database answered (or the "original language" stood in for it). */
    fun load(audio: String?, sub: String?) {
        this.audio = audio
        this.sub = sub
        loaded = true
    }

    /** Apply whichever half is ready. [allowImage]: see [TrackMemory.subtitleAction]. */
    fun apply(
        audioTracks: List<TrackOption>,
        subTracks: List<TrackOption>,
        allowImage: Boolean,
        selectAudio: (Int) -> Unit,
        subtitlesOff: () -> Unit,
        selectSubtitle: (Int) -> Unit,
    ) {
        if (!loaded) return
        if (!audioDone && audioTracks.isNotEmpty()) {
            audioDone = true
            TrackMemory.audioToSelect(audioTracks, audio)?.let { selectAudio(it.mpvId) }
        }
        if (!subDone && subTracks.isNotEmpty()) {
            subDone = true
            when (val action = TrackMemory.subtitleAction(subTracks, sub, allowImage)) {
                TrackMemory.SubtitleAction.None -> Unit
                TrackMemory.SubtitleAction.Off -> subtitlesOff()
                is TrackMemory.SubtitleAction.Select -> selectSubtitle(action.track.mpvId)
            }
        }
    }

    /**
     * The user picked a track: that half is closed. Returns the language to store, or null when the track
     * has none. A picked audio language is also what an engine switch later in this load re-applies.
     */
    fun picked(isAudio: Boolean, lang: String?): String? {
        if (isAudio) audioDone = true else subDone = true
        if (lang.isNullOrBlank()) return null
        if (isAudio) audio = lang
        return lang
    }
}
