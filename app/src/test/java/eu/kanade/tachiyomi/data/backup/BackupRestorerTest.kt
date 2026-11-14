package eu.kanade.tachiyomi.data.backup

import android.content.Context
import android.net.Uri
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.restore.BackupRestorer
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.ExtensionStoreRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MarksRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.PreferenceRestorer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.data.Database
import java.io.IOException

class BackupRestorerTest {
    private val context = mockk<Context>()
    private val notifier = mockk<BackupNotifier>(relaxed = true)
    private val decoder = mockk<BackupDecoder>()
    private val categoriesRestorer = mockk<CategoriesRestorer>()
    private val preferenceRestorer = mockk<PreferenceRestorer>(relaxed = true)
    private val mangaRestorer = mockk<MangaRestorer>()
    private val marksRestorer = mockk<MarksRestorer>(relaxed = true)
    private val uri = mockk<Uri>()
    private val backup = backupFixture()
    private val restorer = BackupRestorer(
        context = context,
        notifier = notifier,
        isSync = false,
        database = mockk<Database>(),
        categoriesRestorer = categoriesRestorer,
        preferenceRestorer = preferenceRestorer,
        extensionStoreRestorer = mockk<ExtensionStoreRestorer>(),
        mangaRestorer = mangaRestorer,
        decoder = decoder,
        marksRestorer = marksRestorer,
    )
    private val options = RestoreOptions(extensionStores = false, sourceSettings = false)

    @BeforeEach
    fun setUp() {
        mockkStatic("tachiyomi.core.common.i18n.LocalizeKt")
        every { context.stringResource(any<StringResource>()) } returns "Label"
        every { decoder.decodeMetadata(uri, any(), any(), any()) } returns BackupDecoder.Metadata(
            backup.backupManga.size,
            backup.copy(backupManga = emptyList()),
        )
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic("tachiyomi.core.common.i18n.LocalizeKt")
    }

    @Test
    fun `categories finish before manga or settings and marks follow every manga`() = runTest {
        val events = mutableListOf<String>()
        coEvery { categoriesRestorer(any()) } coAnswers {
            events.add("categories start")
            delay(10)
            events.add("categories end")
        }
        coEvery { preferenceRestorer.restoreApp(any(), any()) } coAnswers {
            assertTrue("categories end" in events)
            events.add("settings")
        }
        every { decoder.decodeEntries(uri) } returns flow {
            backup.backupManga.forEach {
                events.add("decode " + it.url)
                emit(it)
            }
        }
        coEvery { mangaRestorer.restore(any(), backup.backupCategories) } coAnswers {
            assertTrue("categories end" in events)
            delay(1)
            events.add("restored " + firstArg<BackupManga>().url)
        }
        coEvery { marksRestorer(backup.backupMarks) } coAnswers {
            assertEquals(2, events.count { it.startsWith("restored ") })
            events.add("marks")
        }

        restorer.restoreFromFile(uri, options)

        assertEquals(listOf("categories start", "categories end"), events.take(2))
        assertEquals("marks", events.last())
        assertTrue(events.indexOf("restored " + backup.backupManga[0].url) < events.indexOf("decode /remote"))
        coVerify(exactly = 1) { marksRestorer(backup.backupMarks) }
        coVerify(exactly = 1) { preferenceRestorer.restoreApp(backup.backupPreferences, backup.backupCategories) }
    }

    @Test
    fun `preflight failure starts no restore task`() = runTest {
        val expected = IOException("truncated gzip trailer")
        every { decoder.decodeMetadata(uri, any(), any(), any()) } throws expected
        val actual = try {
            restorer.restoreFromFile(uri, options)
            error("Expected preflight failure")
        } catch (error: IOException) {
            error
        }
        assertSame(expected, actual)
        coVerify(exactly = 0) { categoriesRestorer(any()) }
        coVerify(exactly = 0) { preferenceRestorer.restoreApp(any(), any()) }
        coVerify(exactly = 0) { mangaRestorer.restore(any(), any()) }
        coVerify(exactly = 0) { marksRestorer(any()) }
        verify(exactly = 0) { decoder.decodeEntries(any()) }
    }

    @Test
    fun `cancellation stops further decoding and never restores marks`() = runTest {
        val started = CompletableDeferred<Unit>()
        var decodedSecond = false
        every { decoder.decodeEntries(uri) } returns flow {
            emit(backup.backupManga[0])
            decodedSecond = true
            emit(backup.backupManga[1])
        }
        coEvery { mangaRestorer.restore(any(), any()) } coAnswers {
            started.complete(Unit)
            awaitCancellation()
        }
        val job = launch {
            restorer.restoreFromFile(uri, options.copy(categories = false, appSettings = false))
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFalse(decodedSecond)
        coVerify(exactly = 0) { marksRestorer(any()) }
    }

    @Test
    fun `entry failure is reported and later manga finish before marks`() = runTest {
        var completed = false
        every { decoder.decodeEntries(uri) } returns flow {
            backup.backupManga.forEach { emit(it) }
        }
        coEvery { mangaRestorer.restore(backup.backupManga[0], any()) } throws IOException("entry failed")
        coEvery { mangaRestorer.restore(backup.backupManga[1], any()) } coAnswers { completed = true }
        coEvery { marksRestorer(any()) } coAnswers { assertTrue(completed) }

        restorer.restoreFromFile(uri, options.copy(categories = false, appSettings = false))

        coVerify(exactly = 1) { marksRestorer(backup.backupMarks) }
        verify(exactly = 1) { notifier.showRestoreProgress("Remote", 2, 2, false) }
    }

    @Test
    fun `settings only restore leaves entries and marks untouched`() = runTest {
        restorer.restoreFromFile(uri, options.copy(libraryEntries = false, categories = false))
        verify(exactly = 0) { decoder.decodeEntries(any()) }
        coVerify(exactly = 0) { marksRestorer(any()) }
        coVerify(exactly = 1) { preferenceRestorer.restoreApp(backup.backupPreferences, null) }
    }
}
