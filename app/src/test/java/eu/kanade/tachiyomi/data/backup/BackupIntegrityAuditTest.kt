package eu.kanade.tachiyomi.data.backup

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupMarks
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.image.LocalChapterCoverManager
import java.io.IOException

@OptIn(ExperimentalSerializationApi::class)
class BackupIntegrityAuditTest {
    @Test
    fun `five hundred works and three thousand chapters retain complete serialized entries`() {
        val buffer = Buffer()
        val writer = BackupWriter(buffer, ProtoBuf)
        val entries = (1..500).map { work ->
            BackupManga(
                source = LocalSource.ID,
                url = "work-$work",
                title = "title-$work",
                favorite = work % 2 == 0,
                chapters = (1..6).map { chapter ->
                    BackupChapter(
                        url = "work-$work/chapter-$chapter.cbz",
                        name = "chapter-$chapter",
                        read = chapter % 2 == 0,
                        bookmark = chapter % 3 == 0,
                        lastPageRead = 5,
                        totalPages = 22,
                        customOrder = (7 - chapter).toLong(),
                        translatedName = "translated-$work-$chapter",
                        customCover = byteArrayOf(work.toByte(), chapter.toByte()),
                        markedReadAt = 1234,
                    ).apply { chapterId = (work * 6 + chapter).toLong() }
                },
            )
        }
        val metadata = Backup(
            backupManga = emptyList(),
            backupMarks = entries.flatMap { manga ->
                (0..1).map { type ->
                    BackupMarks(manga.source, manga.url, manga.chapters[0].url, manga.title, "first", 123, type)
                }
            },
        )
        entries.forEach(writer::writeEntry)
        writer.writeMetadata(metadata)
        var chapterCount = 0
        BackupReader(buffer, ProtoBuf).use { reader ->
            entries.forEach { expected ->
                assertTrue(reader.nextEntry())
                val actual = reader.readEntry()
                assertArrayEquals(
                    ProtoBuf.encodeToByteArray(BackupManga.serializer(), expected),
                    ProtoBuf.encodeToByteArray(BackupManga.serializer(), actual),
                )
                assertTrue(actual.chapters.all { it.chapterId == 0L })
                chapterCount += actual.chapters.size
            }
            assertFalse(reader.nextEntry())
            assertEquals(metadata.backupMarks, reader.readMetadata().backupMarks)
        }
        assertEquals(3000, chapterCount)
    }

    @Test
    fun `creator and wire roundtrip retain chapter fields and local cover bytes`() = runTest {
        val fixture = Fixture()
        val history = mockk<GetHistory> { coEvery { await(any()) } returns emptyList() }
        val creator = MangaBackupCreator(fixture.database, fixture.categories, history, fixture.covers)
        coEvery { fixture.covers.exportCustom(9002) } returns byteArrayOf(4, 5, 6)

        val created = creator(fixture.manga, BackupOptions())
        val restored = ProtoBuf.decodeFromByteArray(
            BackupManga.serializer(),
            ProtoBuf.encodeToByteArray(BackupManga.serializer(), created),
        )
        val chapter = restored.chapters.single()

        assertEquals(fixture.manga.url, restored.url)
        assertEquals(3L, restored.categories.single())
        assertEquals(5L, chapter.lastPageRead)
        assertEquals(22L, chapter.totalPages)
        assertTrue(chapter.bookmark)
        assertEquals(17L, chapter.customOrder)
        assertEquals("translated", chapter.translatedName)
        assertArrayEquals(byteArrayOf(4, 5, 6), chapter.customCover)
        assertEquals(0L, chapter.chapterId)
        assertArrayEquals(MemoColumnAdapter.encode(fixture.chapter.memo), chapter.memo)
    }

    @Test
    fun `excluding chapters also excludes their progress translation order and cover bytes`() = runTest {
        val fixture = Fixture()
        val history = mockk<GetHistory> { coEvery { await(any()) } returns emptyList() }
        val creator = MangaBackupCreator(fixture.database, fixture.categories, history, fixture.covers)

        assertTrue(creator(fixture.manga, BackupOptions(chapters = false)).chapters.isEmpty())
        coVerify(exactly = 0) { fixture.covers.exportCustom(any()) }
    }

