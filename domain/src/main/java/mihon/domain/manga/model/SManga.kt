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
        // The row is created once and grows afterwards, so this is the moment the work first
        // showed up in the local library - and it is what the local source's date sort reads as
        // the import date. Importing more chapters into it re-dates it to that later import, so
        // the column means "when this work last entered the library", not "when its row was
        // made". Shelf actions overwrite it with their own timestamp afterwards, so for remote
        // sources the value stays invisible until the work is favorited.
        dateAdded = Clock.System.now().toEpochMilliseconds(),
    )
}
