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
                            folderPath = ancestors.joinToString("/"),
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
        .map { candidates ->
            val displayCandidate = candidates.minWith(
                compareBy<AudioCandidate> { priorityFor(it, quality) }
                    .thenBy { it.node.size ?: Long.MAX_VALUE }
                    .thenBy { it.ordinal },
            )
            SelectedAudio(
                displayCandidate = displayCandidate,
                streamUrl = displayCandidate.preferredStreamUrl(quality),
            )
        }
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

    val coverUrl = work.mainCoverUrl ?: work.thumbnailCoverUrl ?: work.samCoverUrl
    val tracks = selectedAudio.map { selected ->
        val candidate = selected.displayCandidate
        val node = candidate.node
        val subtitle = subtitlesByKey[candidate.logicalKey]
            ?: uniqueSubtitlesByBase[candidate.baseName]
            ?: candidate.trackNumber?.let(uniqueSubtitlesByTrackNumber::get)
            ?: onlySubtitle?.takeIf { selectedAudio.size == 1 }
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

    fun prune(nodes: List<TrackNode>): List<TrackNode> = nodes.flatMap { node ->
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

    return AudioTrackCatalog(rootNodes = prune(this), tracks = tracks)
}

private data class SelectedAudio(
    val displayCandidate: AudioCandidate,
    val streamUrl: String,
)

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
