package eu.kanade.presentation.mylists

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.util.relativeTimeSpanString
import eu.kanade.presentation.util.rememberResourceBitmapPainter
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.util.secondaryItemAlpha
import java.io.IOException

// 三个个人清单（标记清单 / 好本子清单 / 已读复查）共享的视觉与交互件。
//
// 这里只收敛「三个页面本该一致」的部分：间距、封面的圆角与占位、字号色阶、
// 多选勾选圈、时间格式与导出文本。各页面仍保留自己的主体形态
// （两级列表 / 单页网格），不做强行同构。

// region 视觉 token

/** 行与卡片的水平外边距。 */
val MyListHorizontalPadding = 16.dp

/** 行的垂直内边距。 */
val MyListRowVerticalPadding = 12.dp

/** 封面与右侧文字之间的间距。 */
val MyListContentGap = 12.dp

/** 列表页封面宽度，一级漫画行与二级篇目行共用。 */
val MyListCoverWidth = 48.dp

/** 篇目封面宽高比，与已读复查的网格卡片保持一致。 */
const val MY_LIST_COVER_ASPECT_RATIO = 2f / 3f

/** 漫画封面宽高比（漫画行沿用项目内的方封惯例）。 */
const val MY_LIST_MANGA_COVER_ASPECT_RATIO = 1f

/** 网格卡片之间的水平间距。 */
val MyListGridHorizontalSpacing: Dp = 6.dp

// endregion

/**
 * 三个清单统一的封面渲染：固定的圆角、占位色与加载失败兜底。
 *
 * @param model 交给 Coil 的模型，可以是 `MangaCover` 也可以是 `LocalChapterCover`；
 *              为 null 时直接显示占位图标，不发请求。
 * @param aspectRatio [MY_LIST_COVER_ASPECT_RATIO]（篇目）或 [MY_LIST_MANGA_COVER_ASPECT_RATIO]（漫画）。
 * @param onClick 封面自身的点击。漫画行里点封面是「打开漫画」、点整行是「下钻到篇目」，
 *                两个目标不同，所以点击要挂在封面上而不是整行。传 null 表示封面不单独响应点击。
 */
@Composable
fun MyListCover(
    model: Any?,
    contentDescription: String,
    modifier: Modifier = Modifier,
    aspectRatio: Float = MY_LIST_COVER_ASPECT_RATIO,
    placeholderIcon: ImageVector = Icons.Outlined.PlayArrow,
    onClick: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .aspectRatio(aspectRatio)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(CoverPlaceholderColor)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (model == null) {
            Icon(
                imageVector = placeholderIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.secondaryItemAlpha(),
            )
        } else {
            AsyncImage(
                model = model,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                placeholder = ColorPainter(CoverPlaceholderColor),
                error = rememberResourceBitmapPainter(id = R.drawable.cover_error),
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/**
 * 多选态的勾选圈。选中为实心勾，未选中为空心圈。
 */
@Composable
fun MyListSelectionIndicator(
    selected: Boolean,
    modifier: Modifier = Modifier,
    tint: Color? = null,
) {
    Icon(
        imageVector = if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
        contentDescription = stringResource(
            if (selected) MR.strings.selected else MR.strings.not_selected,
        ),
        tint = tint ?: if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outline
        },
        modifier = modifier,
    )
}

/**
 * 统一的时间文案。走相对时间，跟随「外观」里的相对时间开关，
 * 替换原先硬编码的 `SimpleDateFormat("yyyy-MM-dd HH:mm")`。
 */
@Composable
@ReadOnlyComposable
fun formatListTime(epochMillis: Long): String = relativeTimeSpanString(epochMillis)

/**
 * 统一的空态：把 contentPadding 交给 [EmptyScreen]，避免各页面重复处理。
 */
@Composable
fun MyListEmptyState(
    stringRes: StringResource,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    EmptyScreen(
        stringRes = stringRes,
        modifier = modifier.padding(contentPadding),
    )
}

// region 导出文本

/**
 * 导出时的一组：一个漫画下的若干条目。
 *
 * @param entries 已经格式化好的每行文本，便于各清单带上自己的附加信息（如标记时间）。
 */
data class MyListShareGroup(
    val mangaTitle: String,
    val entries: List<String>,
)

/**
 * 三个清单共用的纯文本导出格式：标题头 + 按漫画分组的缩进列表。
 *
 * @param header 已本地化的标题行，形如「标记清单：共 12 条」。
 */
fun buildMyListShareText(
    header: String,
    groups: List<MyListShareGroup>,
): String {
    val total = groups.sumOf { it.entries.size }
    if (total == 0) return ""
    return buildString {
        append(header).append('\n')
        groups.forEach { group ->
            append('\n').append('【').append(group.mangaTitle).append('】').append('\n')
            group.entries.forEach { entry ->
                append("  - ").append(entry).append('\n')
            }
        }
    }.trimEnd()
}

// endregion

// region 导出文件

/**
 * 三个清单统一的导出入口：弹出系统保存框，把拼好的文本写成 .txt 文件，而不是
 * 直接走「分享」把纯文本交给其它应用。返回一个可直接挂到右上角按钮上的点击回调。
 *
 * @param filename 建议的文件名，需带 .txt 后缀。用户在保存框里仍可改名。
 * @param text 要写进文件的内容。为空时回调不会启动保存框，与按钮的禁用态保持一致。
 */
@Composable
fun rememberMyListExportLauncher(
    filename: String,
    text: String,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { writeMyListExportFile(context, uri, text) }
                .onSuccess { context.toast(MR.strings.library_exported) }
                .onFailure { error ->
                    logcat(LogPriority.ERROR, error)
                    context.toast(MR.strings.my_lists_export_failed)
                }
        }
    }
    return { if (text.isNotEmpty()) launcher.launch(filename) }
}

private suspend fun writeMyListExportFile(context: Context, uri: Uri, text: String) {
    withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            stream.write(text.toByteArray())
        } ?: throw IOException("Cannot open output stream for $uri")
    }
}

// endregion

/** 封面占位底色，与 `MangaCover` 保持一致。 */
private val CoverPlaceholderColor = Color(0x1F888888)
