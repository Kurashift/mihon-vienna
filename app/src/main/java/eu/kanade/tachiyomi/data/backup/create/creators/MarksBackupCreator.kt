package eu.kanade.tachiyomi.data.backup.create.creators

import eu.kanade.tachiyomi.data.backup.models.BackupMarks
import eu.kanade.tachiyomi.data.manga.GoodDoujinStore
import eu.kanade.tachiyomi.data.manga.MangaMarkStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MarksBackupCreator(
    private val mangaMarkStore: MangaMarkStore = Injekt.get(),
    private val goodDoujinStore: GoodDoujinStore = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),
    private val chapterRepository: ChapterRepository = Injekt.get(),
) {
    suspend operator fun invoke(): List<BackupMarks> {
        val allMarks = mangaMarkStore.marks.value.map { it to 0 } + goodDoujinStore.snapshot().map { it to 1 }
        return allMarks.mapNotNull { (mark, listType) ->
            currentCoroutineContext().ensureActive()
            val manga = mangaRepository.getMangaByIdOrNull(mark.mangaId) ?: return@mapNotNull null
            val chapter = chapterRepository.getChapterById(mark.chapterId) ?: return@mapNotNull null
            if (chapter.mangaId != manga.id) return@mapNotNull null
            BackupMarks(
                sourceId = manga.source,
                mangaUrl = manga.url,
                chapterUrl = chapter.url,
                mangaTitle = mark.mangaTitle,
                chapterName = mark.chapterName,
                markedAt = mark.markedAt,
                listType = listType,
            )
        }
    }
}
