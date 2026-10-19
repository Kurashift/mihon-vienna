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
    return if (extension in MEDIA_EXTENSIONS) removeFileExtension() else this
}

/**
 * Containers that carry a picture as well as sound.
 *
 * The backend reports every file as an `audio` node, so the only thing that tells a video work
 * apart from a sound work is the container it ships in. The list is kept here next to the audio
 * extensions because the same normalisation has to strip it from the title: a track named
 * `本編.mkv` is the same track as `本編.mp3` for everything keyed on [trackBaseName].
 */
internal val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mkv", "webm", "mov")

/**
 * Whether playing [this] can put a picture on screen.
 *
 * Read off the stream address rather than stored on the item: the address is what the player is
 * handed, and keeping a copy on the persisted model would have to be migrated for every work
 * already in the history and the queue.
 */
internal val AudioPlayItem.isVideoSource: Boolean
    get() = mediaStreamUrl.isVideoStream()

/** Same question for a bare stream address, for rows that never build an [AudioPlayItem]. */
internal fun String.isVideoStream(): Boolean = fileExtension() in VIDEO_EXTENSIONS

private val FORMAT_FOLDER_REGEX = Regex(
    pattern = """^\s*\d*\s*[:：_\-－ ]*\s*(mp3|wav|flac|m4a|aac|ogg|opus|mp4|m4v|mkv|webm|mov|m4b)(?:\s*.*)?$""",
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

/**
 * Every container a track can arrive in, sound or picture.
 *
 * Used only to decide whether a trailing extension is a format marker that belongs to the
 * encoding rather than to the name, so it has to hold the video containers too — otherwise
 * `本編.mkv` and `本編.mp3` would normalise to different keys and lose each other's history.
 */
private val MEDIA_EXTENSIONS = setOf(
    "mp3",
    "m4a",
    "aac",
    "ogg",
    "opus",
    "m4b",
    "flac",
    "wav",
    "mp4",
    "m4v",
    "mkv",
    "webm",
    "mov",
)
