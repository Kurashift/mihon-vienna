package eu.kanade.presentation.manga.components

import android.text.Selection
import android.text.Spannable
import android.util.TypedValue
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.textclassifier.TextClassifier
import android.widget.TextView
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.AttachMoney
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotatorConfig
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.system.copyToClipboard
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.findChildOfType
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.TextButton
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.clickableNoIndication
import tachiyomi.presentation.core.util.secondaryItemAlpha
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Instant
import android.graphics.Color as AndroidColor

@Composable
fun MangaInfoBox(
    isTabletUi: Boolean,
    appBarPadding: Dp,
    manga: Manga,
    sourceName: String,
    isStubSource: Boolean,
    onCoverClick: () -> Unit,
    doSearch: (query: String, global: Boolean) -> Unit,
    searchLocal: (query: String) -> Unit,
    onOpenSource: () -> Unit,
    titleSelection: MangaTitleSelectionController,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        // Backdrop
        val isLocalSource = remember(manga.source) { manga.isLocal() }
        val backdropGradientColors = listOf(
            Color.Transparent,
            MaterialTheme.colorScheme.background,
        )
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(manga)
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .matchParentSize()
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(colors = backdropGradientColors),
                    )
                }
                .blur(4.dp)
                .alpha(0.2f),
        )

        // Manga & source info
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            if (!isTabletUi) {
                MangaAndSourceTitlesSmall(
                    appBarPadding = appBarPadding,
                    manga = manga,
                    sourceName = sourceName,
                    isStubSource = isStubSource,
                    isLocalSource = isLocalSource,
                    onCoverClick = onCoverClick,
                    doSearch = doSearch,
                    searchLocal = searchLocal,
                    onOpenSource = onOpenSource,
                    titleSelection = titleSelection,
                )
            } else {
                MangaAndSourceTitlesLarge(
                    appBarPadding = appBarPadding,
                    manga = manga,
                    sourceName = sourceName,
                    isStubSource = isStubSource,
                    isLocalSource = isLocalSource,
                    onCoverClick = onCoverClick,
                    doSearch = doSearch,
                    searchLocal = searchLocal,
                    onOpenSource = onOpenSource,
                    titleSelection = titleSelection,
                )
            }
        }
    }
}

/**
 * The library action on its own, spanning the full width.
 *
 * Only used for local entries, where the expected update time and the tracker count are both
 * meaningless. A single action would look lost in a row of evenly weighted slots, so it takes
 * the whole row on its own instead.
 *
 * It stays deliberately flat: a local header carries little else, so filling the row would turn
 * this into the heaviest thing on the page and compete with the continue-reading FAB. The row
 * keeps the height and rhythm of the multi-action row, and the divider below draws the boundary.
 */
@Composable
private fun LibraryActionButton(
    favorite: Boolean,
    onAddToLibraryClicked: () -> Unit,
    onEditCategory: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val contentColor = MaterialTheme.colorScheme.primary
    TextButton(
        onClick = onAddToLibraryClicked,
        onLongClick = onEditCategory,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = libraryButtonIcon(favorite),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = contentColor,
            )
            Text(
                text = libraryButtonTitle(favorite),
                style = MaterialTheme.typography.titleSmall,
                color = contentColor,
            )
        }
    }
}

@Composable
private fun libraryButtonTitle(favorite: Boolean): String = if (favorite) {
    stringResource(MR.strings.in_library)
} else {
    stringResource(MR.strings.add_to_library)
}

@Composable
private fun libraryButtonIcon(favorite: Boolean): ImageVector = if (favorite) {
    Icons.Filled.CollectionsBookmark
} else {
    Icons.Outlined.CollectionsBookmark
}

