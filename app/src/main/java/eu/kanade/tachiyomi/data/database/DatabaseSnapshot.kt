package eu.kanade.tachiyomi.data.database

import android.app.Application
import android.util.Log
import tachiyomi.data.Database
import java.io.File
import java.io.RandomAccessFile

/**
 * 迁移发生之前的数据库快照。
 *
 * 29 个 SQLDelight 迁移里有几次顺带修过数据（date_added 修成真实日期、页数/进度写反的修正），
 * 而迁移写坏之后，阅读进度、已读标记、书架分类没有第二条路可以回来。所以在迁移之前把整份
 * 数据库复制一份，放在 files/backup/，保留最近 3 份。
 *
 * 只负责复制，不提供恢复入口：恢复是用户自己把文件拿出去替换，代码里多一条会动用户数据的
 * 路径就多一个出错的地方。
 */
object DatabaseSnapshot {
    private const val TAG = "DatabaseSnapshot"
    private const val DATABASE_NAME = "tachiyomi.db"
    private const val BACKUP_DIR = "backup"
    private const val BACKUP_PREFIX = "pre-migration-"
    private const val KEEP = 3

    private const val SQLITE_HEADER_MAGIC = "SQLite format 3\u0000"
    private const val SQLITE_HEADER_MAGIC_SIZE = 16
    private const val SQLITE_HEADER_SIZE = 100
    private const val USER_VERSION_OFFSET = 60L

    /**
     * 数据库版本低于当前 schema 时复制一份快照。
     *
     * 每个新版本第一次启动才复制，之后启动读到的是当前版本，直接返回。失败不抛出：
     * 备份失败不能挡住启动，迁移本身才是这次启动要做的事。
     */
    fun beforeMigration(app: Application, versionCode: Int) {
        try {
            snapshot(app, versionCode)
        } catch (e: Exception) {
            Log.w(TAG, "迁移前快照失败，继续启动", e)
        }
    }

    private fun snapshot(app: Application, versionCode: Int) {
        val database = app.getDatabasePath(DATABASE_NAME)
        if (!database.exists()) return

        val userVersion = readUserVersion(database) ?: return
        if (userVersion >= Database.Schema.version) return

        val directory = File(app.filesDir, BACKUP_DIR)
        val target = File(directory, "$BACKUP_PREFIX$versionCode.db")
        // 同一个版本重复启动（比如上次迁移失败）时保留第一份，那份才是迁移前的状态。
        if (target.exists()) return

        directory.mkdirs()
        database.copyTo(target, overwrite = false)
        prune(directory)
    }

    /**
     * 直接读文件头 offset 60 的 user_version，不打开数据库。
     *
     * 走 SQLite API 会在启动路径上拿锁，还可能触发 WAL 恢复；读文件头不会。WAL 模式下这个值
     * 只会比实际旧、不会更新，所以读到旧值时最多多复制一份，不会漏掉该有的备份。
     */
    private fun readUserVersion(database: File): Long? {
        RandomAccessFile(database, "r").use { file ->
            if (file.length() < SQLITE_HEADER_SIZE) return null
            val magic = ByteArray(SQLITE_HEADER_MAGIC_SIZE)
            file.readFully(magic)
            if (String(magic, Charsets.US_ASCII) != SQLITE_HEADER_MAGIC) return null
            file.seek(USER_VERSION_OFFSET)
            return file.readInt().toLong() and 0xFFFFFFFFL
        }
    }

    private fun prune(directory: File) {
        val backups = directory.listFiles { file ->
            file.name.startsWith(BACKUP_PREFIX) && file.name.endsWith(".db")
        } ?: return

        backups.sortedByDescending { it.versionCodeFromName() }
            .drop(KEEP)
            .forEach { it.delete() }
    }

    private fun File.versionCodeFromName(): Int =
        name.removePrefix(BACKUP_PREFIX).removeSuffix(".db").toIntOrNull() ?: 0
}
