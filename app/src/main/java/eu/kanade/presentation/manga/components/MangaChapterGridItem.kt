package eu.kanade.presentation.manga.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.data.download.model.Download
import me.saket.swipe.SwipeableActionsBox
import me.saket.swipe.rememberSwipeableActionsState
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.util.selectedBackground
import kotlin.math.abs
import kotlin.math.sign

private const val LIFT_SCALE = 0.05f
private val LIFT_ELEVATION = 8.dp
private val COVER_SHAPE = RoundedCornerShape(4.dp)

/**
 * 已读与未读之间的淡入淡出时长。快滑标记会连带刷新整格内容，封面与标题若是硬切，松手那一帧
 * 会看到整格闪一下，看着像重新加载；这里让它们走一段过渡，把状态变化藏进回弹里。
 */
private const val READ_FADE_MILLIS = 180

/** Kept bright enough to read as red against the dark corner scrim in both themes. */
internal val GOOD_DOUJIN_HEART_COLOR = Color(0xFFF44336)

@Composable
fun MangaChapterGridItem(
    title: String,
    cover: Any,
    readProgress: String?,
    read: Boolean,
    selected: Boolean,
    bookmark: Boolean,
    goodDoujinMarked: Boolean,
    flagMarked: Boolean,
    chapterSwipeStartAction: LibraryPreferences.ChapterSwipeAction,
    chapterSwipeEndAction: LibraryPreferences.ChapterSwipeAction,
    downloadStateProvider: () -> Download.State,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onChapterSwipe: (LibraryPreferences.ChapterSwipeAction) -> Unit,
    dragging: Boolean = false,
    dragOffset: () -> Offset = { Offset.Zero },
    settleOffset: () -> Offset = { Offset.Zero },
    onTitlePlaced: ((LayoutCoordinates) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val swipeState = rememberSwipeableActionsState()
    // 抬起要跟得上手指、放下要有重量感，所以两个方向不共用一条弹簧：拿起偏硬，落下偏软。
    val lift by animateFloatAsState(
        targetValue = if (dragging) 1f else 0f,
        animationSpec = spring(
            stiffness = if (dragging) Spring.StiffnessMedium else Spring.StiffnessMediumLow,
        ),
        label = "chapterGridLift",
    )
    // 拖拽中 1:1 贴着手指走，其余时候是零。
    //
    // 这里刻意不保留任何状态：跟手偏移属于「手指下那一张卡」，不属于格子，而格子是按位置
    // 复用的。换位之后同一个格子会改渲染被挤过来的那张卡，一旦偏移还留在格子上，那张卡就
    // 会先被画在手指所在的位置，再自己滑回自己的格子——看上去正是一段绕着手指的曲线。
    //
    // 和下面的 coverAlpha 同理，取值推迟到 graphicsLayer 的 lambda 里：跟手偏移每帧都在变，
    // 在这里读成 Offset 就等于每帧把这张卡片连同封面重新组合一次。
    val followOffset = if (dragging) dragOffset else null
    // 快滑标记会连带刷新整格内容，已读外观若是硬切，松手那一帧整格会闪一下，看着像重新加载。
    // 让封面与标题一起过渡，状态变化就藏进回弹里了。
    //
    // 两个动画都只留下 State、不在组合里取值：一旦在这里读成 Float，这段过渡的每一帧都会把
    // 整张卡片连同封面重新组合一次，而封面被重新组合就会重新走一遍加载，看着就是一阵白闪。
    // 取值推迟到 graphicsLayer 的 lambda 里，每帧就只重绘、不重组。
    val coverAlpha = animateFloatAsState(
        targetValue = if (read) 0.55f else 1f,
        animationSpec = tween(durationMillis = READ_FADE_MILLIS),
        label = "chapterGridCoverAlpha",
    )
    val titleAlpha = animateFloatAsState(
        targetValue = if (read) DISABLED_ALPHA else 1f,
        animationSpec = tween(durationMillis = READ_FADE_MILLIS),
        label = "chapterGridTitleAlpha",
    )
    // 与列表模式同款的快滑动作：按住卡片横向滑动，露出的空白里显示 √/❤️，松手触发标记。
    //
    // 动作实例必须按内容缓存住，不能每次重组都新建：me.saket.swipe 把「是否已经越过阈值」
    // 记在动作实例上，实例一换它就当成一次全新的快滑，越过阈值那下的震动会跟着每次重组
    // 重放一遍，重组一密就成了连续震动。真正会改变动作外观的输入才换实例，onSwipe 走稳定
    // 引用，免得缓存住旧的回调。
    val downloadState = downloadStateProvider()
    val swipeBackground = MaterialTheme.colorScheme.primaryContainer
    val currentOnChapterSwipe by rememberUpdatedState(onChapterSwipe)
    val startSwipeAction = remember(
        chapterSwipeStartAction,
        read,
        bookmark,
        downloadState,
        goodDoujinMarked,
        swipeBackground,
    ) {
        getSwipeAction(
            action = chapterSwipeStartAction,
            read = read,
            bookmark = bookmark,
            downloadState = downloadState,
            goodDoujinMarked = goodDoujinMarked,
            background = swipeBackground,
            onSwipe = { currentOnChapterSwipe(chapterSwipeStartAction) },
        )
    }
    val endSwipeAction = remember(
        chapterSwipeEndAction,
        read,
        bookmark,
        downloadState,
        goodDoujinMarked,
        swipeBackground,
    ) {
        getSwipeAction(
            action = chapterSwipeEndAction,
            read = read,
            bookmark = bookmark,
            downloadState = downloadState,
            goodDoujinMarked = goodDoujinMarked,
            background = swipeBackground,
            onSwipe = { currentOnChapterSwipe(chapterSwipeEndAction) },
        )
    }
    // 抬起缩放与拖拽跟手必须挂在最外层节点：包了快滑盒之后，卡片拖动时连同盒内的裁切
    // 一起平移，不会被格子边界裁掉；抬起期间也仍然盖在同排邻居上面。
    val liftModifier = modifier
        // 放大后会压到同排邻居，抬起期间要盖在它们上面。
        .zIndex(if (dragging) 1f else 0f)
        .graphicsLayer {
            scaleX = 1f + LIFT_SCALE * lift
            scaleY = 1f + LIFT_SCALE * lift
            // While the finger owns the card it is pinned to the pointer, so a glide of its own
            // would only fight it. Everything it pushed aside keeps gliding into the new cell.
            val follow = followOffset?.invoke() ?: Offset.Zero
            val settle = if (dragging) Offset.Zero else settleOffset()
            translationX = follow.x + settle.x
            translationY = follow.y + settle.y
        }
    val card: @Composable (Modifier) -> Unit = { base ->
        Column(
            modifier = base
                .selectedBackground(selected)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(4.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .shadow(elevation = LIFT_ELEVATION * lift, shape = COVER_SHAPE)
                    .clip(COVER_SHAPE)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                AsyncImage(
                    model = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceContainerHighest),
                    error = ColorPainter(MaterialTheme.colorScheme.surfaceContainerHighest),
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = coverAlpha.value },
                )

                if (read || bookmark || goodDoujinMarked || flagMarked) {
                    Row(
                        modifier = Modifier
                            // 与图书馆封面一致：状态徽章靠左，进度靠右。
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.58f))
                            .padding(horizontal = 4.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 顺序与列表模式一致：已读 → 好本子 → 书签 → 旗子。右下角那颗爱心是
                        // 好本子标记，眼睛先落在它上面，所以把它排到旗子前面。
                        if (read) {
                            Icon(Icons.Filled.CheckCircle, null, Modifier.size(12.dp), tint = Color.White)
                        }
                        if (goodDoujinMarked) {
                            Icon(
                                Icons.Filled.Favorite,
                                null,
                                Modifier.size(12.dp),
                                tint = GOOD_DOUJIN_HEART_COLOR,
                            )
                        }
                        if (bookmark) {
                            Icon(Icons.Filled.Bookmark, null, Modifier.size(12.dp), tint = Color.White)
                        }
                        if (flagMarked) {
                            Icon(Icons.Filled.Flag, null, Modifier.size(12.dp), tint = Color.White)
                        }
                    }
                }

                if (readProgress != null) {
                    // 与图书馆卡片右上角完全同一个读法：黑底白字直接压在封面角上，不留一圈
                    // 内边距、也不做圆角，避免出现一个悬在封面上的小框。
                    Text(
                        text = readProgress,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .background(Color.Black.copy(alpha = 0.5f))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }

            // 三列格子很窄，名字长到十几二十几个字是常态，两行 14sp 只能装约 14 个字。
            // 这里把字号压到 11sp 并收紧行高，换出三行、约 27 个字的容量，而标题区只从
            // 40sp 长到 42sp，格子几乎不变高。
            //
            // minLines 与 maxLines 都设成 3 是刻意的：短标题也占满三行，空白仍属于标题区，
            // 于是同一排格子的标题区严丝合缝等高，整排卡片同高。只放 maxLines 会让长名
            // 格子更高、短名格子更矮，一排参差不齐。
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall.copy(lineHeight = 14.sp),
                minLines = 3,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = 2.dp, top = 5.dp, end = 2.dp)
                    // 与列表模式一致：已读时标题一起变淡。只灰封面会让整格看着还没读。
                    // 同上，走绘制层的 alpha 而不是把值读进组合里。
                    .graphicsLayer { alpha = titleAlpha.value }
                    .then(
                        if (onTitlePlaced != null) {
                            Modifier.onGloballyPositioned(onTitlePlaced)
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }
    if (startSwipeAction == null && endSwipeAction == null) {
        // 两个方向都禁用快滑时不包快滑盒，保持原来的结构。
        card(liftModifier)
    } else {
        SwipeableActionsBox(
            state = swipeState,
            // 裁切放在抬起层之内：滑动时卡片内容被限制在本格之内；拖拽跟手时整层一起
            // 平移，裁切跟着走，不影响卡片飞往其他格子。
            //
            // 裁切要写在位移之外：先整体平移再裁，卡片右侧被本格边界切掉，格子里始终
            // 是「动作块 + 卡片」铺满；反过来先裁后移，右边会空出一条。
            modifier = liftModifier
                .clipToBounds()
                // 快滑只露出一个图标的宽度就停：动作块（图标与底色）只有图标那么宽，并
                // 且跟着卡片一起走，滑过头之后格子边缘和动作块之间就会裂开一条没有底色的
                // 空白。这里把多走的距离从整个快滑盒上减掉，卡片、图标、底色作为整体停在
                // 图标刚刚露全的位置；库内部的位移照常随手指增长，松手照样按阈值触发。
                //
                // 走绘制阶段的位移而不是布局阶段的 offset：这个量每一帧都在变，放进布局会让
                // 整棵卡片子树（封面加三行标题）逐帧重新摆放，快滑就是这么被拖出顿挫的。
                // 裁切仍然写在位移之外，所以先平移后裁的次序不变。
                .graphicsLayer {
                    translationX = swipeOverflowPx(
                        offset = swipeState.offset.value,
                        maxSwipePx = swipeActionThreshold.toPx(),
                    )
                },
            startActions = listOfNotNull(startSwipeAction),
            endActions = listOfNotNull(endSwipeAction),
            swipeThreshold = swipeActionThreshold,
            backgroundUntilSwipeThreshold = MaterialTheme.colorScheme.surfaceContainerLowest,
        ) {
            card(Modifier)
        }
    }
}

/**
 * 快滑盒要往回退多少，卡片才会停在动作图标刚露全的位置。符号与 [offset] 一致，
 * 位移还在图标宽度之内时返回 0，卡片照常一比一跟手。
 */
private fun swipeOverflowPx(offset: Float, maxSwipePx: Float): Float {
    if (offset == 0f) return 0f
    val travelled = abs(offset)
    if (travelled <= maxSwipePx) return 0f
    return (maxSwipePx - travelled) * sign(offset)
}
