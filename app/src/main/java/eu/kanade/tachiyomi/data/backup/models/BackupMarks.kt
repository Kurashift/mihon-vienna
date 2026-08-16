package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * A chapter mark (duplicate or good doujin) stored in a backup.
 * URLs are used instead of ids so the marks can be re-attached after a restore
 * even when the database ids changed.
 */
@Serializable
data class BackupMarks(
    @ProtoNumber(1) val sourceId: Long,
    @ProtoNumber(2) val mangaUrl: String,
    @ProtoNumber(3) val chapterUrl: String,
    @ProtoNumber(4) val mangaTitle: String,
    @ProtoNumber(5) val chapterName: String,
    @ProtoNumber(6) val markedAt: Long,
    // 0 = duplicates, 1 = good doujins
    @ProtoNumber(7) val listType: Int,
)
