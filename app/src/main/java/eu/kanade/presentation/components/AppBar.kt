package eu.kanade.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults.rememberTooltipPositionProvider
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.util.marqueeTitle
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.clearFocusOnSoftKeyboardHide
import tachiyomi.presentation.core.util.runOnEnterKeyPressed
import tachiyomi.presentation.core.util.secondaryItemAlpha
import tachiyomi.presentation.core.util.showSoftKeyboard

@Composable
fun AppBar(
    title: String?,

    modifier: Modifier = Modifier,
    backgroundColor: Color? = null,
    // Text
    subtitle: String? = null,
    // 点标题进入下一层（如从清单跳到漫画详情）。传 null 表示标题只是文字。
    onClickTitle: (() -> Unit)? = null,
    // Up button
    navigateUp: (() -> Unit)? = null,
    navigationIcon: ImageVector? = null,
    // Extra nav buttons (right of the up button)
    navigationActions: @Composable RowScope.() -> Unit = {},
    // Menu
    actions: @Composable RowScope.() -> Unit = {},
    // Action mode
    actionModeCounter: Int = 0,
    onCancelActionMode: () -> Unit = {},
    actionModeActions: @Composable RowScope.() -> Unit = {},

    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    val isActionMode by remember(actionModeCounter) {
        derivedStateOf { actionModeCounter > 0 }
    }

    AppBar(
        modifier = modifier,
        backgroundColor = backgroundColor,
        titleContent = {
            if (isActionMode) {
                AppBarTitle(actionModeCounter.toString())
            } else {
                AppBarTitle(title, subtitle = subtitle, onClickTitle = onClickTitle)
            }
        },
        navigateUp = navigateUp,
        navigationIcon = navigationIcon,
        navigationActions = navigationActions,
        actions = {
            if (isActionMode) {
                actionModeActions()
            } else {
                actions()
            }
        },
        isActionMode = isActionMode,
        onCancelActionMode = onCancelActionMode,
        scrollBehavior = scrollBehavior,
    )
}

@Composable
fun AppBar(
    // Title
    titleContent: @Composable () -> Unit,

    modifier: Modifier = Modifier,
    backgroundColor: Color? = null,
    // Up button
    navigateUp: (() -> Unit)? = null,
    navigationIcon: ImageVector? = null,
    // Extra nav buttons (right of the up button)
    navigationActions: @Composable RowScope.() -> Unit = {},
    // 叠在顶栏左上角（返回箭头上方）的附加内容，如小字标题。
    // M3 的 title 槽被导航按钮挤开、又被右侧菜单夹住，标题很容易被省略号截断，
    // 所以这里改用覆盖层渲染：起点与返回箭头图标左边缘对齐，右侧按菜单实测宽度避让。
    navigationUnderTitle: @Composable (() -> Unit)? = null,
    // Menu
    actions: @Composable RowScope.() -> Unit = {},
    // Action mode
    isActionMode: Boolean = false,
    onCancelActionMode: () -> Unit = {},

    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    Box(
        modifier = modifier,
    ) {
        TopAppBar(
            navigationIcon = {
                if (isActionMode) {
                    IconButton(onClick = onCancelActionMode) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(MR.strings.action_cancel),
                        )
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        navigateUp?.let {
                            IconButton(onClick = it) {
                                UpIcon(navigationIcon = navigationIcon)
                            }
                        }
                        navigationActions()
                    }
                }
            },
            title = titleContent,
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = backgroundColor ?: MaterialTheme.colorScheme.surfaceColorAtElevation(
                    elevation = if (isActionMode) 3.dp else 0.dp,
                ),
            ),
            scrollBehavior = scrollBehavior,
        )
        if (navigationUnderTitle != null) {
            Box(
                modifier = Modifier
                    // 放在图标下方那条空白带里：按钮 48dp 内的 24dp 图标居中，图标底边到
                    // 顶栏底边还剩约 20dp。两行时会顶到图标，单行则刚好嵌得进去。
                    .align(Alignment.BottomCenter)
                    // 不需要 statusBarsPadding：本 Box 总高是「状态栏 + 64dp」，BottomCenter
                    // 对齐的就是 64dp 顶栏自身底边，再补一次反而会把字顶上去。
                    .fillMaxWidth()
                    // 居中排布，左右留对称边距。注意 Modifier.padding 各重载彼此独立，
                    // 不能把 horizontal 和 top 混着传。Box 默认按 TopStart 摆放内容，
                    // 这里显式居中，小标题才会落在整条顶栏的中线上。
                    .padding(start = 16.dp, end = 16.dp, bottom = 2.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                navigationUnderTitle()
            }
        }
    }
}

/**
 * 小箭头：提示「这块标题是可以点击进入下一层的」（如从清单、阅读器跳到漫画详情）。
 *
 * 纯视觉提示，所以不设 contentDescription、不额外占无障碍节点。它必须放在可跳转区域
 * **内部**的最右侧（紧贴该区域的右边缘），因此不等于整条顶栏的最右边——阅读器那里
 * 再往右还有收藏等按钮。点击由所在容器统一响应，箭头本身不单独处理点击。
 */
