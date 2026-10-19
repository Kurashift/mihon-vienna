package eu.kanade.tachiyomi.data.audio

internal data class AudioTrackCatalog(
    val rootNodes: List<TrackNode>,
    val tracks: List<AudioPlayItem>,
)

internal fun List<TrackNode>.buildAudioTrackCatalog(
    work: Work,
    quality: AudioQualityMode = AudioQualityMode.FLUENT_FIRST,
): AudioTrackCatalog {
    val audioCandidates = mutableListOf<AudioCandidate>()
    val subtitleCandidates = mutableListOf<SubtitleCandidate>()
    var ordinal = 0

    fun walk(nodes: List<TrackNode>, ancestors: List<String>) {
        nodes.forEach { node ->
            val extension = node.title.fileExtension()
            when {
                node.type == "folder" -> walk(node.children, ancestors + node.title)
                extension in SUBTITLE_EXTENSIONS -> {
                    val url = node.mediaStreamUrl ?: node.mediaDownloadUrl
                    if (!url.isNullOrBlank()) {
                        val baseName = node.title
                            .removeFileExtension()
                            .removeAudioExtension()
                            .removeSubtitleSuffix()
                        subtitleCandidates += SubtitleCandidate(
                            url = url,
                            fallbackUrl = node.mediaDownloadUrl?.takeUnless { it == url },
                            logicalKey = logicalKey(ancestors, baseName),
                            baseName = baseName.normalizedKey(),
                            trackNumber = baseName.trackNumber(),
                            priority = SUBTITLE_PRIORITY.getValue(extension),
                        )
                    }
                }
                node.type == "audio" -> {
                    val streamUrl = node.mediaStreamUrl
                    if (!streamUrl.isNullOrBlank()) {
                        val baseName = node.title.trackBaseName()
                        audioCandidates += AudioCandidate(
                            ordinal = ordinal++,
                            node = node,
                            logicalKey = logicalKey(ancestors, baseName),
                            baseName = baseName.normalizedKey(),
                            trackNumber = baseName.trackNumber(),
                            extension = extension,
                            // The page drops the format/subtitle containers when it builds the tree,
                            // so the folder a track reports has to drop them too: a track living in
                            // `04_mp3形式…/02_nonSE（液体音無し）` is shown under `02_nonSE（液体音無し）`,
                            // and a folder row looks its tracks up by exactly this string.
                            folderPath = ancestors.filterNot { it.isVariantContainer() }.joinToString("/"),
                        )
                    }
                }
            }
        }
    }
    walk(this, emptyList())

    val selectedAudio = audioCandidates
        .groupBy { it.logicalKey }
        .values
        .flatMap { candidates -> selectRows(candidates, quality) }
        .sortedBy { it.displayCandidate.ordinal }
    val selectedByOriginalUrl = selectedAudio.associateBy {
        it.displayCandidate.node.mediaStreamUrl.orEmpty()
    }

    val subtitlesByKey = subtitleCandidates
        .groupBy { it.logicalKey }
        .mapValues { (_, candidates) -> candidates.minBy { it.priority } }
    val uniqueSubtitlesByBase = subtitleCandidates
        .groupBy { it.baseName }
        .mapNotNull { (baseName, candidates) ->
            val urls = candidates.map { it.url }.distinct()
            if (urls.size == 1) baseName to candidates.minBy { it.priority } else null
        }
        .toMap()
    val uniqueSubtitlesByTrackNumber = subtitleCandidates
        .filter { it.trackNumber != null }
        .groupBy { it.trackNumber }
        .mapNotNull { (trackNumber, candidates) ->
            val urls = candidates.map { it.url }.distinct()
            if (urls.size == 1) trackNumber to candidates.minBy { it.priority } else null
        }
        .toMap()
    val onlySubtitle = subtitleCandidates
        .takeIf { it.map { candidate -> candidate.url }.distinct().size == 1 }
        ?.minByOrNull { it.priority }
    // Counted in tracks, not in rows: a work that ships one track as both sound and picture has
    // two rows for it, and a single subtitle still belongs to that one track.
    val trackCount = selectedAudio.map { it.displayCandidate.logicalKey }.distinct().size
    val coverUrl = work.mainCoverUrl ?: work.thumbnailCoverUrl ?: work.samCoverUrl
    val tracks = selectedAudio.map { selected ->
        val candidate = selected.displayCandidate
        val node = candidate.node
        val subtitle = subtitlesByKey[candidate.logicalKey]
            ?: uniqueSubtitlesByBase[candidate.baseName]
            ?: candidate.trackNumber?.let(uniqueSubtitlesByTrackNumber::get)
            ?: onlySubtitle?.takeIf { trackCount == 1 }
        AudioPlayItem(
            workId = work.id,
            workTitle = work.title,
            circleName = work.name,
            coverUrl = coverUrl,
            trackTitle = node.title,
            mediaStreamUrl = selected.streamUrl,
            subtitleUrl = subtitle?.url,
            subtitleFallbackUrl = subtitle?.fallbackUrl,
            durationMs = ((node.duration ?: 0.0) * 1000).toLong(),
            folderPath = selected.displayCandidate.folderPath,
        )
    }

    fun prune(nodes: List<TrackNode>): List<TrackNode> = nodes
        .flatMap { node ->
            when (node.type) {
                "folder" -> {
                    val children = prune(node.children)
                    when {
                        children.isEmpty() -> emptyList()
                        node.title.isVariantContainer() -> children
                        else -> listOf(node.copy(children = children))
                    }
                }
                "audio" -> {
                    val selected = selectedByOriginalUrl[node.mediaStreamUrl.orEmpty()]
                    if (selected == null) {
                        emptyList()
                    } else {
                        listOf(
                            node.copy(
                                mediaStreamUrl = selected.streamUrl,
                                streamLowQualityUrl = null,
                            ),
                        )
                    }
                }
                else -> emptyList()
            }
        }
        .mergeMirroredFolders()

    return AudioTrackCatalog(rootNodes = prune(this), tracks = tracks)
}

