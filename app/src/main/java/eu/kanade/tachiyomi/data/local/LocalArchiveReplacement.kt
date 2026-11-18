package eu.kanade.tachiyomi.data.local

import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** An approval is for one specific source and version of one existing archive. */
@Serializable
data class LocalArchiveReplacement(
    val targetUrl: String,
    val sourceUri: String,
    val fileName: String,
    val targetPath: String,
    val targetDigest: String,
)

internal fun archiveReplacementDigest(path: Path): String? = runCatching {
    val before = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    if (!before.isRegularFile) return null
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    val after = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    if (before.size() != after.size() || before.lastModifiedTime() != after.lastModifiedTime() ||
        before.fileKey() != after.fileKey()
    ) {
        return null
    }
    "%s:%s:%s:%s".format(
        after.fileKey(),
        after.size(),
        after.lastModifiedTime(),
        digest.digest().joinToString("") { "%02x".format(it) },
    )
}.getOrNull()

/** No delete/rename fallback: an interruption must leave the target path readable. */
internal fun commitArchiveReplacement(
    approval: LocalArchiveReplacement,
    staged: Path,
    move: (Path, Path) -> Unit = { from, to ->
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    },
): Boolean {
    val target = File(approval.targetPath).toPath()
    if (staged.parent != target.parent || archiveReplacementDigest(target) != approval.targetDigest) return false
    move(staged, target)
    return true
}