@Composable
fun TitleOpenHint(
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
        contentDescription = null,
        // 只占 14dp：既能被看见，又不挤压标题本身的可用宽度。
        modifier = modifier
            .size(TITLE_OPEN_HINT_SIZE)
            .secondaryItemAlpha(),
    )
}

private val TITLE_OPEN_HINT_SIZE = 14.dp

@Composable
fun AppBarTitle(
    title: String?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClickTitle: (() -> Unit)? = null,
) {
    // 标题与副标题属于同一个跳转入口，所以点击落在整块文字上，而不是只压在标题那行。
    // 箭头也在这个可跳转区域内，一起成为这块区域的右端。
    Row(
        modifier = if (onClickTitle != null) {
            // 可跳转标题占满整块白色区域（导航按钮右侧到右侧 action 之间），点击落在
            // 整个区域内，而不是只压在文字宽度上。
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClickTitle)
        } else {
            modifier
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // 文字列吃满剩余宽度，把箭头固定在可跳转区域的右端（即分享按钮左侧），
        // 无论标题长短，箭头位置都保持一致。
        Column(modifier = Modifier.weight(1f)) {
            title?.let {
                Text(
                    text = it,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.marqueeTitle(),
                )
            }
        }
        if (onClickTitle != null) {
            TitleOpenHint()
        }
    }
}

