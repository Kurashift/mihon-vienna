package eu.kanade.presentation.mylists

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * 清单里一个篇目要显示的名字。
 *
 * @param primary 主行文字：按设置取译名或原名。
 * @param secondary 「译名与原名」模式下的副行原名，其余模式为 null。
 */
data class MyListChapterTitle(
    val primary: String,
    val secondary: String?,
)

/**
 * 与漫画详情页「章节标题」设置同一套算法的显示名。
 *
 * 详情页把本地篇目的标题显示统一改成译名（或译名与原名）后，三个清单跟着优先显示译名；
 * 没有译名的篇目照旧回退到原名，不会出现空标题。
 *
 * 「篇目号」模式不在这里复刻：详情页显示的「第 N 话」在清单里反而更难认，
 * 清单的定位是快速找到具体文件，该模式下继续显示原名。
 */
fun myListChapterTitle(
    chapterName: String,
    translatedName: String?,
    displayMode: Long,
): MyListChapterTitle {
    val translated = translatedName?.trim()?.takeIf { it.isNotEmpty() }
    val primary = when (displayMode) {
        Manga.CHAPTER_DISPLAY_TRANSLATED_ONLY,
        Manga.CHAPTER_DISPLAY_TRANSLATED_AND_ORIGINAL,
        -> translated ?: chapterName
        else -> chapterName
    }
    val secondary = chapterName.takeIf {
        displayMode == Manga.CHAPTER_DISPLAY_TRANSLATED_AND_ORIGINAL && primary != it
    }
    return MyListChapterTitle(primary, secondary)
}

/**
 * 本地库统一的篇目标题显示模式。
 *
 * 三个清单里的篇目都是本地篇目，详情页改这个设置时写的正是同一份偏好
 * （见 `SetMangaDefaultChapterFlags` / `MangaViewModel.setDisplayMode`），
 * 所以清单只要读它，就能跟着详情页的选择变，不必各自再存一份。
 */
@Composable
fun rememberLocalChapterDisplayMode(): Long {
    val preference = remember { Injekt.get<LibraryPreferences>().localChapterDisplayMode }
    return preference.changes().collectAsState(initial = preference.get()).value
}

/**
 * 清单搜索的单条判定：漫画名、篇目原名、篇目译名任一命中即算命中。
 *
 * 中英日文混搜无需特殊处理——`contains` 按码点匹配，日文与中文没有大小写之分，
 * `ignoreCase` 只额外让英文名不再受大小写影响。
 */
fun myListEntryMatchesQuery(
    query: String,
    mangaTitle: String,
    chapterName: String,
    translatedName: String?,
): Boolean {
    return mangaTitle.contains(query, ignoreCase = true) ||
        chapterName.contains(query, ignoreCase = true) ||
        (translatedName?.contains(query, ignoreCase = true) ?: false)
}

/**
 * 搜索词归一化。空串表示「没有筛选」，交给调用方直接跳过整套过滤。
 */
fun myListSearchQuery(raw: String?): String = raw?.trim().orEmpty()
