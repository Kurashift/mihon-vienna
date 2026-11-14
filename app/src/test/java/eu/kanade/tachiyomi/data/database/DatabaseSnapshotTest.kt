package eu.kanade.tachiyomi.data.database

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ByteBuffer

class DatabaseSnapshotTest {

    @TempDir
    lateinit var directory: File

    private val database: File get() = File(directory, "tachiyomi.db")
    private val backup: File get() = File(directory, "backup")

    /** 写一个只有文件头的假数据库：magic 对、offset 60 放 user_version。 */
    private fun writeDatabase(userVersion: Int) {
        val header = ByteArray(100)
        "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII).copyInto(header)
        ByteBuffer.wrap(header).putInt(60, userVersion)
        database.writeBytes(header)
    }

    @Test
    fun `no database writes nothing`() {
        assertNull(DatabaseSnapshot.snapshot(database, backup, 230, 29))
        assertFalse(backup.exists())
    }

    @Test
    fun `a database already at the current schema is not copied`() {
        writeDatabase(29)
        assertNull(DatabaseSnapshot.snapshot(database, backup, 230, 29))
        assertFalse(backup.exists())
    }

    @Test
    fun `a database older than the schema is copied`() {
        writeDatabase(28)
        val target = DatabaseSnapshot.snapshot(database, backup, 230, 29)
        assertEquals(File(backup, "pre-migration-230.db").path, target?.path)
        assertTrue(target!!.readBytes().contentEquals(database.readBytes()))
    }

    @Test
    fun `a file that is not a sqlite database is left alone`() {
        database.writeBytes(ByteArray(200))
        assertNull(DatabaseSnapshot.snapshot(database, backup, 230, 29))
    }

    @Test
    fun `the first snapshot for a version is kept`() {
        writeDatabase(28)
        DatabaseSnapshot.snapshot(database, backup, 230, 29)
        val first = File(backup, "pre-migration-230.db").readBytes()

        // 迁移失败后重启：这次读到的版本更低，但那一份已经不是迁移前的状态了。
        writeDatabase(27)
        assertNull(DatabaseSnapshot.snapshot(database, backup, 230, 29))
        assertTrue(File(backup, "pre-migration-230.db").readBytes().contentEquals(first))
    }

    @Test
    fun `only the newest three snapshots are kept`() {
        writeDatabase(28)
        listOf(230, 231, 232, 233).forEach {
            DatabaseSnapshot.snapshot(database, backup, it, 29)
        }

        assertEquals(
            listOf("pre-migration-231.db", "pre-migration-232.db", "pre-migration-233.db"),
            backup.list()!!.sorted(),
        )
    }
}
