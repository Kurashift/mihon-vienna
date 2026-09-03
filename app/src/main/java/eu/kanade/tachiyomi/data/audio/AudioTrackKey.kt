package eu.kanade.tachiyomi.data.audio

/**
 * Stable identity for one audio track.
 *
 * A track is addressed by the work it belongs to, the folder it lives in and its file name without
 * the audio extension — never by its stream URL. The URL changes with the audio quality setting,
 * because a lossless and a lossy stream are two different addresses for what the user hears as one
 * and the same track. Anything keyed on it, play history included, would therefore lose its place
 * the moment the quality is switched.
 *
 * The normalisation is the one the track catalog already uses to group a work's files into tracks,
 * so both places agree on what counts as the same track: the catalog picks one encoding out of a
 * group per quality, and the key below names that group rather than the encoding it picked.
 */
internal val AudioPlayItem.trackKey: String
    get() = (listOf(workId.toString()) + folderPath.trackFolders() + trackTitle.trackBaseName())
        .joinToString("\u0000") { it.normalizedKey() }

/** Folder segments of [this] path, minus the ones that only name an encoding or a subtitle set. */
private fun String.trackFolders(): List<String> = split("/")
    .filter { it.isNotBlank() && !it.isVariantContainer() }

/** File name without its audio extension and without a trailing subtitle marker. */
internal fun String.trackBaseName(): String = removeAudioExtension().removeSubtitleSuffix()

internal fun String.normalizedKey(): String = trim().lowercase()

internal fun String.isVariantContainer(): Boolean {
    val normalized = normalizedKey()
    return FORMAT_FOLDER_REGEX.matches(normalized) || SUBTITLE_FOLDER_MARKERS.any { it in normalized }
}

internal fun String.removeSubtitleSuffix(): String {
    var result = this
    while (true) {
        val stripped = result.replace(SUBTITLE_SUFFIX_REGEX, "")
            .trimEnd(' ', '_', '-', '.', '（', '(', '[', '【')
        if (stripped == result) return stripped
        result = stripped
    }
}

internal fun String.fileExtension(): String = substringAfterLast('.', "").lowercase()

internal fun String.removeFileExtension(): String = substringBeforeLast('.', this)

internal fun String.removeAudioExtension(): String {
    val extension = fileExtension()
    return if (extension in AUDIO_PRIORITY) removeFileExtension() else this
}

private val FORMAT_FOLDER_REGEX = Regex(
    pattern = """^\s*\d*\s*[:：_\-－ ]*\s*(mp3|wav|flac|m4a|aac|ogg|opus|mp4|m4b)(?:\s*.*)?$""",
    option = RegexOption.IGNORE_CASE,
)
private val SUBTITLE_FOLDER_MARKERS = listOf(
    "subtitle",
    "subtitled",
    "lyrics",
    "lyric",
    "lrc",
    "字幕",
    "台词",
    "歌词",
    "翻译",
)
private val SUBTITLE_SUFFIX_REGEX = Regex(
    pattern = """(?:[\s._\-]*(?:\(|（|\[|【)?(?:subtitle|subtitles|sub|lyrics?|字幕|台词|歌词|中文|""" +
        """简中|简体|繁中|繁体|zh(?:-cn|-tw)?|ja|jp)(?:\)|）|\]|】)?)$""",
    option = RegexOption.IGNORE_CASE,
)
private val AUDIO_PRIORITY = setOf(
    "mp3",
    "m4a",
    "aac",
    "ogg",
    "opus",
    "m4b",
    "flac",
    "wav",
    "mp4",
)
