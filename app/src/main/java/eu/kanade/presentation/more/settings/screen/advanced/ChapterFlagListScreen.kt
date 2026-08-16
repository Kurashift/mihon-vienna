package eu.kanade.presentation.more.settings.screen.advanced

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.manga.ChapterFlagStore
import eu.kanade.tachiyomi.data.manga.GoodDoujinStore
import eu.kanade.tachiyomi.data.manga.MangaMark
import eu.kanade.tachiyomi.data.manga.MangaMarkStore
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Which per-chapter flag list a screen belongs to. Duplicates and good doujins
 * share the exact same two-level layout, only the labels differ.
 */
enum class ChapterFlagListType(
    val titleRes: StringResource,
    val emptyRes: StringResource,
    val clearConfirmRes: StringResource,
    val chapterCountRes: StringResource,
) {
    DUPLICATES(
        MR.strings.marks_list_title,
        MR.strings.marks_list_empty,
        MR.strings.marks_list_clear_confirm,
        MR.strings.marks_list_chapter_count,
    ),
    GOOD_DOUJINS(
        MR.strings.good_doujin_list_title,
        MR.strings.good_doujin_list_empty,
        MR.strings.good_doujin_list_clear_confirm,
        MR.strings.good_doujin_list_chapter_count,
    ),
}

/**
 * Level one of a chapter flag list: one row per manga. Tap a row to see its
 * chapters, or use the corner button to jump straight to the manga page.
 */
