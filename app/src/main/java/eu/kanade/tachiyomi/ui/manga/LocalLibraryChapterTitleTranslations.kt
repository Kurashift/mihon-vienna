package eu.kanade.tachiyomi.ui.manga

import android.content.Context
import android.net.Uri
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal class LocalLibraryChapterTitleTranslations(
    private val context: Context = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),
    private val chapterRepository: ChapterRepository = Injekt.get(),
) {

    suspend fun export(
        uri: Uri,
        format: ChapterTitleTranslationFormat,
    ): Pair<Int, Int> = withIOContext {
        val mangas = getCurrentMangasWithChapters()
        val content = ChapterTitleTranslationCodec.encodeLocalLibrary(mangas, format)
        context.contentResolver.openOutputStream(uri, "wt")
            ?.bufferedWriter(Charsets.UTF_8)
            ?.use { it.write(content) }
            ?: error("Unable to open local library translation export file")
        mangas.size to mangas.sumOf { (_, chapters) -> chapters.size }
    }

    suspend fun importLibrary(uri: Uri): LocalLibraryChapterTitleImportPlan = withIOContext {
        val document = readDocument(uri) { ChapterTitleTranslationCodec.decodeLocalLibrary(it) }
        import(document)
    }

    suspend fun importMangaFiles(uris: List<Uri>): LocalLibraryChapterTitleImportPlan = withIOContext {
        val documents = uris.flatMap { uri ->
            val content = readText(uri)
            runCatching { ChapterTitleTranslationCodec.decodeLocalLibrary(content).mangas }
                .getOrElse { listOf(ChapterTitleTranslationCodec.decode(content)) }
        }
        import(LocalLibraryChapterTitleTranslationDocument(mangas = documents))
    }

    private suspend fun import(
        document: LocalLibraryChapterTitleTranslationDocument,
    ): LocalLibraryChapterTitleImportPlan {
        val plan = ChapterTitleTranslationCodec.planLocalLibraryImport(
            document = document,
            currentMangas = getCurrentMangasWithChapters(),
        )
        if (plan.updates.isNotEmpty()) {
            chapterRepository.updateAll(plan.updates)
        }
        return plan
    }

    private suspend fun getCurrentMangasWithChapters() = buildList {
        val source = sourceManager.get(LocalSource.ID) as? LocalSource
            ?: error("Local source is unavailable")
        val scan = source.scanChapterChanges()
        check(scan.isReliable) { "Local library storage is unavailable" }

        scan.chapterFileNamesByMangaUrl.keys.sorted().forEach { mangaUrl ->
            val manga = mangaRepository.getMangaByUrlAndSourceId(mangaUrl, LocalSource.ID)
                ?: return@forEach
            val currentChapterUrls = scan.chapterFileNamesByMangaUrl[mangaUrl]
                .orEmpty()
                .mapTo(hashSetOf()) { fileName -> "$mangaUrl/$fileName" }
            val chapters = chapterRepository.getChapterByMangaId(manga.id)
                .filter { it.url in currentChapterUrls }
            add(manga to chapters)
        }
    }

    private inline fun <T> readDocument(uri: Uri, decode: (String) -> T): T {
        return decode(readText(uri))
    }

    private fun readText(uri: Uri): String {
        return context.contentResolver.openInputStream(uri)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            ?: error("Unable to open local library translation import file")
    }
}
