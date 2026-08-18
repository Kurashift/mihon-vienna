package eu.kanade.tachiyomi.data.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class AudioTrackCatalogTest {

    @Test
    fun `prefers selected compressed original over unrelated lossless fast stream`() {
        val nodes = listOf(
            folder(
                "日语",
                folder(
                    "Track 01",
                    folder("01：mp3", audio("Track 01.mp3", "mp3", 10)),
                    folder("02：wav", audio("Track 01.wav", "wav", 100, lowQualityUrl = "wav-low")),
                    folder("03：Subtitled audio video", audio("Track 01.mp4", "mp4", 200)),
                    folder("04：LyRiCs(LRC) subtitle data", text("Track 01.lrc", "lyrics")),
                ),
            ),
        )

        val catalog = nodes.buildAudioTrackCatalog(WORK)

        assertEquals(1, catalog.tracks.size)
        assertEquals("mp3", catalog.tracks.single().mediaStreamUrl)
        assertEquals("lyrics", catalog.tracks.single().subtitleUrl)
        assertEquals(listOf("日语", "Track 01"), catalog.rootNodes.folderTitles())
        assertFalse(catalog.rootNodes.folderTitles().any { "mp3" in it.lowercase() || "lyrics" in it.lowercase() })
    }

    @Test
    fun `matches nested audio extension subtitles from the real catalog shape`() {
        val nodes = listOf(
            folder("03：mp3", audio("Track01_Morning.mp3", "audio", 10), text("Track01_Morning.mp3.vtt", "vtt")),
            folder("04：wav", audio("Track01_Morning.wav", "wav", 100), text("Track01_Morning.wav.vtt", "wav-vtt")),
            folder("07：LyRiCs字幕数据", text("Track01_Morning.lrc", "lrc")),
        )

        val catalog = nodes.buildAudioTrackCatalog(WORK)

        assertEquals(1, catalog.tracks.size)
        assertEquals("audio", catalog.tracks.single().mediaStreamUrl)
        assertEquals("lrc", catalog.tracks.single().subtitleUrl)
    }

    @Test
    fun `matches subtitle attached to mp4 audio`() {
        val nodes = listOf(
            audio("Track01_Morning.mp4", "video", 200),
            text("Track01_Morning.mp4.vtt", "video-vtt"),
        )

        val catalog = nodes.buildAudioTrackCatalog(WORK)

        assertEquals("video-vtt", catalog.tracks.single().subtitleUrl)
    }

    @Test
    fun `recognizes subtitle files even when backend type is other`() {
        val nodes = listOf(
            audio("01.mp3", "audio", 10),
            TrackNode(type = "other", title = "01_字幕.srt", mediaDownloadUrl = "subtitle"),
        )

        val catalog = nodes.buildAudioTrackCatalog(WORK)

        assertEquals("subtitle", catalog.tracks.single().subtitleUrl)
    }

    @Test
    fun `prefers stable subtitle stream endpoint over download endpoint`() {
        val nodes = listOf(
            audio("01.mp3", "audio", 10),
            text("01.lrc", downloadUrl = "raw-download", streamUrl = "api-stream"),
        )

        val catalog = nodes.buildAudioTrackCatalog(WORK)

        assertEquals("api-stream", catalog.tracks.single().subtitleUrl)
        assertEquals("raw-download", catalog.tracks.single().subtitleFallbackUrl)
    }

    @Test
    fun `same file name in different discs remains distinct`() {
        val nodes = listOf(
            folder("Disc 1", audio("01.mp3", "disc-1", 10)),
            folder("Disc 2", audio("01.mp3", "disc-2", 10)),
        )

        val catalog = nodes.buildAudioTrackCatalog(WORK)

        assertEquals(listOf("disc-1", "disc-2"), catalog.tracks.map { it.mediaStreamUrl })
    }

    @Test
    fun `same file name in different languages keeps its matching subtitle`() {
        val nodes = listOf(
            folder(
                "日语",
                audio("01.mp3", "ja-audio", 10),
                folder("字幕", text("01.lrc", "ja-lyrics")),
            ),
            folder(
                "简体中文",
                audio("01.mp3", "zh-audio", 10),
                folder("字幕", text("01.lrc", "zh-lyrics")),
            ),
        )

        val catalog = nodes.buildAudioTrackCatalog(WORK)

        assertEquals(listOf("ja-lyrics", "zh-lyrics"), catalog.tracks.map { it.subtitleUrl })
    }

    @Test
    fun `uses low quality stream for a lone lossless track`() {
        val nodes = listOf(audio("Track.wav", "wav", 100, lowQualityUrl = "m4a"))

        val catalog = nodes.buildAudioTrackCatalog(WORK)

        assertEquals("m4a", catalog.tracks.single().mediaStreamUrl)
        assertEquals("m4a", catalog.rootNodes.single().mediaStreamUrl)
    }

    @Test
    fun `quality first prefers lossless original over compressed original`() {
        val nodes = listOf(
            folder(
                "日语",
                folder("01：mp3", audio("Track 01.mp3", "mp3", 10)),
                folder("02：wav", audio("Track 01.wav", "wav", 100, lowQualityUrl = "wav-low")),
            ),
        )

        val catalog = nodes.buildAudioTrackCatalog(WORK, AudioQualityMode.QUALITY_FIRST)

        assertEquals(1, catalog.tracks.size)
        assertEquals("wav", catalog.tracks.single().mediaStreamUrl)
    }

    @Test
    fun `quality first keeps original lossless stream instead of low quality stream`() {
        val nodes = listOf(audio("Track.wav", "wav", 100, lowQualityUrl = "m4a"))

        val catalog = nodes.buildAudioTrackCatalog(WORK, AudioQualityMode.QUALITY_FIRST)

        assertEquals("wav", catalog.tracks.single().mediaStreamUrl)
        assertEquals("wav", catalog.rootNodes.single().mediaStreamUrl)
    }

    private fun List<TrackNode>.folderTitles(): List<String> = flatMap { node ->
        if (node.type == "folder") listOf(node.title) + node.children.folderTitles() else emptyList()
    }

    private fun folder(title: String, vararg children: TrackNode) = TrackNode(
        type = "folder",
        title = title,
        children = children.toList(),
    )

    private fun audio(
        title: String,
        url: String,
        size: Long,
        lowQualityUrl: String? = null,
    ) = TrackNode(
        type = "audio",
        title = title,
        mediaStreamUrl = url,
        streamLowQualityUrl = lowQualityUrl,
        size = size,
    )

    private fun text(
        title: String,
        downloadUrl: String,
        streamUrl: String? = null,
    ) = TrackNode(
        type = "text",
        title = title,
        mediaStreamUrl = streamUrl,
        mediaDownloadUrl = downloadUrl,
    )

    private companion object {
        val WORK = Work(id = 1, title = "Work", name = "Circle")
    }
}
