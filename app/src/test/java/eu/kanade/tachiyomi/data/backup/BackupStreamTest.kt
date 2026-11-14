package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupMarks
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.BackupTracking
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import okio.Buffer
import okio.ForwardingSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tachiyomi.source.local.LocalSource
import java.io.IOException

@OptIn(ExperimentalSerializationApi::class)
class BackupStreamTest {
    private val parser = ProtoBuf

    @Test
    fun `writer is byte compatible with the old whole backup encoder including all Vienna fields`() {
        val backup = backupFixture()
        val sink = Buffer()
        val writer = BackupWriter(sink, parser)

        backup.backupManga.forEach(writer::writeEntry)
        writer.writeMetadata(backup.copy(backupManga = emptyList()))
        val bytes = sink.readByteArray()

        assertFalse(writer.isEmpty)
        assertArrayEquals(encodeBackup(backup), bytes)
        assertArrayEquals(encodeBackup(backup), encodeBackup(parser.decodeFromByteArray(Backup.serializer(), bytes)))
    }

    @Test
    fun `reader preserves every existing Vienna field from the old encoding`() {
        val backup = backupFixture()
        BackupReader(Buffer().write(encodeBackup(backup)), parser).use { reader ->
            backup.backupManga.forEach { expected ->
                assertTrue(reader.nextEntry())
                val actual = reader.readEntry()
                assertArrayEquals(encodeManga(expected), encodeManga(actual))
            }
            assertFalse(reader.nextEntry())
            val metadata = reader.readMetadata()
            assertTrue(metadata.backupManga.isEmpty())
            assertArrayEquals(encodeBackup(backup.copy(backupManga = emptyList())), encodeBackup(metadata))
            assertEquals(listOf(0, 1), metadata.backupMarks.map { it.listType })
        }
    }

    @Test
    fun `new stream is accepted by a frozen older reader ignoring Vienna additions`() {
        val backup = backupFixture()
        val buffer = Buffer()
        val writer = BackupWriter(buffer, parser)
        backup.backupManga.forEach(writer::writeEntry)
        writer.writeMetadata(backup.copy(backupManga = emptyList()))
        val legacy = parser.decodeFromByteArray(LegacyBackup.serializer(), buffer.readByteArray())
        assertEquals(backup.backupManga.map { it.url }, legacy.manga.map { it.url })
        assertEquals(22L, legacy.manga[0].chapters.single().lastPageRead)
        assertEquals(2, legacy.manga[0].tracking.single().syncId)
    }

