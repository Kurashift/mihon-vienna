package eu.kanade.tachiyomi.data.backup.restore.restorers

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import eu.kanade.tachiyomi.data.backup.models.BackupMarks
import eu.kanade.tachiyomi.data.manga.GoodDoujinStore
import eu.kanade.tachiyomi.data.manga.MangaMark
import eu.kanade.tachiyomi.data.manga.MangaMarkStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tachiyomi.data.Database
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MarksRestorer(
    private val database: Database = Injekt.get(),
) {
    suspend operator fun invoke(backupMarks: List<BackupMarks>) {
        if (backupMarks.isEmpty()) return
        val mangaMarkStore = Injekt.get<MangaMarkStore>()
        val goodDoujinStore = Injekt.get<GoodDoujinStore>()
        val getMangaByUrlAndSourceId = Injekt.get<GetMangaByUrlAndSourceId>()
        for (mark in backupMarks) {
            currentCoroutineContext().ensureActive()
            val manga = getMangaByUrlAndSourceId.await(mark.mangaUrl, mark.sourceId) ?: continue
            val chapter = database.chaptersQueries
                .getChapterByUrlAndMangaId(mark.chapterUrl, manga.id)
                .awaitAsOneOrNull() ?: continue
            val store = if (mark.listType == 0) mangaMarkStore else goodDoujinStore
            store.add(
                MangaMark(
                    mangaId = manga.id,
                    mangaTitle = mark.mangaTitle.ifBlank { manga.title },
                    chapterId = chapter._id,
                    chapterName = mark.chapterName.ifBlank { chapter.name },
                    markedAt = mark.markedAt,
                ),
            )
        }
    }
}
