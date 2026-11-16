package tachiyomi.data.chapter

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import java.io.IOException

class ChapterRepositorySourceSyncTest {

    @Test
    fun `delete failure rolls back before any insert or update`() = runTest {
        val driver = RecordingDriver()
        val failure = IOException("delete failed")
        driver.failure = "delete" to failure

        try {
            repository(driver).applySourceSync(
                listOf(20L),
                listOf(chapter("new")),
                listOf(ChapterUpdate(id = 10L, name = "Updated")),
            )
            fail<Unit>("Expected database failure")
        } catch (actual: IOException) {
            assertSame(failure, actual)
        }

        assertEquals(listOf("begin", "delete", "rollback"), driver.operations)
        assertTrue(driver.notifications.isEmpty())
    }

    @Test
    fun `delete insert and source updates use one committed transaction`() = runTest {
        val driver = RecordingDriver()
        val repository = repository(driver)

        val inserted = repository.applySourceSync(
            listOf(20L),
            listOf(chapter("new")),
            listOf(ChapterUpdate(id = 10L, name = "Updated")),
        )

        assertEquals(listOf(100L), inserted.map { it.id })
        assertEquals(listOf("begin", "delete", "lookup", "insert", "update", "commit"), driver.operations)
        assertTrue(driver.notifications.isNotEmpty())
    }

    @Test
    fun `late database failure rolls back and propagates instead of returning an empty list`() = runTest {
        val driver = RecordingDriver()
        val failure = IOException("update failed")
        driver.failure = "update" to failure

        try {
            repository(driver).applySourceSync(
                listOf(20L),
                listOf(chapter("new")),
                listOf(ChapterUpdate(id = 10L, name = "Updated")),
            )
            fail<Unit>("Expected database failure")
        } catch (actual: IOException) {
            assertSame(failure, actual)
        }

        assertEquals(listOf("begin", "delete", "lookup", "insert", "update", "rollback"), driver.operations)
        assertTrue(driver.notifications.isEmpty())
    }

    @Test
    fun `insert failure rolls back prior deletion and propagates`() = runTest {
        val driver = RecordingDriver()
        val failure = IOException("insert failed")
        driver.failure = "insert" to failure

        try {
            repository(driver).applySourceSync(listOf(20L), listOf(chapter("new")), emptyList())
            fail<Unit>("Expected insertion failure")
        } catch (actual: IOException) {
            assertSame(failure, actual)
        }

        assertEquals(listOf("begin", "delete", "lookup", "insert", "rollback"), driver.operations)
        assertTrue(driver.notifications.isEmpty())
    }

    @Test
    fun `cancellation rolls back a completed insert before commit`() = runTest {
        val driver = RecordingDriver()
        val job = launch {
            val syncJob = currentCoroutineContext()[Job]!!
            driver.afterInsert = { syncJob.cancel(CancellationException("cancelled source sync")) }
            repository(driver).applySourceSync(listOf(20L), listOf(chapter("new")), emptyList())
        }
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(listOf("begin", "delete", "lookup", "insert", "rollback"), driver.operations)
        assertTrue(driver.notifications.isEmpty())
    }

    @Test
    fun `duplicate new chapter URLs insert once per manga`() = runTest {
        val driver = RecordingDriver()
        val chapter = chapter("new")

        val inserted = repository(driver).applySourceSync(emptyList(), listOf(chapter, chapter), emptyList())

        assertEquals(1, inserted.size)
        assertEquals(listOf("begin", "lookup", "insert", "commit"), driver.operations)
    }

    @Test
    fun `a chapter inserted by another sync keeps its stable ID and user state`() = runTest {
        val driver = RecordingDriver()
        driver.existingRow = listOf(
            77L, 1L, "new", "new", null, true, true, 6L, 1.0, 0L, 0L, 0L, 0L, 1L, 0L,
            "{}".encodeToByteArray(), 10L, 7L, "Translated", 42L,
        )

        val inserted = repository(driver).applySourceSync(emptyList(), listOf(chapter("new")), emptyList())

        assertTrue(inserted.isEmpty())
        assertEquals(listOf("begin", "lookup", "commit"), driver.operations)
        assertTrue(driver.notifications.isEmpty())
    }

