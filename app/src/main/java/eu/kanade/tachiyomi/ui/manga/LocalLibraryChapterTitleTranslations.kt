package eu.kanade.tachiyomi.ui.manga

import android.content.Context
import android.net.Uri
import eu.kanade.domain.base.BasePreferences
import logcat.LogPriority
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalChapterSyncScan
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * One confirmed local folder and everything the database knows about it.
 *
 * [chapters] holds only real chapter rows. Works whose folder is on disk but has no row at all are
 * still listed in [mangas], with a placeholder that carries the folder name; the export expands
 * those from [fileNames] while the import must not mistake them for existing records.
 */
private data class LocalFolderSnapshot(
    val mangas: List<LocalFolder>,
    val fileNamesByMangaUrl: Map<String, Set<String>>,
)

private data class LocalFolder(
    val manga: Manga,
    val chapters: List<Chapter>,
    val fileNames: Set<String>,
) {
    val existsInDatabase: Boolean
        get() = manga.id > 0
}

internal class LocalLibraryChapterTitleTranslations(
    private val context: Context = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),
    private val chapterRepository: ChapterRepository = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val updateMangaFromRemote: UpdateMangaFromRemote = Injekt.get(),
    private val basePreferences: BasePreferences = Injekt.get(),
) {

    suspend fun export(
        uri: Uri,
        format: ChapterTitleTranslationFormat,
        onlyUntranslated: Boolean,
    ): Pair<Int, Int> = withIOContext {
        val mangas = snapshot().mangas
            .map { folder -> folder.toExportPair() }
            // A folder with no chapter files is not a work, so it is neither written nor counted.
            .filter { (_, chapters) -> chapters.isNotEmpty() }
        val content = ChapterTitleTranslationCodec.encodeLocalLibrary(
            mangas = mangas,
            format = format,
            onlyUntranslated = onlyUntranslated,
            exportInstanceId = basePreferences.installationId.get().takeIf(String::isNotBlank),
        )
        context.contentResolver.openOutputStream(uri, "wt")
            ?.bufferedWriter(Charsets.UTF_8)
            ?.use { it.write(content) }
            ?: error("Unable to open local library translation export file")
        // Report what the file actually contains. A manga with any missing title includes all of
        // its chapters so existing translations can serve as naming context outside the app.
        val count = if (onlyUntranslated) {
            val mangasWithUntranslated = mangas.filter { (_, chapters) ->
                chapters.any { it.isUntranslated() }
            }
            mangasWithUntranslated.size to mangasWithUntranslated.sumOf { (_, chapters) -> chapters.size }
        } else {
            mangas.size to mangas.sumOf { (_, chapters) -> chapters.size }
        }
        count
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
        val folders = snapshot()
        val plan = ChapterTitleTranslationCodec.planLocalLibraryImport(
            document = document,
            // Only real records: a folder the database has never recorded looks exactly like an
            // unknown folder here, which is what the plan needs to materialize.
            currentMangas = folders.mangas
                .filter { folder -> folder.existsInDatabase }
                .map { folder -> folder.manga to folder.chapters },
            currentInstanceId = basePreferences.installationId.get().takeIf(String::isNotBlank),
            diskChapterFileNamesByMangaUrl = folders.fileNamesByMangaUrl,
        )
        if (plan.updates.isNotEmpty()) {
            chapterRepository.updateAll(plan.updates)
        }
        if (plan.pendingByMangaUrl.isEmpty()) return plan
        // The pending entries only become real writes once their rows exist, and a folder that
        // cannot be read must not be reported as imported, so the plan is rebuilt from what was
        // actually stored.
        val applied = storePendingTranslations(plan.pendingByMangaUrl)
        if (applied.isNotEmpty()) {
            chapterRepository.updateAll(applied)
        }
        return plan.copy(
            updates = plan.updates + applied,
            pendingByMangaUrl = emptyMap(),
        )
    }

    /**
     * Writes translations whose chapter had no row yet. Each folder is registered through the same
     * source update the app uses everywhere else, so chapters, page counts and dates come from the
     * scanner rather than from a hand-built row, and the translations are then applied by URL.
     * Returns only the translations that were really stored.
     */
    private suspend fun storePendingTranslations(
        pendingByMangaUrl: Map<String, List<LocalLibraryPendingTranslation>>,
    ): List<ChapterUpdate> {
        val source = sourceManager.get(LocalSource.ID) as? LocalSource ?: return emptyList()
        pendingByMangaUrl.entries
            .sortedBy { (mangaUrl, _) -> mangaUrl }
            .forEach { (mangaUrl, _) ->
                val manga = mangaRepository.getMangaByUrlAndSourceId(mangaUrl, LocalSource.ID)
                    ?: registerManga(source, mangaUrl)
                updateMangaFromRemote(
                    source = source,
                    manga = manga,
                    fetchChapters = true,
                ).onFailure { error ->
                    logcat(LogPriority.ERROR, error) { "Unable to register local chapters of $mangaUrl" }
                }
            }
        // Read the rows back once: the update assigns ids, page counts and dates, and only the
        // translations still have to be stored.
        return pendingByMangaUrl.flatMap { (mangaUrl, entries) ->
            val manga = mangaRepository.getMangaByUrlAndSourceId(mangaUrl, LocalSource.ID)
                ?: return@flatMap emptyList()
            val chaptersByUrl = chapterRepository.getChapterByMangaId(manga.id).associateBy(Chapter::url)
            entries.mapNotNull { entry ->
                val chapter = chaptersByUrl["$mangaUrl/${entry.fileName}"] ?: return@mapNotNull null
                ChapterUpdate(id = chapter.id, translatedName = entry.translatedName)
            }
        }
    }

    /** Creates the manga row an unopened folder needs before its chapters can be scanned. */
    private suspend fun registerManga(source: LocalSource, mangaUrl: String): Manga {
        return networkToLocalManga(
            Manga.create().copy(
                source = source.id,
                url = mangaUrl,
                title = mangaUrl,
            ),
        )
    }

    private suspend fun snapshot(): LocalFolderSnapshot = withIOContext {
        val source = sourceManager.get(LocalSource.ID) as? LocalSource
            ?: error("Local source is unavailable")
        val scan = confirmedScan(source)
        val fileNamesByMangaUrl = scan.chapterFileNamesByMangaUrl
        LocalFolderSnapshot(
            mangas = fileNamesByMangaUrl.keys.sorted().map { mangaUrl ->
                val manga = mangaRepository.getMangaByUrlAndSourceId(mangaUrl, LocalSource.ID)
                val chapters = manga?.let { chapterRepository.getChapterByMangaId(it.id) }.orEmpty()
                LocalFolder(
                    manga = manga ?: Manga.create().copy(url = mangaUrl, title = mangaUrl),
                    chapters = chapters,
                    fileNames = fileNamesByMangaUrl[mangaUrl].orEmpty(),
                )
            },
            fileNamesByMangaUrl = fileNamesByMangaUrl,
        )
    }

    private suspend fun confirmedScan(source: LocalSource): LocalChapterSyncScan {
        val scan = source.scanChapterChanges()
        check(scan.isReliable) { "Local library storage is unavailable" }
        return scan
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

/** Expands a folder to the chapters the template should list, database rows plus unwritten files. */
private fun LocalFolder.toExportPair(): Pair<Manga, List<Chapter>> {
    return manga to ChapterTitleTranslationCodec.diskBackedChapters(
        mangaId = manga.id,
        mangaUrl = manga.url,
        dbChapters = chapters,
        diskFileNames = fileNames,
    )
}
