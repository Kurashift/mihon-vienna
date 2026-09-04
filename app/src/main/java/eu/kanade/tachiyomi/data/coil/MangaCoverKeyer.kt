package eu.kanade.tachiyomi.data.coil

import coil3.key.Keyer
import coil3.request.Options
import eu.kanade.domain.manga.model.hasCustomCover
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.manga.model.Manga as DomainManga

class MangaKeyer : Keyer<DomainManga> {
    override fun key(data: DomainManga, options: Options): String {
        return if (data.hasCustomCover()) {
            "${data.id};${data.coverLastModified}"
        } else {
            "${data.thumbnailUrl};${data.coverLastModified}"
        }
    }
}

class MangaCoverKeyer : Keyer<MangaCover> {
    override fun key(data: MangaCover, options: Options): String {
        // Custom covers are only stored for library items, and lastModified already
        // changes when a custom cover is set. Avoid File.exists() on the scroll path.
        return if (data.isMangaFavorite) {
            "${data.mangaId};${data.lastModified}"
        } else {
            "${data.url};${data.lastModified}"
        }
    }
}