class ChapterFlagListScreen(
    private val type: ChapterFlagListType,
) : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val store = rememberStore(type)
        val marks by store.marks.collectAsState()
        val grouped = remember(marks) {
            marks.groupBy { it.mangaId }
                .map { (_, mangaMarks) -> mangaMarks }
                .sortedByDescending { it.maxOf { mark -> mark.markedAt } }
        }
        var showClearConfirm by remember { mutableStateOf(false) }
        var selectedMangaIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
        var pendingRemovalMangaIds by remember { mutableStateOf<Set<Long>?>(null) }
        val listTitle = stringResource(type.titleRes)

        LaunchedEffect(grouped) {
            val availableIds = grouped.mapTo(mutableSetOf()) { it.first().mangaId }
            selectedMangaIds = selectedMangaIds.intersect(availableIds)
        }
        BackHandler(enabled = selectedMangaIds.isNotEmpty()) {
            selectedMangaIds = emptySet()
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(type.titleRes),
                    navigateUp = navigator::pop,
                    actions = {
                        AppBarActions(
                            listOf(
                                AppBar.Action(
                                    title = stringResource(MR.strings.marks_list_share),
                                    icon = Icons.Outlined.Share,
                                    onClick = {
                                        val text = buildMarksText(marks, listTitle)
                                        if (text.isNotEmpty()) {
                                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_TEXT, text)
                                            }
                                            context.startActivity(Intent.createChooser(sendIntent, null))
                                        }
                                    },
                                ),
                                AppBar.Action(
                                    title = stringResource(MR.strings.marks_list_clear),
                                    icon = Icons.Outlined.Delete,
                                    onClick = { showClearConfirm = true },
                                ),
                            ),
                        )
                    },
                    actionModeCounter = selectedMangaIds.size,
                    onCancelActionMode = { selectedMangaIds = emptySet() },
                    actionModeActions = {
                        AppBarActions(
                            listOf(
                                AppBar.Action(
                                    title = stringResource(MR.strings.action_remove),
                                    icon = Icons.Outlined.Delete,
                                    onClick = { pendingRemovalMangaIds = selectedMangaIds },
                                ),
                            ),
                        )
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            if (marks.isEmpty()) {
                EmptyScreen(
                    stringRes = type.emptyRes,
                    modifier = Modifier.padding(contentPadding),
                )
            } else {
                LazyColumn(contentPadding = contentPadding) {
                    grouped.forEach { mangaMarks ->
                        val mangaMark = mangaMarks.first()
                        item(key = "manga-${mangaMark.mangaId}") {
                            ChapterFlagMangaRow(
                                mangaMark = mangaMark,
                                chapterCount = mangaMarks.size,
                                chapterCountRes = type.chapterCountRes,
                                selectionMode = selectedMangaIds.isNotEmpty(),
                                selected = mangaMark.mangaId in selectedMangaIds,
                                onClick = {
                                    navigator.push(
                                        ChapterFlagDetailScreen(type, mangaMark.mangaId, mangaMark.mangaTitle),
                                    )
                                },
                                onToggleSelection = {
                                    selectedMangaIds = selectedMangaIds.toMutableSet().apply {
                                        if (!add(mangaMark.mangaId)) remove(mangaMark.mangaId)
                                    }
                                },
                                onOpenDetail = {
                                    openManga(navigator, scope, context, mangaMark.mangaId)
                                },
                                onRemove = {
                                    pendingRemovalMangaIds = setOf(mangaMark.mangaId)
                                },
                            )
                        }
                    }
                }
            }
        }

        if (showClearConfirm) {
            AlertDialog(
                onDismissRequest = { showClearConfirm = false },
                title = { Text(stringResource(MR.strings.are_you_sure)) },
                text = { Text(stringResource(type.clearConfirmRes)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                store.clear()
                                showClearConfirm = false
                            }
                        },
                    ) {
                        Text(stringResource(MR.strings.marks_list_clear))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearConfirm = false }) {
                        Text(stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }

        pendingRemovalMangaIds?.let { mangaIds ->
            AlertDialog(
                onDismissRequest = { pendingRemovalMangaIds = null },
                title = { Text(stringResource(MR.strings.are_you_sure)) },
                text = {
                    Text(
                        stringResource(
                            MR.strings.marks_list_remove_selected_confirm,
                            mangaIds.size,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                store.clearMangas(mangaIds)
                                selectedMangaIds = selectedMangaIds - mangaIds
                                pendingRemovalMangaIds = null
                            }
                        },
                    ) {
                        Text(stringResource(MR.strings.action_remove))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingRemovalMangaIds = null }) {
                        Text(stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }
    }
}

/**
 * Level two of a chapter flag list: every flagged chapter of one manga.
 * Tap a row to open the reader at that chapter.
 */
class ChapterFlagDetailScreen(
    private val type: ChapterFlagListType,
    private val mangaId: Long,
    private val mangaTitle: String,
) : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val store = rememberStore(type)
        val marks by store.marks.collectAsState()
        val mangaMarks = remember(marks) {
            marks.filter { it.mangaId == mangaId }.sortedByDescending { it.markedAt }
        }
        var showClearConfirm by remember { mutableStateOf(false) }
        var pendingRemovalMark by remember { mutableStateOf<MangaMark?>(null) }
        val listTitle = stringResource(type.titleRes)

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = mangaTitle,
                    navigateUp = navigator::pop,
                    actions = {
                        AppBarActions(
                            listOf(
                                AppBar.Action(
                                    title = stringResource(MR.strings.marks_list_share),
                                    icon = Icons.Outlined.Share,
                                    onClick = {
                                        val text = buildMarksText(mangaMarks, listTitle)
                                        if (text.isNotEmpty()) {
                                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_TEXT, text)
                                            }
                                            context.startActivity(Intent.createChooser(sendIntent, null))
                                        }
                                    },
                                ),
                                AppBar.Action(
                                    title = stringResource(MR.strings.marks_list_open_manga),
                                    icon = Icons.AutoMirrored.Outlined.OpenInNew,
                                    onClick = { openManga(navigator, scope, context, mangaId) },
                                ),
                                AppBar.Action(
                                    title = stringResource(MR.strings.marks_list_clear),
                                    icon = Icons.Outlined.Delete,
                                    onClick = { showClearConfirm = true },
                                ),
                            ),
                        )
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            if (mangaMarks.isEmpty()) {
                EmptyScreen(
                    stringRes = type.emptyRes,
                    modifier = Modifier.padding(contentPadding),
                )
            } else {
                LazyColumn(contentPadding = contentPadding) {
                    items(mangaMarks, key = { it.chapterId }) { mark ->
                        ChapterFlagChapterRow(
                            mark = mark,
                            onClick = { openChapterReader(context, scope, mangaId, mark.chapterId) },
                            onRemove = { pendingRemovalMark = mark },
                        )
                    }
                }
            }
        }

        if (showClearConfirm) {
            AlertDialog(
                onDismissRequest = { showClearConfirm = false },
                title = { Text(stringResource(MR.strings.are_you_sure)) },
                text = { Text(stringResource(type.clearConfirmRes)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                store.clearManga(mangaId)
                                showClearConfirm = false
                            }
                        },
                    ) {
                        Text(stringResource(MR.strings.marks_list_clear))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearConfirm = false }) {
                        Text(stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }

        pendingRemovalMark?.let { mark ->
            AlertDialog(
                onDismissRequest = { pendingRemovalMark = null },
                title = { Text(stringResource(MR.strings.are_you_sure)) },
                text = {
                    Text(
                        stringResource(
                            MR.strings.marks_list_remove_entry_confirm,
                            mark.chapterName,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                store.remove(mark)
                                pendingRemovalMark = null
                            }
                        },
                    ) {
                        Text(stringResource(MR.strings.action_remove))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingRemovalMark = null }) {
                        Text(stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }
    }
}

@Composable
private fun rememberStore(type: ChapterFlagListType): ChapterFlagStore {
    return remember(type) {
        when (type) {
            ChapterFlagListType.DUPLICATES -> Injekt.get<MangaMarkStore>()
            ChapterFlagListType.GOOD_DOUJINS -> Injekt.get<GoodDoujinStore>()
        }
    }
}

@Composable
private fun ChapterFlagMangaRow(
    mangaMark: MangaMark,
    chapterCount: Int,
    chapterCountRes: StringResource,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onToggleSelection: () -> Unit,
    onOpenDetail: () -> Unit,
    onRemove: () -> Unit,
) {
    val manga = rememberManga(mangaMark.mangaId)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selected) {
                    Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                } else {
                    Modifier
                },
            )
            .combinedClickable(
                onClick = {
                    if (selectionMode) onToggleSelection() else onClick()
                },
                onLongClick = onToggleSelection,
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .combinedClickable(
                    onClick = {
                        if (selectionMode) onToggleSelection() else onOpenDetail()
                    },
                    onLongClick = onToggleSelection,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (manga != null) {
                MangaCover.Square(
                    data = manga.asMangaCover(),
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp, end = 8.dp),
        ) {
            Text(
                text = mangaMark.mangaTitle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(chapterCountRes, chapterCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selectionMode) {
            Icon(
                imageVector = if (selected) {
                    Icons.Outlined.CheckCircle
                } else {
                    Icons.Outlined.RadioButtonUnchecked
                },
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
            )
        } else {
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(MR.strings.marks_list_remove),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ChapterFlagChapterRow(
    mark: MangaMark,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.PlayArrow,
            contentDescription = stringResource(MR.strings.marks_list_open_chapter),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = mark.chapterName,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = formatMarkTime(mark.markedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(MR.strings.marks_list_remove),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun rememberManga(mangaId: Long): Manga? {
    val repository = remember { Injekt.get<MangaRepository>() }
    var manga by remember { mutableStateOf<Manga?>(null) }
    LaunchedEffect(mangaId) {
        manga = runCatching { withIOContext { repository.getMangaById(mangaId) } }.getOrNull()
    }
    return manga
}

private fun openManga(
    navigator: cafe.adriel.voyager.navigator.Navigator,
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
    mangaId: Long,
) {
    scope.launch {
        val repository = Injekt.get<MangaRepository>()
        val exists = runCatching { withIOContext { repository.getMangaById(mangaId) } }.isSuccess
        if (exists) {
            navigator.push(MangaScreen(mangaId))
        } else {
            context.toast(MR.strings.marks_list_manga_missing)
        }
    }
}

private fun openChapterReader(
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    mangaId: Long,
    chapterId: Long,
) {
    scope.launch {
        val repository = Injekt.get<MangaRepository>()
        val exists = runCatching { withIOContext { repository.getMangaById(mangaId) } }.isSuccess
        if (exists) {
            context.startActivity(ReaderActivity.newIntent(context, mangaId, chapterId))
        } else {
            context.toast(MR.strings.marks_list_manga_missing)
        }
    }
}

private fun formatMarkTime(timeMillis: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timeMillis))
}

/**
 * Builds a plain-text report grouped by manga, ready to be shared or saved.
 */
private fun buildMarksText(marks: List<MangaMark>, listTitle: String): String {
    if (marks.isEmpty()) return ""
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    val sb = StringBuilder()
    sb.append("Mihon ").append(listTitle).append("\uff08\u5171 ").append(marks.size).append(" \u6761\uff09\n\n")
    marks.groupBy { it.mangaId }.forEach { (_, mangaMarks) ->
        sb.append("\u3010").append(mangaMarks.first().mangaTitle).append("\u3011\n")
        mangaMarks.forEach { mark ->
            sb.append("  - ").append(mark.chapterName)
                .append("  (").append(dateFormat.format(Date(mark.markedAt))).append(")\n")
        }
        sb.append("\n")
    }
    return sb.toString()
}
