package eu.kanade.tachiyomi.ui.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.local.LocalChapterTransferJob
import kotlinx.coroutines.launch
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class LocalChapterMoveScreen(
    private val sourceMangaId: Long,
    private val chapterIds: List<Long>,
) : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val mangaRepository = remember { Injekt.get<MangaRepository>() }
        val chapterRepository = remember { Injekt.get<ChapterRepository>() }
        var mangas by remember { mutableStateOf<List<Manga>>(emptyList()) }
        var chapters by remember { mutableStateOf<List<Chapter>>(emptyList()) }
        var targetId by remember { mutableLongStateOf(-1L) }
        var moving by remember { mutableStateOf(false) }
        val transferStatus by remember(context) { LocalChapterTransferJob.statusFlow(context) }
            .collectAsStateWithLifecycle(initialValue = null)
        LaunchedEffect(Unit) {
            val localIds = mangaRepository.getLocalMangaIds()
            val nonEmptyIds = mangaRepository.getMangaProgressBySource(LocalSource.ID)
                .mapTo(hashSetOf()) { it.mangaId }
            mangas = localIds.filter { it in nonEmptyIds }.mapNotNull {
                runCatching { mangaRepository.getMangaById(it) }.getOrNull()
            }.filter { it.id != sourceMangaId }
                .sortedBy { it.title.lowercase() }
            chapters = chapterIds.mapNotNull { chapterRepository.getChapterById(it) }
        }
        fun enqueueMove() {
            moving = true
            if (LocalChapterTransferJob.startMove(
                    context = context,
                    chapterIds = chapterIds,
                    targetMangaId = targetId,
                )
            ) {
                navigator.pop()
            }
            moving = false
        }
        Scaffold(
            topBar = { AppBar(title = stringResource(MR.strings.action_move_to), navigateUp = navigator::pop) },
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("已选择 ${chapters.size} 个篇目")
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(mangas, key = { it.id }) { manga ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = targetId == manga.id, onClick = { targetId = manga.id })
                            Text(manga.title)
                        }
                    }
                }
                transferStatus?.takeUnless { it.state.isFinished }?.let { status ->
                    LinearProgressIndicator(
                        progress = {
                            if (status.totalBytes > 0) {
                                status.copiedBytes.toFloat() / status.totalBytes
                            } else if (status.total > 0) {
                                status.completed.toFloat() / status.total
                            } else {
                                0f
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${status.completed}/${status.total}：${status.currentName}")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = navigator::pop, enabled = !moving) { Text("取消") }
                    Button(
                        onClick = {
                            scope.launch {
                                enqueueMove()
                            }
                        },
                        enabled = !moving && targetId >= 0 && chapters.isNotEmpty(),
                    ) { Text("开始移动") }
                }
            }
        }
    }
}
