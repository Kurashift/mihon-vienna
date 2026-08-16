package tachiyomi.domain.manga.interactor

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.manga.model.MangaProgressByMangaId
import tachiyomi.domain.manga.repository.MangaRepository

class GetMangaProgress(
    private val mangaRepository: MangaRepository,
) {

    fun getForSource(sourceId: Long): Flow<List<MangaProgressByMangaId>> {
        return mangaRepository.getMangaProgressBySourceAsFlow(sourceId)
    }

    suspend fun awaitForSource(sourceId: Long): List<MangaProgressByMangaId> {
        return mangaRepository.getMangaProgressBySource(sourceId)
    }
}