    @Test
    fun `local nonfavorite chapters retain custom cover and legacy progress repair`() {
        BackupReader(Buffer().write(encodeBackup(backupFixture())), parser).use { reader ->
            assertTrue(reader.nextEntry())
            val manga = reader.readEntry()
            assertEquals(LocalSource.ID, manga.source)
            assertFalse(manga.favorite)
            val chapter = manga.chapters.single()
            assertEquals(0L, chapter.chapterId)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), chapter.customCover)
            val restored = chapter.toChapterImpl()
            assertEquals(22L, restored.totalPages)
            assertEquals(2L, restored.lastPageRead)
            assertEquals(17L, restored.customOrder)
            assertEquals("Translated chapter", restored.translatedName)
            assertEquals(123456L, restored.markedReadAt)
        }
    }

    @Test
    fun `pre Vienna wire retains viewer tracking and chapter defaults`() {
        val old = LegacyBackup(
            listOf(
                LegacyManga(
                    source = 7,
                    url = "/old",
                    viewer = 5,
                    chapters = listOf(LegacyChapter("/old/1", "Old chapter", lastPageRead = 3)),
                    tracking = listOf(LegacyTracking(syncId = 2, libraryId = 9, mediaId = 123)),
                ),
            ),
        )
        val bytes = parser.encodeToByteArray(LegacyBackup.serializer(), old)
        BackupReader(Buffer().write(bytes), parser).use { reader ->
            assertTrue(reader.nextEntry())
            val manga = reader.readEntry()
            assertEquals(5L, manga.getMangaImpl().viewerFlags)
            assertEquals(123L, manga.tracking.single().getTrackImpl().remoteId)
            val chapter = manga.chapters.single()
            assertEquals(3L, chapter.toChapterImpl().lastPageRead)
            assertEquals(0L, chapter.markedReadAt)
            assertNull(chapter.customCover)
            assertNull(chapter.translatedName)
            assertTrue(reader.readMetadata().backupMarks.isEmpty())
        }
    }

    @Test
    fun `metadata can be interleaved with entries and unknown wire fields`() {
        val backup = backupFixture()
        val source = Buffer()
            .write(encodeBackup(backup.copy(backupManga = emptyList(), backupMarks = emptyList())))
            .writeByte(3 shl 3).writeVarint(150)
            .writeByte(4 shl 3 or 1).writeLongLe(42)
            .writeByte(5 shl 3 or 2).writeVarint(4).writeUtf8("skip")
            .writeByte(6 shl 3 or 5).writeIntLe(42)
            .writeByte(7 shl 3 or 3).writeByte(8 shl 3).writeByte(1).writeByte(7 shl 3 or 4)
            .write(encodeBackup(Backup(backup.backupManga)))
            .write(encodeBackup(Backup(emptyList(), backupMarks = backup.backupMarks)))

        BackupReader(source, parser).use { reader ->
            backup.backupManga.forEach { expected ->
                assertTrue(reader.nextEntry())
                assertArrayEquals(encodeManga(expected), encodeManga(reader.readEntry()))
            }
            assertArrayEquals(
                encodeBackup(backup.copy(backupManga = emptyList())),
                encodeBackup(reader.readMetadata()),
            )
        }
    }

    @Test
    fun `validator scans IDs without decoding chapters covers or unrelated metadata`() {
        val source = LargeEntrySource(16L * 1024 * 1024)
        BackupReader(source.buffer(), parser, metadataFields = setOf(101)).use { reader ->
            assertTrue(reader.nextEntry())
            val ids = mutableSetOf<Int>()
            reader.readTrackingIds { ids.add(it) }
            assertFalse(reader.nextEntry())
            val metadata = reader.readMetadata()
            assertEquals(setOf(2), ids)
            assertEquals(listOf("Source"), metadata.backupSources.map { it.name })
            assertTrue(metadata.backupPreferences.isEmpty())
            assertTrue(metadata.backupMarks.isEmpty())
            assertTrue(metadata.backupManga.isEmpty())
        }
        assertTrue(source.closed)
        assertTrue(source.largestRead <= 8192)
    }

    @Test
    fun `unknown manga fields are skipped by both full decoder and minimal validator`() {
        val manga = backupFixture().backupManga[0]
        val entry = Buffer().write(encodeManga(manga))
            .writeVarint(200L shl 3).writeVarint(150)
            .writeVarint(201L shl 3 or 2).writeVarint(3).writeUtf8("new")
            .readByteArray()
        val input = Buffer().writeByte(10).writeVarint(entry.size.toLong()).write(entry).readByteArray()
        BackupReader(Buffer().write(input), parser).use { reader ->
            assertTrue(reader.nextEntry())
            assertArrayEquals(encodeManga(manga), encodeManga(reader.readEntry()))
        }
        BackupReader(Buffer().write(input), parser).use { reader ->
            assertTrue(reader.nextEntry())
            val ids = mutableSetOf<Int>()
            reader.readTrackingIds { ids.add(it) }
            assertEquals(setOf(2), ids)
        }
    }

    @Test
    fun `metadata scan counts thousands of entries without retaining them`() {
        val buffer = Buffer()
        val writer = BackupWriter(buffer, parser)
        repeat(4001) { writer.writeEntry(BackupManga(source = 1, url = "/$it")) }
        BackupReader(buffer, parser).use { reader ->
            var count = 0
            while (reader.nextEntry()) {
                reader.readTrackingIds {}
                count++
            }
            assertEquals(4001, count)
            assertTrue(reader.readMetadata().backupManga.isEmpty())
        }
    }

    @Test
    fun `truncation and malformed top level wire fail both reading and preflight`() {
        val backup = backupFixture()
        val bytes = encodeBackup(backup)
        val inputs = listOf(
            bytes.copyOf(bytes.size - 1),
            bytes.copyOf(encodeBackup(Backup(backup.backupManga)).size - 1),
            byteArrayOf(0x0a),
            byteArrayOf(0x0a, 0x10, 0x01),
            byteArrayOf(0x02, 0x00),
            byteArrayOf(0x0f),
            byteArrayOf(0x08, 0x01),
            ByteArray(11) { 0xff.toByte() },
            byteArrayOf(0x18) + ByteArray(9) { 0xff.toByte() } + byteArrayOf(0x02),
            Buffer().writeByte(0x0a).writeVarint(Int.MAX_VALUE.toLong() + 1).readByteArray(),
            byteArrayOf(0x3b, 0x44),
        )
        inputs.forEach { input ->
            assertThrows<IOException> {
                BackupReader(Buffer().write(input), parser).use { reader ->
                    while (reader.nextEntry()) reader.readEntry()
                    reader.readMetadata()
                }
            }
            assertThrows<IOException> {
                BackupReader(Buffer().write(input), parser).use { reader ->
                    while (reader.nextEntry()) reader.readTrackingIds {}
                    reader.readMetadata()
                }
            }
        }
    }

    @Test
    fun `preflight enforces entry and nested tracking boundaries`() {
        val identity = Buffer().writeByte(8).writeByte(1).writeByte(18).writeByte(0).readByteArray()
        val malformed = listOf(
            byteArrayOf(),
            byteArrayOf(8, 1),
            identity + byteArrayOf(0x92.toByte(), 1, 10, 8, 1),
            identity + byteArrayOf(0x92.toByte(), 1, 2, 8, 1),
            identity + byteArrayOf(0x92.toByte(), 1, 2, 16, 1),
        )
        malformed.forEach { entry ->
            val input = Buffer().writeByte(10).writeVarint(entry.size.toLong()).write(entry)
            BackupReader(input, parser).use { reader ->
                assertTrue(reader.nextEntry())
                assertThrows<IOException> { reader.readTrackingIds {} }
            }
        }
    }

    @Test
    fun `reader closes its source on decoding error and callback cancellation`() {
        listOf(false, true).forEach { cancel ->
            var closed = false
            val source = object : ForwardingSource(Buffer().write(encodeBackup(backupFixture()))) {
                override fun close() {
                    closed = true
                    super.close()
                }
            }.buffer()
            val cancellation = CancellationException("cancel")
            if (cancel) {
                val error = assertThrows<CancellationException> {
                    BackupReader(source, parser).use { reader ->
                        reader.nextEntry()
                        reader.readTrackingIds { throw cancellation }
                    }
                }
                assertSame(cancellation, error)
            } else {
                assertThrows<IllegalStateException> {
                    BackupReader(source, parser).use { reader ->
                        reader.nextEntry()
                        reader.nextEntry()
                    }
                }
            }
            assertTrue(closed)
        }
    }

    @Test
    fun `cancel interrupts long skipped fields and closes the source`() {
        val source = LargeEntrySource(16L * 1024 * 1024)
        val cancellation = CancellationException("cancel")
        val error = assertThrows<CancellationException> {
            BackupReader(source.buffer(), parser, checkCancelled = {
                if (source.payloadRead >= 32768) throw cancellation
            }).use { reader ->
                reader.nextEntry()
                reader.readTrackingIds {}
            }
        }
        assertSame(cancellation, error)
        assertTrue(source.closed)
        assertTrue(source.payloadRead < 16L * 1024 * 1024)
    }

    @Test
    fun `writer rejects accidental library metadata and preserves cancellation`() {
        val sink = Buffer()
        val cancellation = CancellationException("cancel")
        val writer = BackupWriter(sink, parser) { throw cancellation }
        val actual = assertThrows<CancellationException> { writer.writeEntry(backupFixture().backupManga[0]) }
        assertSame(cancellation, actual)
        assertEquals(0L, sink.size)
        assertThrows<IllegalArgumentException> {
            BackupWriter(sink, parser).writeMetadata(backupFixture())
        }
    }

    @Test
    fun `cancel interrupts writing a large individual entry`() {
        val sink = Buffer()
        val cancellation = CancellationException("cancel")
        val writer = BackupWriter(sink, parser) {
            if (sink.size >= 32768) throw cancellation
        }
        val manga = BackupManga(source = 1, url = "/large", description = "x".repeat(1024 * 1024))
        assertSame(cancellation, assertThrows<CancellationException> { writer.writeEntry(manga) })
        assertTrue(sink.size in 32768 until 1024 * 1024L)
    }

    @Test
    fun `settings only and empty metadata retain old encoding`() {
        val sink = Buffer()
        val writer = BackupWriter(sink, parser)
        writer.writeMetadata(Backup(emptyList()))
        assertTrue(writer.isEmpty)
        assertEquals(0L, sink.size)
        val settings = Backup(emptyList(), backupPreferences = backupFixture().backupPreferences)
        writer.writeMetadata(settings)
        assertFalse(writer.isEmpty)
        BackupReader(sink, parser).use { reader ->
            assertFalse(reader.nextEntry())
            assertArrayEquals(encodeBackup(settings), encodeBackup(reader.readMetadata()))
        }
    }

    private class LargeEntrySource(private val payloadLength: Long) : Source {
        private val prefix: Buffer
        private val suffix: Buffer
        private val zeros = ByteArray(8192)
        var payloadRead = 0L
            private set
        var largestRead = 0L
            private set
        var closed = false
            private set

        init {
            val identity = Buffer().writeByte(8).writeByte(1).writeByte(18).writeByte(0)
            val chapterHeader = Buffer().writeVarint(16L shl 3 or 2).writeVarint(payloadLength)
            val tracking = Buffer().writeVarint(18L shl 3 or 2).writeVarint(4)
                .writeByte(8).writeByte(2).writeByte(16).writeByte(3)
            prefix = Buffer().writeByte(10)
                .writeVarint(identity.size + chapterHeader.size + payloadLength + tracking.size)
                .writeAllFrom(identity).writeAllFrom(chapterHeader)
            suffix = Buffer().writeAllFrom(tracking)
                .writeVarint(104L shl 3 or 2).writeVarint(3).writeUtf8("bad")
                .write(encodeBackup(Backup(emptyList(), backupSources = listOf(BackupSource("Source", 1)))))
        }

        override fun read(sink: Buffer, byteCount: Long): Long {
            largestRead = maxOf(largestRead, byteCount)
            if (byteCount == 0L) return 0
            if (prefix.size > 0) return prefix.read(sink, byteCount)
            if (payloadRead < payloadLength) {
                val count = minOf(byteCount, payloadLength - payloadRead, zeros.size.toLong()).toInt()
                sink.write(zeros, 0, count)
                payloadRead += count
                return count.toLong()
            }
            return suffix.read(sink, byteCount)
        }

        override fun timeout(): Timeout = Timeout.NONE
        override fun close() {
            closed = true
        }
    }

    @Serializable
    private class LegacyBackup(@ProtoNumber(1) val manga: List<LegacyManga>)

    @Serializable
    private class LegacyManga(
        @ProtoNumber(1) val source: Long,
        @ProtoNumber(2) val url: String,
        @ProtoNumber(14) val viewer: Int = 0,
        @ProtoNumber(16) val chapters: List<LegacyChapter> = emptyList(),
        @ProtoNumber(18) val tracking: List<LegacyTracking> = emptyList(),
    )

    @Serializable
    private class LegacyChapter(
        @ProtoNumber(1) val url: String,
        @ProtoNumber(2) val name: String,
        @ProtoNumber(6) val lastPageRead: Long = 0,
    )

    @Serializable
    private class LegacyTracking(
        @ProtoNumber(1) val syncId: Int,
        @ProtoNumber(2) val libraryId: Long,
        @ProtoNumber(3) val mediaId: Int = 0,
    )
}