/**
 * Folds folders that ended up with one title under one parent back into a single node.
 *
 * A work can ship the same folders twice: a lossless set at the root and an `04_mp3形式…` copy
 * beside it. [buildAudioTrackCatalog] hoists the copy's children so the container itself never
 * shows, which leaves two folders with the same name side by side - something a real directory
 * cannot hold. They are one folder in two encodings, so their children belong in one node.
 *
 * One node per folder is also what the detail page needs: it keys its rows by folder path, and a
 * repeated path made Compose throw "Key ... was already used" as soon as the page opened.
 *
 * A mirrored copy is often shorter than the folder it duplicates (the lossless set carries tracks
 * the mp3 copy skipped), so its children are appended rather than replacing anything, and a track
 * both copies list under the same URL is kept once.
 */
private fun List<TrackNode>.mergeMirroredFolders(): List<TrackNode> {
    if (size < 2) return this
    val merged = ArrayList<TrackNode>(size)
    val folderIndexByTitle = HashMap<String, Int>(size)
    val audioUrls = HashSet<String>(size)
    for (node in this) {
        when (node.type) {
            "folder" -> {
                val index = folderIndexByTitle[node.title]
                if (index == null) {
                    folderIndexByTitle[node.title] = merged.size
                    merged += node
                } else {
                    val existing = merged[index]
                    merged[index] = existing.copy(
                        children = (existing.children + node.children).mergeMirroredFolders(),
                    )
                }
            }
            "audio" -> if (audioUrls.add(node.mediaStreamUrl.orEmpty())) merged += node
            else -> merged += node
        }
    }
    return merged
}

private data class SelectedAudio(
    val displayCandidate: AudioCandidate,
    val streamUrl: String,
)

