package eu.kanade.tachiyomi.data.download

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal fun writeDownloadIndex(
    target: File,
    bytes: ByteArray,
    beforeCommit: () -> Unit = {},
    replace: (File, File) -> Unit = { staged, destination ->
        Files.move(
            staged.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    },
) {
    val staged = File(target.parentFile, "${target.name}.tmp")
    try {
        FileOutputStream(staged).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        beforeCommit()
        replace(staged, target)
    } finally {
        staged.delete()
    }
}
