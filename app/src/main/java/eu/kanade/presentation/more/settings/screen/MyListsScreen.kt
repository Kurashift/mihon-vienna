package eu.kanade.presentation.more.settings.screen

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.RemoveDone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.screen.advanced.ChapterFlagListScreen
import eu.kanade.presentation.more.settings.screen.advanced.ChapterFlagListType
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.manga.GoodDoujinStore
import eu.kanade.tachiyomi.data.manga.MangaMarkStore
import eu.kanade.tachiyomi.ui.browse.source.browse.LocalReadReviewScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import tachiyomi.data.Database
import tachiyomi.data.subscribeToOne
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * 个人清单的入口页。三个子清单各自的条目数在这里实时汇总，
 */
class MyListsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val markStore = remember { Injekt.get<MangaMarkStore>() }
        val goodDoujinStore = remember { Injekt.get<GoodDoujinStore>() }
        val database = remember { Injekt.get<Database>() }

        val marks by markStore.marks.collectAsState()
        val goodDoujins by goodDoujinStore.marks.collectAsState()
        // 只取计数，不把整份已读章节列表读进内存：入口页刚打开就要跑，量级可能很大。
        val localReadCount by remember(database) {
            database.chaptersQueries
                .countReadChaptersBySource(LocalSource.ID)
                .subscribeToOne(Dispatchers.IO)
                .map { it.chapter_count.toInt() to it.manga_count.toInt() }
        }.collectAsState(initial = null)

        val markCount = remember(marks) { marks.size to marks.distinctBy { it.mangaId }.size }
        val goodDoujinCount = remember(goodDoujins) {
            goodDoujins.size to goodDoujins.distinctBy { it.mangaId }.size
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.my_lists_title),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            ScrollbarLazyColumn(contentPadding = contentPadding) {
                item {
                    MyListEntryRow(
                        icon = Icons.Outlined.Flag,
                        title = stringResource(MR.strings.marks_list_title),
                        summary = stringResource(MR.strings.pref_marks_list_summary),
                        entryCount = markCount.first,
                        mangaCount = markCount.second,
                        onClick = {
                            navigator.push(ChapterFlagListScreen(ChapterFlagListType.DUPLICATES))
                        },
                    )
                }
                item {
                    MyListEntryRow(
                        icon = Icons.Outlined.FavoriteBorder,
                        title = stringResource(MR.strings.good_doujin_list_title),
                        summary = stringResource(MR.strings.pref_good_doujin_list_summary),
                        entryCount = goodDoujinCount.first,
                        mangaCount = goodDoujinCount.second,
                        onClick = {
                            navigator.push(ChapterFlagListScreen(ChapterFlagListType.GOOD_DOUJINS))
                        },
                    )
                }
                item {
                    MyListEntryRow(
                        icon = Icons.Outlined.RemoveDone,
                        title = stringResource(MR.strings.local_read_review_action),
                        summary = stringResource(MR.strings.local_read_review_summary),
                        entryCount = localReadCount?.first,
                        mangaCount = localReadCount?.second,
                        onClick = { navigator.push(LocalReadReviewScreen()) },
                    )
                }
            }
        }
    }
}

/**
 * 与「更多」页同一套行样式（[TextPreferenceWidget]），只是额外带上了条目数。
 *
 * @param entryCount 为空表示数据还没加载出来，此时显示 [summary] 而不是 0，避免闪一下「0 个篇目」。
 */
@Composable
private fun MyListEntryRow(
    icon: ImageVector,
    title: String,
    summary: String,
    entryCount: Int?,
    mangaCount: Int?,
    onClick: () -> Unit,
) {
    TextPreferenceWidget(
        title = title,
        subtitle = when (entryCount) {
            null -> null
            0 -> summary
            else -> stringResource(MR.strings.my_lists_entry_count, entryCount, mangaCount ?: 0)
        },
        icon = icon,
        onPreferenceClick = onClick,
    )
}
