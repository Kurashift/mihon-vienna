package eu.kanade.tachiyomi.data.coil

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import tachiyomi.source.local.image.LocalChapterCover
import tachiyomi.source.local.image.LocalChapterCoverManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class LocalChapterCoverFetcher(
    private val data: LocalChapterCover,
    private val manager: LocalChapterCoverManager,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val file = manager.getOrCreate(data) ?: error("No local chapter cover available")
        return SourceFetchResult(
            source = ImageSource(
                file = file.toOkioPath(),
                fileSystem = FileSystem.SYSTEM,
                // Include the DB-derived version so a freshly written custom cover is not
                // shadowed by Coil's disk cache, which would otherwise serve the stale snapshot
                // forever because the on-disk file name is stable.
                diskCacheKey = "${file.name};${data.version}",
            ),
            mimeType = "image/webp",
            dataSource = DataSource.DISK,
        )
    }

    class Factory(
        private val manager: LocalChapterCoverManager = Injekt.get(),
    ) : Fetcher.Factory<LocalChapterCover> {
        override fun create(
            data: LocalChapterCover,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = LocalChapterCoverFetcher(data, manager)
    }
}

class LocalChapterCoverKeyer : Keyer<LocalChapterCover> {
    override fun key(data: LocalChapterCover, options: Options): String {
        return "local-chapter-cover:${data.chapterUrl};${data.version}"
    }
}
