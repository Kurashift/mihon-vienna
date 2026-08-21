package eu.kanade.tachiyomi.ui.reader.loader

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalPageOrder
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.source.local.io.LocalSourceFileSystem
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Loader used to load a chapter from a directory given on [file].
 */
internal class DirectoryPageLoader(
    val file: UniFile,
    private val fileSystem: LocalSourceFileSystem = Injekt.get(),
) : PageLoader() {

    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> {
        return fileSystem.getFreshFilesInDirectory(file)
            .filter { !it.isDirectory && ImageUtil.isImage(it.name) { it.openInputStream() } }
            .sortedWith { f1, f2 ->
                f1.name.orEmpty().compareToCaseInsensitiveNaturalPageOrder(f2.name.orEmpty())
            }
            .mapIndexed { i, file ->
                val streamFn = { file.openInputStream() }
                ReaderPage(i).apply {
                    stream = streamFn
                    status = Page.State.Ready
                }
            }
    }
}