    @Test
    fun `source sync ignores user fields even when supplied by a stale caller`() = runTest {
        val driver = RecordingDriver()

        repository(driver).applySourceSync(
            emptyList(),
            emptyList(),
            listOf(
                ChapterUpdate(
                    id = 10L,
                    name = "Updated",
                    read = false,
                    bookmark = false,
                    lastPageRead = 0L,
                    totalPages = 20L,
                    customOrder = 0L,
                    translatedName = "Stale",
                    markedReadAt = 0L,
                ),
            ),
        )

        val assignments = driver.statements.single().substringAfter("SET").substringBefore("WHERE")
        val userColumns = listOf(
            "read",
            "bookmark",
            "last_page_read",
            "custom_order",
            "translated_name",
            "marked_read_at",
        )
        userColumns.forEach { column ->
            assertFalse(Regex("\\b$column\\b").containsMatchIn(assignments), column)
        }
        assertEquals(listOf("begin", "update", "commit"), driver.operations)
    }

    private fun chapter(url: String) = Chapter.create().copy(mangaId = 1L, url = url, name = url)

    private fun repository(driver: SqlDriver) = ChapterRepositoryImpl(
        Database(
            driver = driver,
            historyAdapter = History.Adapter(DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter, MemoColumnAdapter),
            chaptersAdapter = Chapters.Adapter(MemoColumnAdapter),
        ),
    )

    private class RecordingDriver : SqlDriver {
        val operations = mutableListOf<String>()
        val statements = mutableListOf<String>()
        val notifications = mutableListOf<String>()
        var failure: Pair<String, Throwable>? = null
        var afterInsert: () -> Unit = {}
        var existingRow: List<Any?>? = null
        private var transaction: Transacter.Transaction? = null
        private var nextId = 100L

        override fun newTransaction(): QueryResult<Transacter.Transaction> {
            check(transaction == null)
            operations.add("begin")
            val created = object : Transacter.Transaction() {
                override val enclosingTransaction: Transacter.Transaction? = null

                override fun endTransaction(successful: Boolean): QueryResult<Unit> {
                    operations.add(if (successful) "commit" else "rollback")
                    transaction = null
                    return QueryResult.Value(Unit)
                }
            }
            transaction = created
            return QueryResult.Value(created)
        }

        override fun currentTransaction(): Transacter.Transaction? = transaction

        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> {
            record(sql, binders)
            return QueryResult.Value(1L)
        }

        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> {
            val operation = record(sql, binders)
            val rows = if (operation == "insert") listOf(listOf(nextId++)) else listOfNotNull(existingRow)
            if (operation == "insert") afterInsert()
            var position = -1
            val cursor = object : SqlCursor {
                override fun next() = QueryResult.Value(++position < rows.size)
                override fun getLong(index: Int): Long? = rows[position][index] as Long?
                override fun getString(index: Int): String? = rows[position][index] as String?
                override fun getBytes(index: Int): ByteArray? = rows[position][index] as ByteArray?
                override fun getDouble(index: Int): Double? = rows[position][index] as Double?
                override fun getBoolean(index: Int): Boolean? = rows[position][index] as Boolean?
            }
            return mapper(cursor)
        }

        private fun record(sql: String, binders: (SqlPreparedStatement.() -> Unit)?): String {
            check(transaction != null)
            val statement = sql.trimStart()
            val operation = when {
                statement.startsWith("DELETE") -> "delete"
                statement.startsWith("INSERT") -> "insert"
                statement.startsWith("UPDATE") -> "update"
                else -> "lookup"
            }
            statements.add(statement)
            operations.add(operation)
            binders?.invoke(mockk(relaxed = true))
            failure?.takeIf { it.first == operation }?.let { throw it.second }
            return operation
        }

        override fun addListener(vararg queryKeys: String, listener: Query.Listener) = Unit
        override fun removeListener(vararg queryKeys: String, listener: Query.Listener) = Unit
        override fun notifyListeners(vararg queryKeys: String) {
            notifications.addAll(queryKeys)
        }
        override fun close() = Unit
    }
}