    @Test
    fun `fresh restore maps chapter history category and cover to destination identities`() = runTest {
        val fixture = Fixture()
        coEvery { fixture.mangaLookup.await(any(), any()) } returns null
        coEvery { fixture.chapters.await(9001) } returnsMany listOf(emptyList(), listOf(fixture.chapter))
        val backup = fixture.backup().apply {
            categories = listOf(3)
            history = listOf(BackupHistory(fixture.chapter.url, 9876, 4321))
        }

        fixture.restorer.restore(backup, listOf(BackupCategory("reading", order = 3, id = 1)))

        val insert = fixture.driver.statements.single { it.sql.startsWith("INSERT INTO chapters(") }
        assertEquals(9001L, insert.values[0])
        assertEquals(5L, insert.values[9])
        assertEquals(22L, insert.values[10])
        assertEquals(17L, insert.values[11])
        assertEquals("translated", insert.values[18])
        val history = fixture.driver.statements.single { it.sql.startsWith("INSERT INTO history(") }
        assertEquals(listOf(9002L, 9876L, 4321L), history.values.take(3))
        val category = fixture.driver.statements.single { it.sql.startsWith("INSERT INTO mangas_categories") }
        assertEquals(listOf(9001L, 8001L), category.values)
        coVerify(exactly = 1) { fixture.covers.restoreCustom(9002, byteArrayOf(4, 5, 6)) }
        assertTrue(fixture.driver.events.indexOf("commit") < fixture.driver.events.indexOf("cover"))
    }

    @Test
    fun `existing restore preserves identity farther valid progress and bookmark`() = runTest {
        val fixture = Fixture()
        val backup = fixture.backup().apply {
            chapters.single().apply {
                lastPageRead = 2
                bookmark = false
                customOrder = 9
            }
        }

        fixture.restorer.restore(backup, emptyList())

        val update = fixture.driver.chapterUpdate()
        assertEquals(9002L, update.argumentAfter("WHERE _id ="))
        assertEquals(5L, update.argumentAfter("ELSE coalesce("))
        assertEquals(true, update.argumentAfter("bookmark = coalesce("))
        assertEquals(9L, update.argumentAfter("custom_order = coalesce("))
        assertEquals("translated", update.argumentAfter("translated_name = coalesce("))
    }

    @Test
    fun `legacy chapter defaults are observable when restoring over a manual order`() = runTest {
        val fixture = Fixture()
        val backup = fixture.backup().apply {
            chapters = listOf(BackupChapter(url = fixture.chapter.url, name = "legacy", lastPageRead = 2))
        }

        fixture.restorer.restore(backup, emptyList())

        val update = fixture.driver.chapterUpdate()
        assertEquals(17L, fixture.chapter.customOrder)
        assertEquals(0L, update.argumentAfter("custom_order = coalesce("))
        assertEquals(null, update.argumentAfter("translated_name = coalesce("))
        assertArrayEquals("{}".toByteArray(), update.argumentAfter("memo = coalesce(") as ByteArray)
    }

    @Test
    fun `cover rejection currently returns normally after database commit`() = runTest {
        val fixture = Fixture()
        coEvery { fixture.covers.restoreCustom(any(), any()) } returns false

        fixture.restorer.restore(fixture.backup(), emptyList())

        assertTrue("commit" in fixture.driver.events)
        coVerify(exactly = 1) { fixture.covers.restoreCustom(9002, any()) }
    }

    @Test
    fun `history merge keeps newer date and adds only missing duration`() = runTest {
        val fixture = Fixture()
        fixture.driver.historyRow = listOf(77L, 9002L, 12000L, 300L)
        val backup = fixture.backup().apply { history = listOf(BackupHistory(fixture.chapter.url, 9000, 500)) }

        fixture.restorer.restore(backup, emptyList())

        val upsert = fixture.driver.statements.single { it.sql.startsWith("INSERT INTO history(") }
        assertEquals(listOf(9002L, 12000L, 200L), upsert.values.take(3))
    }

    @Test
    fun `database failure rolls back before custom cover restore`() = runTest {
        val fixture = Fixture()
        fixture.driver.failChapterUpdate = true

        val backup = fixture.backup().apply { chapters.single().customOrder = 9 }
        assertThrows<IOException> { fixture.restorer.restore(backup, emptyList()) }

        assertTrue("rollback" in fixture.driver.events)
        assertFalse("commit" in fixture.driver.events)
        coVerify(exactly = 0) { fixture.covers.restoreCustom(any(), any()) }
    }

    private class Fixture {
        val manga = Manga.create().copy(
            id = 9001,
            source = LocalSource.ID,
            url = "work",
            title = "work",
            favorite = true,
        )
        val chapter = Chapter.create().copy(
            id = 9002, mangaId = 9001, url = "work/chapter.cbz", name = "chapter", totalPages = 22,
            lastPageRead = 5, customOrder = 17, translatedName = "translated", bookmark = true,
        )
        val driver = AuditDriver(chapter)
        val database = Database(
            driver = driver,
            historyAdapter = History.Adapter(DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter, MemoColumnAdapter),
            chaptersAdapter = Chapters.Adapter(MemoColumnAdapter),
        )
        val categories = mockk<GetCategories> {
            coEvery { await() } returns listOf(Category(8001, "reading", 3, 0))
            coEvery { await(any()) } returns listOf(Category(8001, "reading", 3, 0))
        }
        val mangaLookup = mockk<GetMangaByUrlAndSourceId> { coEvery { await(any(), any()) } returns manga }
        val chapters = mockk<GetChaptersByMangaId> { coEvery { await(any<Long>(), any()) } returns listOf(chapter) }
        val covers = mockk<LocalChapterCoverManager> {
            coEvery { restoreCustom(any(), any()) } coAnswers {
                driver.events.add("cover")
                true
            }
        }
        private val fetchInterval = mockk<FetchInterval> { every { getWindow(any(), any()) } returns (0L to 0L) }
        private val updateManga = mockk<UpdateManga>(relaxed = true)
        private val tracks = mockk<GetTracks> { coEvery { await(any()) } returns emptyList() }
        val restorer = MangaRestorer(
            database, categories, mangaLookup, chapters, updateManga, tracks, mockk(), covers, fetchInterval,
        )

