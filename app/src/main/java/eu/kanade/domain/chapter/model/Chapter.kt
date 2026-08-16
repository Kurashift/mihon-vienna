package eu.kanade.domain.chapter.model

import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import tachiyomi.domain.chapter.model.Chapter
import eu.kanade.tachiyomi.data.database.models.Chapter as DbChapter

// TODO: Remove when all deps are migrated
fun Chapter.toSChapter(): SChapter {
    return SChapter.create().also {
        it.url = url
        it.name = name
        it.date_upload = dateUpload
        it.chapter_number = chapterNumber.toFloat()
        it.scanlator = scanlator
        it.memo = memo
    }
}

fun Chapter.copyFromSChapter(sChapter: SChapter): Chapter {
    val pageCount = sChapter.memo["mihon.pageCount"]
        ?.jsonPrimitive
        ?.intOrNull
        ?.toLong()
        ?: 0L
    return this.copy(
        name = sChapter.name,
        url = sChapter.url,
        dateUpload = sChapter.date_upload,
        chapterNumber = sChapter.chapter_number.toDouble(),
        scanlator = sChapter.scanlator?.ifBlank { null }?.trim(),
        memo = sChapter.memo,
        totalPages = if (this.totalPages > 0L) this.totalPages else pageCount,
    )
}

fun Chapter.toDbChapter(): DbChapter = ChapterImpl().also {
    it.id = id
    it.manga_id = mangaId
    it.url = url
    it.name = name
    it.scanlator = scanlator
    it.read = read
    it.bookmark = bookmark
    it.last_page_read = lastPageRead.toInt()
    it.total_pages = totalPages.toInt()
    it.date_fetch = dateFetch
    it.date_upload = dateUpload
    it.chapter_number = chapterNumber.toFloat()
    it.source_order = sourceOrder.toInt()
    it.memo = memo
}
