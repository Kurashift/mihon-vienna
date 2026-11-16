package eu.kanade.tachiyomi.extension.util

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal fun replacePrivateExtensionFile(
    source: File,
    target: File,
    beforeCommit: () -> Unit = {},
    copy: (File, File) -> Unit = { input, output ->
        input.inputStream().use { reader ->
            FileOutputStream(output).use { writer ->
                check(output.setReadOnly()) { "Failed to make extension read-only" }
                reader.copyTo(writer)
                writer.fd.sync()
            }
        }
    },
    replace: (File, File) -> Unit = { staged, destination ->
        Files.move(
            staged.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    },
) {
    check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "Cannot create extension directory" }
    val staged = File.createTempFile(".extension-", ".tmp", target.parentFile)
    try {
        copy(source, staged)
        beforeCommit()
        replace(staged, target)
    } finally {
        staged.delete()
    }
}
