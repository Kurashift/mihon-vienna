package tachiyomi.source.local.image

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal fun writeChapterCover(
    target: File,
    write: (OutputStream) -> Unit,
    isValid: (File) -> Boolean,
    beforeCommit: () -> Unit = {},
    replace: (File, File) -> Unit = { staged, destination ->
        Files.move(
            staged.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    },
): File {
    val directory = target.parentFile ?: throw IOException("Cover directory is missing")
    check(directory.isDirectory || directory.mkdirs()) { "Cannot create cover directory" }
    val staged = File.createTempFile(".cover-", ".tmp", directory)
    try {
        FileOutputStream(staged).use { output ->
            val buffered = output.buffered()
            write(buffered)
            buffered.flush()
            output.fd.sync()
        }
        check(isValid(staged)) { "Cover image is invalid" }
        beforeCommit()
        replace(staged, target)
        return target
    } finally {
        staged.delete()
    }
}