@OptIn(ExperimentalSerializationApi::class)
internal fun encodeBackup(backup: Backup): ByteArray = ProtoBuf.encodeToByteArray(Backup.serializer(), backup)

@OptIn(ExperimentalSerializationApi::class)
private fun encodeManga(manga: BackupManga): ByteArray = ProtoBuf.encodeToByteArray(BackupManga.serializer(), manga)

private fun Buffer.writeVarint(value: Long): Buffer {
    var remaining = value
    while (remaining and 0x7fL.inv() != 0L) {
        writeByte((remaining and 0x7f).toInt() or 0x80)
        remaining = remaining ushr 7
    }
    return writeByte(remaining.toInt())
}

private fun Buffer.writeAllFrom(other: Buffer): Buffer = apply { writeAll(other) }

internal fun backupFixture() = Backup(
    backupManga = listOf(
        BackupManga(
            source = LocalSource.ID,
            url = "Local author/Story",
            title = "Story",
            artist = "Artist",
            author = "Author",
            description = "Description",
            genre = listOf("Action", "Drama"),
            status = 2,
            thumbnailUrl = "cover.jpg",
            dateAdded = 123,
            viewer = 5,
            chapters = listOf(
                BackupChapter(
                    url = "Local author/Story/chapter.cbz",
                    name = "Chapter",
                    scanlator = "Group",
                    read = false,
                    bookmark = true,
                    lastPageRead = 22,
                    dateFetch = 10,
                    dateUpload = 11,
                    chapterNumber = 1.5f,
                    sourceOrder = 3,
                    lastModifiedAt = 12,
                    version = 13,
                    memo = """{"mihon.pageCount":22}""".toByteArray(),
                    totalPages = 2,
                    customOrder = 17,
                    translatedName = "Translated chapter",
                    customCover = byteArrayOf(1, 2, 3, 4),
                    markedReadAt = 123456,
                ).apply { chapterId = 987 },
            ),
            categories = listOf(0, 1),
            tracking = listOf(BackupTracking(syncId = 2, libraryId = 3, mediaId = 7, title = "Story")),
            favorite = false,
            chapterFlags = 34,
            viewer_flags = 65,
            history = listOf(BackupHistory("Local author/Story/chapter.cbz", 1234, 60)),
            updateStrategy = UpdateStrategy.ONLY_FETCH_ONCE,
            lastModifiedAt = 101,
            favoriteModifiedAt = 102,
            excludedScanlators = listOf("Excluded"),
            version = 44,
            notes = "Notes",
            initialized = true,
            memo = """{"translation":"Translated story"}""".toByteArray(),
        ),
        BackupManga(source = 2, url = "/remote", title = "Remote"),
    ),
    backupCategories = listOf(BackupCategory("Reading", order = 1, id = 42, flags = 5)),
    backupSources = listOf(BackupSource("Local", LocalSource.ID), BackupSource("Remote", 2)),
    backupPreferences = listOf(BackupPreference("flag", BooleanPreferenceValue(true))),
    backupSourcePreferences = listOf(
        BackupSourcePreferences("source_2", listOf(BackupPreference("lang", StringPreferenceValue("en")))),
    ),
    backupExtensionStores = listOf(
        BackupExtensionStore("index", "Store", "Badge", "Key", "Website", "Discord", false, "extensions"),
    ),
    backupMarks = listOf(
        BackupMarks(LocalSource.ID, "Local author/Story", "Local author/Story/chapter.cbz", "Story", "Chapter", 80, 0),
        BackupMarks(LocalSource.ID, "Local author/Story", "Local author/Story/chapter.cbz", "Story", "Chapter", 90, 1),
    ),
)