/**
 * Turns one track's encodings into the rows the page shows.
 *
 * Normally that is a single row: the best encoding for the current quality setting. A track that
 * ships both as sound and as picture is the exception. Every priority table ranks the video
 * containers last, so the group would otherwise keep only the sound file and the picture would be
 * unreachable — there is no separate video entry point to fall back on. The picture therefore
 * becomes a second row of the same track, carrying the same subtitle and its own audio.
 */
private fun selectRows(
    candidates: List<AudioCandidate>,
    quality: AudioQualityMode,
): List<SelectedAudio> {
    val ranking = compareBy<AudioCandidate> { priorityFor(it, quality) }
        .thenBy { it.node.size ?: Long.MAX_VALUE }
        .thenBy { it.ordinal }
    val primary = candidates.minWith(ranking)
    val rows = mutableListOf(SelectedAudio(primary, primary.preferredStreamUrl(quality)))
    if (primary.extension !in VIDEO_EXTENSIONS) {
        candidates
            .filter { it.extension in VIDEO_EXTENSIONS }
            .minWithOrNull(ranking)
            ?.let { rows += SelectedAudio(it, it.preferredStreamUrl(quality)) }
    }
    return rows
}

private data class AudioCandidate(
    val ordinal: Int,
    val node: TrackNode,
    val logicalKey: String,
    val baseName: String,
    val trackNumber: String?,
    val extension: String,
    val folderPath: String,
) {
    fun preferredStreamUrl(quality: AudioQualityMode): String {
        return if (quality == AudioQualityMode.FLUENT_FIRST && extension in LOSSLESS_EXTENSIONS) {
            node.streamLowQualityUrl?.takeIf { it.isNotBlank() } ?: node.mediaStreamUrl.orEmpty()
        } else {
            node.mediaStreamUrl.orEmpty()
        }
    }
}

private data class SubtitleCandidate(
    val url: String,
    val fallbackUrl: String?,
    val logicalKey: String,
    val baseName: String,
    val trackNumber: String?,
    val priority: Int,
)

private fun logicalKey(ancestors: List<String>, baseName: String): String {
    return (ancestors.filterNot { it.isVariantContainer() } + baseName)
        .joinToString("\u0000") { it.normalizedKey() }
}

private fun String.trackNumber(): String? {
    val digits = TRACK_NUMBER_REGEX.find(this)?.groupValues?.getOrNull(1) ?: return null
    return digits.trimStart('0').ifEmpty { "0" }
}

private val TRACK_NUMBER_REGEX = Regex("""^(?:track\s*)?0*(\d+)""", RegexOption.IGNORE_CASE)
private val FLUENT_AUDIO_PRIORITY = mapOf(
    "mp3" to 0,
    "m4a" to 1,
    "aac" to 2,
    "ogg" to 3,
    "opus" to 4,
    "m4b" to 5,
    "flac" to 6,
    "wav" to 7,
    "mp4" to 8,
    "m4v" to 8,
    "mov" to 8,
    "mkv" to 8,
    "webm" to 8,
)
private val QUALITY_FIRST_AUDIO_PRIORITY = mapOf(
    "flac" to 0,
    "wav" to 1,
    "ogg" to 2,
    "opus" to 3,
    "m4a" to 4,
    "aac" to 5,
    "mp3" to 6,
    "m4b" to 7,
    "mp4" to 8,
    "m4v" to 8,
    "mov" to 8,
    "mkv" to 8,
    "webm" to 8,
)
private val LOSSLESS_EXTENSIONS = setOf("flac", "wav")
private val SUBTITLE_EXTENSIONS = setOf("lrc", "vtt", "srt", "ass")
private val SUBTITLE_PRIORITY = mapOf("lrc" to 0, "vtt" to 1, "srt" to 2, "ass" to 3)

private fun priorityFor(candidate: AudioCandidate, quality: AudioQualityMode): Int {
    val priorities = if (quality == AudioQualityMode.QUALITY_FIRST) {
        QUALITY_FIRST_AUDIO_PRIORITY
    } else {
        FLUENT_AUDIO_PRIORITY
    }
    return priorities[candidate.extension] ?: Int.MAX_VALUE
}
