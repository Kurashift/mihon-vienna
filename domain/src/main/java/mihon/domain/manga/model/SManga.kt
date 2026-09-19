package mihon.domain.manga.model

import eu.kanade.tachiyomi.source.model.SManga
import tachiyomi.domain.manga.model.Manga
import kotlin.time.Clock

fun SManga.toDomainManga(sourceId: Long): Manga {
    return Manga.create().copy(
        url = url,
        title = title,
        artist = artist,
        author = author,
        description = description,
        genre = getGenres(),
        status = status.toLong(),
        thumbnailUrl = thumbnail_url,
        updateStrategy = update_strategy,
        initialized = initialized,
        memo = memo,
        source = sourceId,
        // The row only gets created once, so this is the moment the work was first seen, and it
        // is what the local source's date sort reads as the import date. Shelf actions overwrite
        // it with their own timestamp afterwards, so for remote sources the value stays
        // invisible until the work is favorited.
        dateAdded = Clock.System.now().toEpochMilliseconds(),
    )
}
