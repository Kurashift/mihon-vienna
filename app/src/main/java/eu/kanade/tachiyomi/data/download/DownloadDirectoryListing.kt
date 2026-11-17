package eu.kanade.tachiyomi.data.download

import com.hippo.unifile.UniFile
import java.io.IOException

internal fun readDownloadDirectory(directory: UniFile?, hasKnownEntries: () -> Boolean = { false }): List<UniFile> {
    val files = directory?.listFiles()?.toList() ?: throw IOException("Download directory cannot be listed")
    if (files.isEmpty() && hasKnownEntries()) throw IOException("Download directory listing is temporarily empty")
    return files
}