@Composable
fun MangaActionRow(
    favorite: Boolean,
    trackingCount: Int,
    nextUpdate: Instant?,
    isUserIntervalMode: Boolean,
    isLocalSource: Boolean,
    onAddToLibraryClicked: () -> Unit,
    onWebViewClicked: (() -> Unit)?,
    onWebViewLongClicked: (() -> Unit)?,
    onTrackingClicked: () -> Unit,
    onEditIntervalClicked: (() -> Unit)?,
    onEditCategory: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // Local entries only keep the library action: the expected update time is an artifact of
    // the fetch interval a folder never has, and no tracker can be matched to a local file.
    if (isLocalSource) {
        LibraryActionButton(
            favorite = favorite,
            onAddToLibraryClicked = onAddToLibraryClicked,
            onEditCategory = onEditCategory,
            modifier = modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp),
        )
        return
    }

    val defaultActionButtonColor = MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)

    // TODO: show something better when using custom interval
    val nextUpdateDays = remember(nextUpdate) {
        return@remember if (nextUpdate != null) {
            val now = Clock.System.now()
            now.daysUntil(nextUpdate, TimeZone.currentSystemDefault()).coerceAtLeast(0)
        } else {
            null
        }
    }

    Row(modifier = modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp)) {
        MangaActionButton(
            title = libraryButtonTitle(favorite),
            icon = libraryButtonIcon(favorite),
            color = if (favorite) MaterialTheme.colorScheme.primary else defaultActionButtonColor,
            onClick = onAddToLibraryClicked,
            onLongClick = onEditCategory,
        )
        MangaActionButton(
            title = when (nextUpdateDays) {
                null -> stringResource(MR.strings.not_applicable)
                0 -> stringResource(MR.strings.manga_interval_expected_update_soon)
                else -> pluralStringResource(
                    MR.plurals.day,
                    count = nextUpdateDays,
                    nextUpdateDays,
                )
            },
            icon = Icons.Default.HourglassEmpty,
            color = if (isUserIntervalMode) {
                MaterialTheme.colorScheme.primary
            } else {
                defaultActionButtonColor
            },
            onClick = { onEditIntervalClicked?.invoke() },
        )
        MangaActionButton(
            title = if (trackingCount == 0) {
                stringResource(MR.strings.manga_tracking_tab)
            } else {
                pluralStringResource(MR.plurals.num_trackers, count = trackingCount, trackingCount)
            },
            icon = if (trackingCount == 0) Icons.Outlined.Sync else Icons.Outlined.Done,
            color = if (trackingCount == 0) {
                defaultActionButtonColor
            } else {
                MaterialTheme.colorScheme.primary
            },
            onClick = onTrackingClicked,
        )
        if (onWebViewClicked != null) {
            MangaActionButton(
                title = stringResource(MR.strings.action_web_view),
                icon = Icons.Outlined.Public,
                color = defaultActionButtonColor,
                onClick = onWebViewClicked,
                onLongClick = onWebViewLongClicked,
            )
        }
    }
}

@Composable
private fun RowScope.MangaActionButton(
    title: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.weight(1f),
        onLongClick = onLongClick,
    ) {
        MangaActionButtonContent(title = title, icon = icon, color = color)
    }
}

