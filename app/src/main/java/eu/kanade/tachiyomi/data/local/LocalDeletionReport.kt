package eu.kanade.tachiyomi.data.local

import android.content.Context
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What a delete run was aimed at. Only used to phrase the result. */
enum class LocalDeletionUnit { CHAPTER, COLLECTION }

/**
 * How one top-level entry ended.
 *
 * [PARTIAL] is not the same as [FAILED]: a directory that lost some of its files before the run
 * stopped or hit an error is half gone, and calling it failed would tell the reader everything is
 * still on disk.
 */
enum class LocalDeletionOutcome { DELETED, PARTIAL, FAILED }

/**
 * One top-level entry (a chapter file, a chapter folder, or a work's directory).
 *
 * Files inside an unpacked chapter are not listed one by one: a 142-page folder would turn into
 * 142 rows of noise. [fileCount] and [sizeBytes] carry that detail instead.
 */
data class LocalDeletionEntry(
    val mangaTitle: String,
    val name: String,
    val relativePath: String,
    val fileCount: Int,
    val sizeBytes: Long,
    val outcome: LocalDeletionOutcome,
)

/**
 * Receipt of one delete run.
 *
 * Lives in memory only: the dialog that shows it is the only consumer, and it is cleared as soon
 * as the reader closes that dialog or exports the list.
 */
data class LocalDeletionReport(
    val id: Long,
    val unit: LocalDeletionUnit,
    val entries: List<LocalDeletionEntry>,
    val cancelled: Boolean,
    val skippedCount: Int,
    val deletedMangaIds: Set<Long>,
    val finishedAt: Long,
) {
    val deletedCount: Int get() = entries.count { it.outcome == LocalDeletionOutcome.DELETED }

    val failedCount: Int get() = entries.count { it.outcome != LocalDeletionOutcome.DELETED }

    /** One entry, all of it gone, nobody pressed stop: a toast with the name says everything. */
    val isSingleSuccess: Boolean
        get() = !cancelled && failedCount == 0 && entries.size == 1
}

object LocalDeletionReportCsv {
    private const val BOM = '\uFEFF'

    // Fixed Chinese headers, same as the chapter title CSV: the file is meant to be opened in a
    // spreadsheet, not parsed by another build of this app.
    private val header = listOf("作品", "章节", "路径", "大小", "结果", "时间")

    fun encode(report: LocalDeletionReport): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(report.finishedAt))
        val rows = buildList {
            add(header)
            report.entries.forEach { entry ->
                add(
                    listOf(
                        entry.mangaTitle,
                        if (entry.fileCount > 1) "${entry.name} (${entry.fileCount} 个文件)" else entry.name,
                        entry.relativePath,
                        formatSize(entry.sizeBytes),
                        when (entry.outcome) {
                            LocalDeletionOutcome.DELETED -> "已删除"
                            LocalDeletionOutcome.PARTIAL -> "部分删除"
                            LocalDeletionOutcome.FAILED -> "失败"
                        },
                        time,
                    ),
                )
            }
        }
        return buildString {
            append(BOM)
            rows.joinTo(this, separator = "\r\n") { row -> row.joinToString(",", transform = ::escape) }
        }
    }

    fun write(context: Context, uri: Uri, report: LocalDeletionReport): Boolean = runCatching {
        val stream = context.contentResolver.openOutputStream(uri) ?: return@runCatching false
        stream.use { it.write(encode(report).toByteArray(Charsets.UTF_8)) }
        true
    }.getOrDefault(false)

    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return ""
        val units = listOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024
            index++
        }
        return if (index == 0) {
            "${value.toLong()} ${units[index]}"
        } else {
            String.format(Locale.US, "%.1f %s", value, units[index])
        }
    }
}