@Composable
fun AppBarActions(
    actions: List<AppBar.AppBarAction>,
) {
    var showMenu by remember { mutableStateOf(false) }

    actions.filterNot { it is AppBar.OverflowAction }.forEach { action ->
        when (action) {
            is AppBar.Action -> AppBarActionButton(action)
            is AppBar.MenuAction -> {
                var expanded by remember(action.title) { mutableStateOf(false) }
                Box {
                    AppBarActionButton(
                        action = AppBar.Action(
                            title = action.title,
                            icon = action.icon,
                            iconTint = action.iconTint,
                            onClick = { expanded = true },
                            enabled = action.enabled,
                        ),
                    )
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        offset = DpOffset(0.dp, 0.dp),
                    ) {
                        action.content.invoke(this, { expanded = false })
                    }
                }
            }
            is AppBar.OverflowAction -> Unit
        }
    }

    val overflowActions = actions.filterIsInstance<AppBar.OverflowAction>()
    if (overflowActions.isNotEmpty()) {
        Box {
            TooltipBox(
                positionProvider = rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                tooltip = {
                    PlainTooltip {
                        Text(stringResource(MR.strings.action_menu_overflow_description))
                    }
                },
                state = rememberTooltipState(),
                focusable = false,
            ) {
                IconButton(
                    onClick = { showMenu = !showMenu },
                ) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = stringResource(MR.strings.action_menu_overflow_description),
                    )
                }
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                offset = DpOffset(0.dp, 0.dp),
                minWidth = 0.dp,
            ) {
                overflowActions.forEach { action ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = action.title,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        },
                        onClick = {
                            action.onClick()
                            showMenu = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun AppBarActionButton(action: AppBar.Action) {
    TooltipBox(
        positionProvider = rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = {
            PlainTooltip {
                Text(action.title)
            }
        },
        state = rememberTooltipState(),
        focusable = false,
    ) {
        IconButton(
            onClick = action.onClick,
            enabled = action.enabled,
        ) {
            Icon(
                imageVector = action.icon,
                tint = action.iconTint ?: LocalContentColor.current,
                contentDescription = action.title,
            )
        }
    }
}

/**
 * @param searchEnabled Set to false if you don't want to show search action.
 * @param searchQuery If null, use normal toolbar.
 * @param placeholderText If null, [MR.strings.action_search_hint] is used.
 */
@Composable
fun SearchToolbar(
    searchQuery: String?,
    onChangeSearchQuery: (String?) -> Unit,
    modifier: Modifier = Modifier,
    titleContent: @Composable () -> Unit = {},
    navigateUp: (() -> Unit)? = null,
    searchEnabled: Boolean = true,
    placeholderText: String? = null,
    historyKey: String? = null,
    onSearch: (String) -> Unit = {},
    onClickCloseSearch: () -> Unit = { onChangeSearchQuery(null) },
    actions: @Composable RowScope.() -> Unit = {},
    // Action mode: while a selection is active the search field steps aside for the counter and
    // the selection actions, the same way the non-searchable [AppBar] does.
    actionModeCounter: Int = 0,
    onCancelActionMode: () -> Unit = {},
    actionModeActions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val focusRequester = remember { FocusRequester() }
    val searchHistoryStore = rememberSearchHistoryStore()
    var historyExpanded by remember(historyKey) { mutableStateOf(false) }
    val isActionMode by remember(actionModeCounter) {
        derivedStateOf { actionModeCounter > 0 }
    }

    AppBar(
        modifier = modifier,
        titleContent = {
            if (isActionMode) return@AppBar AppBarTitle(actionModeCounter.toString())
            if (searchQuery == null) return@AppBar titleContent()

            val keyboardController = LocalSoftwareKeyboardController.current
            val focusManager = LocalFocusManager.current

            val searchAndClearFocus: (String) -> Unit = f@{ query ->
                if (query.isBlank()) return@f
                historyKey?.let { key -> searchHistoryStore?.add(key, query) }
                onSearch(query)
                historyExpanded = false
                focusManager.clearFocus()
                keyboardController?.hide()
            }

            Box(modifier = Modifier.fillMaxWidth()) {
                BasicTextField(
                    value = searchQuery,
                    onValueChange = onChangeSearchQuery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged {
                            historyExpanded = it.isFocused
                            if (!it.isFocused && searchQuery.isNotBlank()) {
                                historyKey?.let { key -> searchHistoryStore?.add(key, searchQuery) }
                            }
                        }
                        .runOnEnterKeyPressed(action = { searchAndClearFocus(searchQuery) })
                        .showSoftKeyboard(remember { searchQuery.isEmpty() })
                        .clearFocusOnSoftKeyboardHide(),
                    textStyle = MaterialTheme.typography.titleMedium.copy(
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Normal,
                        fontSize = 18.sp,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { searchAndClearFocus(searchQuery) }),
                    singleLine = true,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onBackground),
                    visualTransformation = visualTransformation,
                    interactionSource = interactionSource,
                    decorationBox = { innerTextField ->
                        TextFieldDefaults.DecorationBox(
                            value = searchQuery,
                            innerTextField = innerTextField,
                            enabled = true,
                            singleLine = true,
                            visualTransformation = visualTransformation,
                            interactionSource = interactionSource,
                            placeholder = {
                                Text(
                                    modifier = Modifier.secondaryItemAlpha(),
                                    text = (placeholderText ?: stringResource(MR.strings.action_search_hint)),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Normal,
                                    ),
                                )
                            },
                            container = {},
                        )
                    },
                )

                SearchHistoryDropdown(
                    historyKey = historyKey,
                    query = searchQuery,
                    expanded = historyExpanded,
                    onDismissRequest = { historyExpanded = false },
                    onSelect = { query ->
                        onChangeSearchQuery(query)
                        searchAndClearFocus(query)
                    },
                )
            }
        },
        navigateUp = if (searchQuery == null) navigateUp else onClickCloseSearch,
        actions = {
            // 多选态下顶栏要换成批量操作（删除、移除），搜索与整表清空都不该再出现。
            // 这里必须自己分流：AppBar 只认 actions，actionModeActions 得由本函数展开。
            if (isActionMode) {
                actionModeActions()
                return@AppBar
            }

            key("search") {
                val onClick = { onChangeSearchQuery("") }

                if (!searchEnabled) {
                    // Don't show search action
                } else if (searchQuery == null) {
                    TooltipBox(
                        positionProvider = rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                        tooltip = {
                            PlainTooltip {
                                Text(stringResource(MR.strings.action_search))
                            }
                        },
                        state = rememberTooltipState(),
                        focusable = false,
                    ) {
                        IconButton(
                            onClick = onClick,
                        ) {
                            Icon(
                                Icons.Outlined.Search,
                                contentDescription = stringResource(MR.strings.action_search),
                            )
                        }
                    }
                } else if (searchQuery.isNotEmpty()) {
                    TooltipBox(
                        positionProvider = rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                        tooltip = {
                            PlainTooltip {
                                Text(stringResource(MR.strings.action_reset))
                            }
                        },
                        state = rememberTooltipState(),
                        focusable = false,
                    ) {
                        IconButton(
                            onClick = {
                                onClick()
                                focusRequester.requestFocus()
                            },
                        ) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = stringResource(MR.strings.action_reset),
                            )
                        }
                    }
                }
            }

            key("actions") { actions() }
        },
        isActionMode = isActionMode,
        onCancelActionMode = onCancelActionMode,
        scrollBehavior = scrollBehavior,
    )
}

@Composable
fun UpIcon(
    modifier: Modifier = Modifier,
    navigationIcon: ImageVector? = null,
) {
    val icon = navigationIcon
        ?: Icons.AutoMirrored.Outlined.ArrowBack
    Icon(
        imageVector = icon,
        contentDescription = stringResource(MR.strings.action_bar_up_description),
        modifier = modifier,
    )
}

sealed interface AppBar {
    sealed interface AppBarAction

    data class Action(
        val title: String,
        val icon: ImageVector,
        val iconTint: Color? = null,
        val onClick: () -> Unit,
        val enabled: Boolean = true,
    ) : AppBarAction

    data class MenuAction(
        val title: String,
        val icon: ImageVector,
        val iconTint: Color? = null,
        val enabled: Boolean = true,
        val content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
    ) : AppBarAction

    data class OverflowAction(
        val title: String,
        val onClick: () -> Unit,
    ) : AppBarAction
}
