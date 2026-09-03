package tachiyomi.data.source

import androidx.paging.PagingState
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.repository.SourcePagingSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SourceSearchPagingSource(
    source: Source,
    private val query: String,
    private val filters: FilterList,
) : BaseSourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): MangasPage {
        return source.getSearchManga(currentPage, query, filters)
    }
}

class SourcePopularPagingSource(source: Source) : BaseSourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): MangasPage {
        return source.getPopularManga(currentPage)
    }
}

class SourceLatestPagingSource(source: Source) : BaseSourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): MangasPage {
        return source.getLatestUpdates(currentPage)
    }
}

abstract class BaseSourcePagingSource(
    protected val source: Source,
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
) : SourcePagingSource() {

    private val seenManga = hashSetOf<String>()
    private var positionedPageSize: Int? = null

    abstract suspend fun requestNextPage(currentPage: Int): MangasPage

    override suspend fun load(params: LoadParams<Long>): LoadResult<Long, Manga> {
        val page = params.key ?: 1

        // A refresh restarts the page walk from scratch, so reset the dedup set. Otherwise the
        // previously seen URLs would be filtered out after an invalidation and the list would
        // come back empty (and stay empty on every following refresh).
        if (params is LoadParams.Refresh) {
            seenManga.clear()
        }

        return try {
            // A refresh asks for the page the reader was last on, carried over through
            // [getRefreshKey]. That page belongs to the result set as it was before the
            // invalidation, and a filter can shrink that set far below it: browsing the local
            // source pushes the mark filter down into the source, so switching to a filter with
            // three matches turns a 549-entry listing into three entries while the refresh key
            // still points at page 4. Serving that page as an empty result fails the refresh and
            // leaves the screen blank even though the filter matched. Fall back to the first
            // page instead, which is where a list rebuilt from a different result set starts.
            var servedPage = page
            val mangasPage = withIOContext {
                val requested = requestNextPage(servedPage.toInt())
                if (requested.mangas.isNotEmpty()) {
                    requested
                } else if (params is LoadParams.Refresh && servedPage > 1L) {
                    servedPage = 1L
                    requestNextPage(1).takeIf { it.mangas.isNotEmpty() } ?: throw NoResultsException()
                } else {
                    throw NoResultsException()
                }
            }

            val remoteManga = mangasPage.mangas
                .map { it.toDomainManga(source.id) }
                .filter { seenManga.add(it.url) }
            val manga = networkToLocalManga(remoteManga)
                .zip(remoteManga) { local, remote ->
                    // Search matches and local latest timestamps are transient source metadata.
                    // Favorites intentionally keep their database details, so carry this memo on
                    // the displayed item even when the insert query leaves the stored row alone.
                    if (local.memo == remote.memo) local else local.copy(memo = remote.memo)
                }

            val nextKey = if (mangasPage.hasNextPage) servedPage + 1 else null
            if (mangasPage.itemsBefore >= 0 && mangasPage.itemsAfter >= 0) {
                val inferredPageSize = if (servedPage > 1L) {
                    (mangasPage.itemsBefore / (servedPage - 1L)).toInt()
                } else {
                    manga.size
                }
                if (inferredPageSize > 0) {
                    positionedPageSize = maxOf(positionedPageSize ?: 0, inferredPageSize)
                }
                LoadResult.Page(
                    data = manga,
                    prevKey = if (mangasPage.itemsBefore > 0) servedPage - 1 else null,
                    nextKey = nextKey,
                    itemsBefore = mangasPage.itemsBefore,
                    itemsAfter = mangasPage.itemsAfter,
                )
            } else {
                LoadResult.Page(
                    data = manga,
                    prevKey = null,
                    nextKey = nextKey,
                )
            }
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Long, Manga>): Long? {
        val pageSize = positionedPageSize
        if (pageSize != null) {
            return state.anchorPosition?.let { position -> position / pageSize + 1L }
        }
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey ?: anchorPage?.nextKey
        }
    }
}

class NoResultsException : Exception()
