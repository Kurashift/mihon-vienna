package eu.kanade.tachiyomi.data.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.BackupTracking
import eu.kanade.tachiyomi.data.backup.restore.BackupRestorer
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.ExtensionStoreRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MarksRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.PreferenceRestorer
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.Source
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.data.Database
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

@OptIn(ExperimentalSerializationApi::class)
class BackupDecoderTest {
    private val context = mockk<Context>()
    private val resolver = mockk<ContentResolver>()
    private val uri = mockk<Uri>()
    private val decoder = BackupDecoder(context, ProtoBuf)
    private val inputs = mutableListOf<CloseTrackingInput>()

    @BeforeEach
    fun setUp() {
        mockkStatic("tachiyomi.core.common.i18n.LocalizeKt")
        every { context.stringResource(any<StringResource>()) } returns "invalid backup"
        every { context.stringResource(MR.strings.invalid_backup_file_json) } returns "JSON backup"
        every { context.contentResolver } returns resolver
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic("tachiyomi.core.common.i18n.LocalizeKt")
    }

    @Test
    fun `plain and gzip backups preflight completely then stream the same entries`() = runTest {
        val backup = backupFixture()
        val bytes = encodeBackup(backup)
        listOf(bytes, gzipBackup(bytes)).forEach { encoded ->
            open(encoded)
            val metadata = decoder.decodeMetadata(uri)
            assertEquals(2, metadata.entryCount)
            assertTrue(metadata.backup.backupManga.isEmpty())
            assertEquals(backup.backupMarks, metadata.backup.backupMarks)
            val entries = decoder.decodeEntries(uri).toList()
            assertArrayEquals(encodeBackup(backup), encodeBackup(metadata.backup.copy(backupManga = entries)))
        }
        assertEquals(4, inputs.size)
        assertTrue(inputs.all { it.closed })
    }

    @Test
    fun `preflight rejects truncated gzip headers bodies trailers and bad checksums`() {
        val compressed = gzipBackup(encodeBackup(backupFixture()))
        val badChecksum = compressed.copyOf().apply { this[size - 8] = (this[size - 8].toInt() xor 1).toByte() }
        listOf(
            compressed.copyOf(1),
            compressed.copyOf(8),
            compressed.copyOf(compressed.size / 2),
            compressed.copyOf(compressed.size - 1),
            badChecksum,
        ).forEach { bytes ->
            open(bytes)
            assertThrows<IOException> { decoder.decodeMetadata(uri) }
            assertTrue(inputs.last().closed)
        }
    }

    @Test
    fun `valid gzip with malformed nested chapter fails restore preflight before any writes`() = runTest {
        val malformedEntry = byteArrayOf(10, 8, 8, 1, 18, 0, 0x82.toByte(), 1, 1, 0)
        val bytes = encodeBackup(backupFixture()) + malformedEntry
        val compressed = gzipBackup(bytes)
        GZIPInputStream(ByteArrayInputStream(compressed)).use { assertArrayEquals(bytes, it.readBytes()) }
        open(compressed)

        val trackers = mutableSetOf<Int>()
        val lightweight = decoder.decodeMetadata(uri, onlySources = true, onTrackingId = { trackers.add(it) })
        assertEquals(3, lightweight.entryCount)
        assertEquals(setOf(2), trackers)
        assertEquals(backupFixture().backupSources, lightweight.backup.backupSources)
        assertThrows<IOException> { decoder.decodeMetadata(uri) }

        val categories = mockk<CategoriesRestorer>()
        val preferences = mockk<PreferenceRestorer>()
        val extensions = mockk<ExtensionStoreRestorer>()
        val manga = mockk<MangaRestorer>()
        val marks = mockk<MarksRestorer>()
        val restorer = BackupRestorer(
            context = context,
            notifier = mockk<BackupNotifier>(relaxed = true),
            isSync = false,
            database = mockk<Database>(),
            categoriesRestorer = categories,
            preferenceRestorer = preferences,
            extensionStoreRestorer = extensions,
            mangaRestorer = manga,
            decoder = decoder,
            marksRestorer = marks,
        )
        val failure = try {
            restorer.restoreFromFile(uri, RestoreOptions())
            null
        } catch (error: IOException) {
            error
        }
        assertTrue(failure is IOException)
        coVerify(exactly = 0) { categories(any()) }
        coVerify(exactly = 0) { preferences.restoreApp(any(), any()) }
        coVerify(exactly = 0) { preferences.restoreSource(any()) }
        coVerify(exactly = 0) { extensions(any()) }
        coVerify(exactly = 0) { manga.restore(any(), any()) }
        coVerify(exactly = 0) { marks(any()) }
        assertEquals(3, inputs.size)
        assertTrue(inputs.all { it.closed })
    }