@Composable
private fun MangaActionButtonContent(
    title: String,
    icon: ImageVector,
    color: Color,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = color,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun ExpandableMangaDescription(
    defaultExpandState: Boolean,
    description: String?,
    tagsProvider: () -> List<String>?,
    notes: String,
    isLocalSource: Boolean,
    onTagSearch: (String) -> Unit,
    onCopyTagToClipboard: (tag: String) -> Unit,
    onEditNotes: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        val (expanded, onExpanded) = rememberSaveable {
            mutableStateOf(defaultExpandState)
        }
        // A local entry's description can only come from a ComicInfo.xml and there is no
        // way to edit it in the app, so leaving an empty entry there is just dead space.
        val desc = description.takeIf { !it.isNullOrBlank() }
            ?: if (isLocalSource) null else stringResource(MR.strings.description_placeholder)
        val tags = tagsProvider()

        // Nothing to expand and nothing to show: drop the section instead of leaving an
        // empty strip with a dangling caret below the info box. The divider above the chapter
        // header is what keeps that header at a stable distance either way.
        if (desc == null && notes.isBlank() && tags.isNullOrEmpty()) return

        MangaSummary(
            description = desc,
            expanded = expanded,
            notes = notes,
            onEditNotesClicked = onEditNotes,
            modifier = Modifier
                .padding(top = 8.dp)
                .padding(horizontal = 16.dp)
                .clickableNoIndication { onExpanded(!expanded) },
        )
        if (!tags.isNullOrEmpty()) {
            Box(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .padding(vertical = 12.dp)
                    .animateContentSize(animationSpec = spring())
                    .fillMaxWidth(),
            ) {
                var showMenu by remember { mutableStateOf(false) }
                var tagSelected by remember { mutableStateOf("") }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(text = stringResource(MR.strings.action_search)) },
                        onClick = {
                            onTagSearch(tagSelected)
                            showMenu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(text = stringResource(MR.strings.action_copy_to_clipboard)) },
                        onClick = {
                            onCopyTagToClipboard(tagSelected)
                            showMenu = false
                        },
                    )
                }
                if (expanded) {
                    FlowRow(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                    ) {
                        tags.forEach {
                            TagsChip(
                                modifier = DefaultTagChipModifier,
                                text = it,
                                onClick = {
                                    tagSelected = it
                                    showMenu = true
                                },
                            )
                        }
                    }
                } else {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = MaterialTheme.padding.medium),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                    ) {
                        items(items = tags) {
                            TagsChip(
                                modifier = DefaultTagChipModifier,
                                text = it,
                                onClick = {
                                    tagSelected = it
                                    showMenu = true
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MangaAndSourceTitlesLarge(
    appBarPadding: Dp,
    manga: Manga,
    sourceName: String,
    isStubSource: Boolean,
    isLocalSource: Boolean,
    onCoverClick: () -> Unit,
    doSearch: (query: String, global: Boolean) -> Unit,
    searchLocal: (query: String) -> Unit,
    onOpenSource: () -> Unit,
    titleSelection: MangaTitleSelectionController,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = appBarPadding + 16.dp, end = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MangaCover.Book(
            modifier = Modifier.fillMaxWidth(0.65f),
            data = ImageRequest.Builder(LocalContext.current)
                .data(manga)
                .crossfade(true)
                .build(),
            contentDescription = stringResource(MR.strings.manga_cover),
            onClick = onCoverClick,
        )
        Spacer(modifier = Modifier.height(16.dp))
        MangaContentInfo(
            title = manga.title,
            author = manga.author,
            artist = manga.artist,
            status = manga.status,
            sourceName = sourceName,
            isStubSource = isStubSource,
            isLocalSource = isLocalSource,
            doSearch = doSearch,
            searchLocal = searchLocal,
            onOpenSource = onOpenSource,
            titleSelection = titleSelection,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MangaAndSourceTitlesSmall(
    appBarPadding: Dp,
    manga: Manga,
    sourceName: String,
    isStubSource: Boolean,
    isLocalSource: Boolean,
    onCoverClick: () -> Unit,
    doSearch: (query: String, global: Boolean) -> Unit,
    searchLocal: (query: String) -> Unit,
    onOpenSource: () -> Unit,
    titleSelection: MangaTitleSelectionController,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = appBarPadding + 16.dp, end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MangaCover.Book(
            modifier = Modifier
                .sizeIn(maxWidth = DETAIL_COVER_MAX_WIDTH)
                .align(Alignment.Top),
            data = ImageRequest.Builder(LocalContext.current)
                .data(manga)
                .crossfade(true)
                .build(),
            contentDescription = stringResource(MR.strings.manga_cover),
            onClick = onCoverClick,
        )
        Column(
            // 占满封面右侧的剩余宽度，标题等文字才能用上整列排版。
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            MangaContentInfo(
                title = manga.title,
                author = manga.author,
                artist = manga.artist,
                status = manga.status,
                sourceName = sourceName,
                isStubSource = isStubSource,
                isLocalSource = isLocalSource,
                doSearch = doSearch,
                searchLocal = searchLocal,
                onOpenSource = onOpenSource,
                titleSelection = titleSelection,
            )
        }
    }
}

@Composable
private fun ColumnScope.MangaContentInfo(
    title: String,
    author: String?,
    artist: String?,
    status: Long,
    sourceName: String,
    isStubSource: Boolean,
    isLocalSource: Boolean,
    doSearch: (query: String, global: Boolean) -> Unit,
    searchLocal: (query: String) -> Unit,
    onOpenSource: () -> Unit,
    // 标题选区交给原生 TextView。Compose 的 SelectionContainer 在拖动手柄时，只要手指
    // 越过 TextLayoutResult 的排版高度边界，就会被 isSelected() 判定成「整段选中」，
    // 单行标题表现为「下移一点就全选、上移一点就反选」；而那个边界取自 TextLayoutResult
    // 的尺寸，Modifier 的 padding/height 都影响不到它，上层无法定向修正。原生 TextView
    // 由系统实现选区，斜拖、跨行、越界回收都是标准行为。
    titleSelection: MangaTitleSelectionController,
    textAlign: TextAlign? = LocalTextStyle.current.textAlign,
) {
    val context = LocalContext.current
    val displayedTitle = title.ifBlank { stringResource(MR.strings.unknown_title) }
    val localSearchLabel = stringResource(MR.strings.manga_title_search_local)
    val sourceSearchLabel = stringResource(MR.strings.manga_title_search_sources)
    // 跟随系统语言，和原生 TextView 的选区菜单保持一致。
    val copyLabel = remember(context) { context.getString(android.R.string.copy) }
    val titleStyle = MaterialTheme.typography.titleLarge
    val titleColor = LocalContentColor.current.toArgb()

    // AndroidView 的 factory 只跑一次，里面的回调必须拿到最新的搜索 lambda，
    // 否则重组之后会跳到过期的页面状态。
    val currentSearchLocal by rememberUpdatedState(searchLocal)
    val currentDoSearch by rememberUpdatedState(doSearch)

    DisposableEffect(titleSelection) {
        onDispose { titleSelection.unbindClearAction() }
    }

    AndroidView(
        factory = { viewContext ->
            TextView(viewContext).apply {
                setTextIsSelectable(true)
                // 关掉 smart text selection 的分类建议：标题只显示文本，用不到 URL/电话等
                // 智能识别。注意这挡不住 ROM 自己注入的 assist 项（网页搜索/翻译）——那些
                // 不走 TextClassifier，菜单里仍可能出现，见 MangaTitleSelectionCallback。
                setTextClassifier(TextClassifier.NO_OP)
                setBackgroundColor(AndroidColor.TRANSPARENT)
                includeFontPadding = false
            }
        },
        update = { titleView ->
            titleView.text = displayedTitle
            titleView.setTextColor(titleColor)
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, titleStyle.fontSize.value)
            titleView.gravity = when (textAlign) {
                TextAlign.Center -> Gravity.CENTER_HORIZONTAL
                TextAlign.End, TextAlign.Right -> Gravity.END
                else -> Gravity.START
            }
            // 选区还在（ActionMode 挂着）时不跳搜索：这一下点击只是用来收掉选区的。
            // 这里用 ActionMode 的存活状态而不是 hasSelection()，因为系统会在派发
            // click 之前就把选区清掉，那时 hasSelection() 已经是 false，会误触发跳转。
            titleView.setOnClickListener {
                if (title.isNotBlank() && !titleSelection.isActive) {
                    currentDoSearch(title, true)
                }
            }
            titleView.customSelectionActionModeCallback = MangaTitleSelectionCallback(
                textView = titleView,
                copyLabel = copyLabel,
                localSearchLabel = localSearchLabel,
                sourceSearchLabel = sourceSearchLabel,
                onLocalSearch = { query -> currentSearchLocal(query) },
                onSourceSearch = { query -> currentDoSearch(query, true) },
                onActionModeCreated = titleSelection::bindActionMode,
                onActionModeDestroyed = { titleSelection.bindActionMode(null) },
                onSelectionActiveChange = titleSelection::setActive,
            )
            titleSelection.bindClearAction { titleView.clearTextSelection() }
        },
        modifier = Modifier
            // 按文字实际宽度收拢：撑满整行时，短标题右侧那片空白也会算进点击区，
            // 点空白同样会跳搜索。长标题仍受父级宽度约束，会正常换行。
            .wrapContentWidth(
                align = when (textAlign) {
                    TextAlign.Center -> Alignment.CenterHorizontally
                    TextAlign.End, TextAlign.Right -> Alignment.End
                    else -> Alignment.Start
                },
            )
            // 上报标题在窗口里的位置，供 MangaScreen 外层「点外部清选区」判断按下点是否落在标题内。
            .onGloballyPositioned { titleSelection.updateTitleRect(it.boundsInWindow()) },
    )

    // Local files carry no author unless a ComicInfo.xml supplies one. An "unknown author"
    // row would only be a dead placeholder there, so skip it entirely for local entries.
    // Every row below carries its own top padding instead of being separated by spacers, so
    // hiding any of them leaves the same even gap rather than stacking two spacers.
    if (!isLocalSource || !author.isNullOrBlank()) {
        Row(
            modifier = Modifier
                .secondaryItemAlpha()
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.PersonOutline,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = author?.takeIf { it.isNotBlank() }
                    ?: stringResource(MR.strings.unknown_author),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .combinedClickable(
                        onLongClick = {
                            if (!author.isNullOrBlank()) {
                                context.copyToClipboard(
                                    author,
                                    author,
                                )
                            }
                        },
                        onClick = { if (!author.isNullOrBlank()) doSearch(author, true) },
                    ),
                textAlign = textAlign,
            )
        }
    }

    if (!artist.isNullOrBlank() && author != artist) {
        Row(
            modifier = Modifier
                .secondaryItemAlpha()
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Brush,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = artist,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .combinedClickable(
                        onLongClick = { context.copyToClipboard(artist, artist) },
                        onClick = { doSearch(artist, true) },
                    ),
                textAlign = textAlign,
            )
        }
    }

    val statusIcon = when (status) {
        SManga.ONGOING.toLong() -> Icons.Outlined.Schedule
        SManga.COMPLETED.toLong() -> Icons.Outlined.DoneAll
        SManga.LICENSED.toLong() -> Icons.Outlined.AttachMoney
        SManga.PUBLISHING_FINISHED.toLong() -> Icons.Outlined.Done
        SManga.CANCELLED.toLong() -> Icons.Outlined.Close
        SManga.ON_HIATUS.toLong() -> Icons.Outlined.Pause
        else -> null
    }
    val statusText = when (status) {
        SManga.ONGOING.toLong() -> stringResource(MR.strings.ongoing)
        SManga.COMPLETED.toLong() -> stringResource(MR.strings.completed)
        SManga.LICENSED.toLong() -> stringResource(MR.strings.licensed)
        SManga.PUBLISHING_FINISHED.toLong() -> stringResource(MR.strings.publishing_finished)
        SManga.CANCELLED.toLong() -> stringResource(MR.strings.cancelled)
        SManga.ON_HIATUS.toLong() -> stringResource(MR.strings.on_hiatus)
        else -> null
    }
    // Same as the author: a local entry has no publication state unless ComicInfo.xml
    // provides one, so only show "unknown" where it is real source metadata.
    val showStatus = !isLocalSource || statusText != null

    Row(
        modifier = Modifier
            .secondaryItemAlpha()
            .padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showStatus) {
            Icon(
                imageVector = statusIcon ?: Icons.Outlined.Block,
                contentDescription = null,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(16.dp),
            )
        }
        ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
            if (showStatus) {
                Text(
                    text = statusText ?: stringResource(MR.strings.unknown),
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                )
                DotSeparatorText()
            }
            if (isStubSource) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(16.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                text = sourceName,
                modifier = Modifier.clickableNoIndication {
                    onOpenSource()
                },
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun descriptionAnnotator(loadImages: Boolean, linkStyle: SpanStyle) = remember(loadImages, linkStyle) {
    markdownAnnotator(
        annotate = { content, child ->
            if (!loadImages && child.type == MarkdownElementTypes.IMAGE) {
                val inlineLink = child.findChildOfType(MarkdownElementTypes.INLINE_LINK)

                val url = inlineLink?.findChildOfType(MarkdownElementTypes.LINK_DESTINATION)
                    ?.getUnescapedTextInNode(content)
                    ?: inlineLink?.findChildOfType(MarkdownElementTypes.AUTOLINK)
                        ?.findChildOfType(MarkdownTokenTypes.AUTOLINK)
                        ?.getUnescapedTextInNode(content)
                    ?: return@markdownAnnotator false

                val textNode = inlineLink?.findChildOfType(MarkdownElementTypes.LINK_TITLE)
                    ?: inlineLink?.findChildOfType(MarkdownElementTypes.LINK_TEXT)
                val altText = textNode?.findChildOfType(MarkdownTokenTypes.TEXT)
                    ?.getUnescapedTextInNode(content).orEmpty()

                withLink(LinkAnnotation.Url(url = url)) {
                    pushStyle(linkStyle)
                    appendInlineContent(MARKDOWN_INLINE_IMAGE_TAG)
                    append(altText)
                    pop()
                }

                return@markdownAnnotator true
            }

            if (child.type in DISALLOWED_MARKDOWN_TYPES) {
                append(content.substring(child.startOffset, child.endOffset))
                return@markdownAnnotator true
            }

            false
        },
        config = markdownAnnotatorConfig(
            eolAsNewLine = true,
        ),
    )
}

@Composable
private fun MangaSummary(
    description: String?,
    notes: String,
    expanded: Boolean,
    onEditNotesClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val preferences = remember { Injekt.get<UiPreferences>() }
    val loadImages = remember { preferences.imagesInDescription.get() }
    val animProgress by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        label = "summary",
    )
    var infoHeight by remember { mutableIntStateOf(0) }
    Layout(
        modifier = modifier.clipToBounds(),
        contents = listOf(
            {
                Text(
                    // Shows at least 3 lines if there is text to show, 6 when notes are
                    // present, and only a single line when the section is effectively empty.
                    text = when {
                        notes.isNotBlank() -> "\n\n\n\n\n"
                        description.isNullOrBlank() -> ""
                        else -> "\n\n"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            {
                Column(
                    modifier = Modifier.onSizeChanged { size ->
                        infoHeight = size.height
                    },
                ) {
                    MangaNotesSection(
                        content = notes,
                        expanded = expanded,
                        onEditNotes = onEditNotesClicked,
                    )
                    if (!description.isNullOrBlank()) {
                        SelectionContainer {
                            MarkdownRender(
                                content = description,
                                modifier = Modifier.secondaryItemAlpha(),
                                annotator = descriptionAnnotator(
                                    loadImages = loadImages,
                                    linkStyle = getMarkdownLinkStyle().toSpanStyle(),
                                ),
                                loadImages = loadImages,
                            )
                        }
                    }
                }
            },
            {
                val colors = listOf(Color.Transparent, MaterialTheme.colorScheme.background)
                Box(
                    modifier = Modifier.background(Brush.verticalGradient(colors = colors)),
                    contentAlignment = Alignment.Center,
                ) {
                    val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_caret_down)
                    Icon(
                        painter = rememberAnimatedVectorPainter(image, !expanded),
                        contentDescription = stringResource(
                            if (expanded) MR.strings.manga_info_collapse else MR.strings.manga_info_expand,
                        ),
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.background(Brush.radialGradient(colors = colors.asReversed())),
                    )
                }
            },
        ),
    ) { (shrunk, actual, scrim), constraints ->
        val shrunkHeight = shrunk.single()
            .measure(constraints)
            .height
        val heightDelta = infoHeight - shrunkHeight
        val scrimHeight = 24.dp.roundToPx()

        val actualPlaceable = actual.single()
            .measure(constraints)
        val scrimPlaceable = scrim.single()
            .measure(Constraints.fixed(width = constraints.maxWidth, height = scrimHeight))

        val currentHeight = shrunkHeight + ((heightDelta + scrimHeight) * animProgress).roundToInt()
        layout(constraints.maxWidth, currentHeight) {
            actualPlaceable.place(0, 0)

            val scrimY = currentHeight - scrimHeight
            scrimPlaceable.place(0, scrimY)
        }
    }
}

private val DefaultTagChipModifier = Modifier.padding(vertical = 4.dp)

@Composable
private fun TagsChip(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        SuggestionChip(
            modifier = modifier,
            onClick = onClick,
            label = { Text(text = text, style = MaterialTheme.typography.bodySmall) },
        )
    }
}

/**
 * Width of the cover on the detail page.
 *
 * Chapter rows in the list below draw their covers at 96dp, so this is deliberately a step
 * larger: the entry being described should read as the subject of the page rather than as one
 * more row of it. Bigger than this starts squeezing the title column on small screens.
 */
private val DETAIL_COVER_MAX_WIDTH = 120.dp

private const val TITLE_ACTION_COPY = 1
private const val TITLE_ACTION_SEARCH_LOCAL = 2
private const val TITLE_ACTION_SEARCH_SOURCES = 3

private val TITLE_ACTION_IDS = setOf(
    TITLE_ACTION_COPY,
    TITLE_ACTION_SEARCH_LOCAL,
    TITLE_ACTION_SEARCH_SOURCES,
)

/**
 * 标题选区的桥接层。原生 TextView 的选区由系统的 ActionMode 统一管理（点击外部、返回键、
 * 滚动都会由系统收掉），Compose 侧只需要知道「当前有没有选区」以及一个主动清除的入口。
 *
 * 由 [MangaScreen] 持有并 remember，标题所在 item 被回收时通过 [unbindClearAction] 解绑，
 * 避免持有已经 detach 的 TextView。
 */
class MangaTitleSelectionController internal constructor() {
    /** 是否有标题选区（ActionMode 是否挂着）。用于返回键拦截和点击抑制。 */
    var isActive by mutableStateOf(false)
        private set

    private var actionMode: ActionMode? = null
    private var clearAction: (() -> Unit)? = null

    internal fun setActive(active: Boolean) {
        isActive = active
    }

    internal fun bindActionMode(mode: ActionMode?) {
        actionMode = mode
    }

    internal fun bindClearAction(action: () -> Unit) {
        clearAction = action
    }

    internal fun unbindClearAction() {
        actionMode = null
        clearAction = null
    }

    // 标题 TextView 在窗口里的位置（boundsInWindow）。点外部拦截用：按下点落在标题内时
    // 交由 TextView 自己处理（拖手柄、点标题跳搜索），落在外面才清选区。初始 Zero 无所谓，
    // 因为要长按标题才可能激活选区，那时标题早已布局并上报过真实 rect。
    private var titleRect: Rect = Rect.Zero

    internal fun updateTitleRect(rect: Rect) {
        titleRect = rect
    }

    internal fun isOutsideTitle(position: Offset): Boolean = !titleRect.contains(position)

    /**
     * 主动收掉选区。优先 finish 掉 ActionMode——这才是彻底的收尾，选区高亮、手柄、
     * 工具栏会一起消失，和点外部/系统返回键的效果一致。没有 ActionMode 时（例如选区
     * 存在但工具栏还没起来）退化成直接清空 Selection。
     */
    fun clear() {
        val mode = actionMode
        if (mode != null) {
            mode.finish()
        } else {
            clearAction?.invoke()
        }
    }
}

private fun TextView.clearTextSelection() {
    // setTextIsSelectable(true) 会把 buffer 类型切成 SPANNABLE，所以这里通常成立；
    // 万一不是 Spannable 就无从清除，直接跳过，系统后续仍会按常规流程收掉选区。
    val text = text as? Spannable ?: return
    Selection.setSelection(text, 0, 0)
}

private fun TextView.selectedTextOrNull(): String? {
    val start = selectionStart
    val end = selectionEnd
    if (start < 0 || end < 0 || start == end) return null
    val text = text ?: return null
    return text.subSequence(minOf(start, end), maxOf(end, start)).toString()
}

/**
 * 标题选区的菜单：固定三项（复制 → 云端搜索 → 本地搜索），并尽量删掉系统补进来的项。
 *
 * 注意一个已经实测确认的边界：这个回调**无法保证**工具栏最终只剩这三项。悬浮工具栏每次显示
 * 时才会去读菜单，而 ROM 注入的 assist 项（网页搜索/翻译等）是在这之后异步加进来的，加完立刻
 * 触发工具栏刷新——我们只能在它加完之后动手，永远慢一拍。这里的删除只能保证我们的三项一定在
 * 菜单里（且排在前面），剩下来的系统项由平台决定，删不掉就不要再跟它赛跑。
 */
private class MangaTitleSelectionCallback(
    private val textView: TextView,
    private val copyLabel: String,
    private val localSearchLabel: String,
    private val sourceSearchLabel: String,
    private val onLocalSearch: (String) -> Unit,
    private val onSourceSearch: (String) -> Unit,
    private val onActionModeCreated: (ActionMode) -> Unit,
    private val onActionModeDestroyed: () -> Unit,
    private val onSelectionActiveChange: (Boolean) -> Unit,
) : ActionMode.Callback {

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        onActionModeCreated(mode)
        onSelectionActiveChange(true)
        populateMenu(menu)
        return true
    }

    // 这里故意不重新 add：ActionMode 每次内容变化都会回调 onPrepareActionMode，重复
    // add 会让菜单在两项和三项之间闪。删除系统项必须在这里做——onCreateActionMode 里
    // 的 menu.clear() 只清得掉那一刻的项，系统 Editor 之后补回来的（尤其是 assist/翻译）
    // 它管不到。
    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        menu.removeForeignTitleActions()
        return true
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        val selectedText = textView.selectedTextOrNull() ?: return false
        when (item.itemId) {
            TITLE_ACTION_COPY -> textView.context.copyToClipboard(selectedText, selectedText)
            TITLE_ACTION_SEARCH_LOCAL -> onLocalSearch(selectedText)
            TITLE_ACTION_SEARCH_SOURCES -> onSourceSearch(selectedText)
            else -> return false
        }
        mode.finish()
        return true
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        onActionModeDestroyed()
        onSelectionActiveChange(false)
    }

    private fun populateMenu(menu: Menu) {
        menu.clear()
        // 工具栏只有固定的三项（复制 → 云端搜索 → 本地搜索）：没有 NEVER 项就没有三点
        // 溢出菜单，系统注入项在 clear 时一起被清掉，prepare 阶段再兜底删一轮。
        menu.add(Menu.NONE, TITLE_ACTION_COPY, 0, copyLabel)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(Menu.NONE, TITLE_ACTION_SEARCH_SOURCES, 1, sourceSearchLabel)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(Menu.NONE, TITLE_ACTION_SEARCH_LOCAL, 2, localSearchLabel)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
    }

    /**
     * 把非自建的系统项全部删掉。选区菜单会被系统 Editor 追加内容：全选、翻译、网页搜索
     * （放大镜）等——全选来自系统，翻译/网页搜索来自 ACTION_PROCESS_TEXT 或 ROM 注入，由
     * 手机上装的其它应用提供。
     */
    private fun Menu.removeForeignTitleActions() {
        for (index in size() - 1 downTo 0) {
            val item = getItem(index)
            if (item.itemId !in TITLE_ACTION_IDS) {
                removeItem(item.itemId)
            }
        }
    }
}