        fun backup() = BackupManga(
            source = LocalSource.ID,
            url = manga.url,
            title = manga.title,
            chapters = listOf(
                BackupChapter(
                    url = chapter.url,
                    name = chapter.name,
                    lastPageRead = 5,
                    totalPages = 22,
                    bookmark = true,
                    translatedName = "translated",
                    customOrder = 17,
                    customCover = byteArrayOf(4, 5, 6),
                ),
            ),
        )
    }

    private data class Statement(val sql: String, val values: List<Any?>) {
        fun argumentAfter(fragment: String): Any? {
            val start = sql.indexOf(fragment)
            require(start >= 0)
            val marker = sql.indexOf('?', start)
            return values[sql.take(marker).count { it == '?' }]
        }
    }

    private class AuditDriver(private val chapter: Chapter) : SqlDriver {
        val statements = mutableListOf<Statement>()
        val events = mutableListOf<String>()
        var historyRow: List<Any?>? = null
        var failChapterUpdate = false
        private var transaction: Transacter.Transaction? = null

        fun chapterUpdate() = statements.single { it.sql.startsWith("UPDATE chapters") }

        override fun currentTransaction(): Transacter.Transaction? = transaction

        override fun newTransaction(): QueryResult<Transacter.Transaction> {
            val parent = transaction
            val created = object : Transacter.Transaction() {
                override val enclosingTransaction = parent
                override fun endTransaction(successful: Boolean): QueryResult<Unit> {
                    val outcome = if (successful) "commit" else "rollback"
                    events.add(if (parent == null) outcome else "nested-$outcome")
                    transaction = parent
                    return QueryResult.Value(Unit)
                }
            }
            transaction = created
            return QueryResult.Value(created)
        }

        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> {
            record(sql, parameters, binders)
            if (failChapterUpdate && sql.startsWith("UPDATE chapters")) throw IOException("injected update failure")
            return QueryResult.Value(1L)
        }

        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> {
            record(sql, parameters, binders)
            val rows = when {
                sql.startsWith("INSERT INTO mangas(") -> listOf(listOf(9001L))
                "FROM history" in sql -> listOfNotNull(historyRow)
                "FROM chapters" in sql -> listOf(
                    listOf(
                        chapter.id, chapter.mangaId, chapter.url, chapter.name, chapter.scanlator,
                        chapter.read, chapter.bookmark, chapter.lastPageRead, chapter.chapterNumber,
                        chapter.sourceOrder, chapter.dateFetch, chapter.dateUpload, chapter.lastModifiedAt,
                        chapter.version, 0L, MemoColumnAdapter.encode(chapter.memo), chapter.totalPages,
                        chapter.customOrder, chapter.translatedName, chapter.markedReadAt,
                    ),
                )
                else -> emptyList()
            }
            var position = -1
            return mapper(object : SqlCursor {
                override fun next() = QueryResult.Value(++position < rows.size)
                override fun getLong(index: Int) = rows[position][index] as Long?
                override fun getString(index: Int) = rows[position][index] as String?
                override fun getBytes(index: Int) = rows[position][index] as ByteArray?
                override fun getDouble(index: Int) = rows[position][index] as Double?
                override fun getBoolean(index: Int) = rows[position][index] as Boolean?
            })
        }

        private fun record(sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?) {
            val values = MutableList<Any?>(parameters) { null }
            binders?.invoke(object : SqlPreparedStatement {
                override fun bindBytes(index: Int, bytes: ByteArray?) {
                    values[index] = bytes
                }
                override fun bindLong(index: Int, long: Long?) {
                    values[index] = long
                }
                override fun bindDouble(index: Int, double: Double?) {
                    values[index] = double
                }
                override fun bindString(index: Int, string: String?) {
                    values[index] = string
                }
                override fun bindBoolean(index: Int, boolean: Boolean?) {
                    values[index] = boolean
                }
            })
            statements.add(Statement(sql, values))
        }

        override fun addListener(vararg queryKeys: String, listener: Query.Listener) = Unit
        override fun removeListener(vararg queryKeys: String, listener: Query.Listener) = Unit
        override fun notifyListeners(vararg queryKeys: String) = Unit
        override fun close() = Unit
    }
}