    @Test
    fun `JSON retains its specific error and short input closes immediately`() {
        listOf("{}", "{\"x\":1}", "{\n}").forEach { json ->
            open(json.toByteArray())
            assertEquals("JSON backup", assertThrows<IOException> { decoder.decodeMetadata(uri) }.message)
            assertTrue(inputs.last().closed)
        }
        listOf(byteArrayOf(), byteArrayOf(10)).forEach { bytes ->
            open(bytes)
            assertEquals("invalid backup", assertThrows<IOException> { decoder.decodeMetadata(uri) }.message)
            assertTrue(inputs.last().closed)
        }
    }

    @Test
    fun `stopping collection closes source without decoding a later invalid entry`() = runTest {
        val first = BackupManga(source = 1, url = "/first")
        open(encodeBackup(Backup(listOf(first))) + byteArrayOf(10, 1, 0))
        val entries = decoder.decodeEntries(uri).take(1).toList()
        assertEquals(listOf("/first"), entries.map { it.url })
        assertTrue(inputs.single().closed)
    }

    @Test
    fun `consumer errors and cancellation retain their identity and close source`() = runTest {
        listOf(IOException("consumer failed"), CancellationException("cancelled")).forEach { expected ->
            open(encodeBackup(backupFixture()))
            val actual = try {
                decoder.decodeEntries(uri).collect { throw expected }
                error("Expected failure")
            } catch (error: Exception) {
                error
            }
            assertSame(expected, actual)
            assertTrue(inputs.last().closed)
        }
    }

    @Test
    fun `preflight callback cancellation is not wrapped as an invalid file`() {
        open(encodeBackup(backupFixture()))
        val expected = CancellationException("cancelled")
        val actual = assertThrows<CancellationException> {
            decoder.decodeMetadata(uri, onTrackingId = { throw expected })
        }
        assertSame(expected, actual)
        assertTrue(inputs.single().closed)
    }

    @Test
    fun `validator retains missing source and tracker behavior with minimal streaming scan`() {
        val sourceManager = mockk<SourceManager>()
        val trackerManager = mockk<TrackerManager>()
        every { sourceManager.get(any()) } returns null
        every { sourceManager.get(3) } returns mockk()
        val numericSource = mockk<Source>()
        every { numericSource.toString() } returns "Missing numeric"
        every { sourceManager.getOrStub(123) } returns numericSource
        every { trackerManager.get(any()) } returns null
        val loggedOut = mockk<BaseTracker>()
        every { loggedOut.isLoggedIn } returns false
        every { loggedOut.name } returns "Tracker"
        every { trackerManager.get(2) } returns loggedOut
        val loggedIn = mockk<BaseTracker>()
        every { loggedIn.isLoggedIn } returns true
        every { trackerManager.get(3) } returns loggedIn
        val backup = Backup(
            backupManga = listOf(
                BackupManga(
                    source = 2,
                    url = "/manga",
                    tracking = listOf(2, 2, 3, 999).map { BackupTracking(syncId = it, libraryId = 1) },
                ),
            ),
            backupSources = listOf(
                BackupSource("Remote", 2),
                BackupSource("123", 123),
                BackupSource("Installed", 3),
                BackupSource("Remote", 4),
            ),
        )
        open(gzipBackup(encodeBackup(backup)))
        val validator = BackupFileValidator(context, sourceManager, trackerManager, decoder)
        val result = validator.validate(uri)
        assertEquals(listOf("Missing numeric", "Remote"), result.missingSources)
        assertEquals(listOf("Tracker"), result.missingTrackers)
        assertTrue(inputs.single().closed)
        val cancellation = CancellationException("cancelled")
        assertSame(cancellation, assertThrows<CancellationException> { validator.validate(uri) { throw cancellation } })
    }

    private fun open(bytes: ByteArray) {
        every { resolver.openInputStream(uri) } answers { CloseTrackingInput(bytes).also { inputs.add(it) } }
    }

    private class CloseTrackingInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }
}

internal fun gzipBackup(bytes: ByteArray): ByteArray {
    val output = ByteArrayOutputStream()
    GZIPOutputStream(output).use { it.write(bytes) }
    return output.toByteArray()
}
