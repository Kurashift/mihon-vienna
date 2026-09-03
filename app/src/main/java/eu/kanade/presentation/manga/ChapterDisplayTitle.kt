package eu.kanade.presentation.manga

import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * 漫画详情页一个篇目要显示的名字。
 *
 * @param title 主行文字：按显示模式取篇目号、译名或原名。
 * @param originalTitle 次级原名，仅在「译名与原名」模式且主行确实显示的是译名时非空；其余模式与空间不足的布局为 null。
 */
internal data class ChapterDisplayTitle(
    val title: String,
    val originalTitle: String?,
)

/**
 * 详情页篇目显示名的唯一决策点，列表与网格都走这里。
 *
 * 两者此前各写一份 `when`，v2.2.0 重做详情页操作栏时网格那一份被改动后静默漂移，
 * 把「译名与原名」模式的原名副行丢成了 `subtitle = null`，而列表那份看起来仍然正常。
 * 收敛成一处后，任一分支再被改动都会同时影响两个布局，不会再出现只坏一个的情况。
 *
 * @param displayMode 该漫画的篇目显示模式，见 [Manga.displayMode]。
 * @param showOriginalTitle 该布局是否放得下原名副行。网格三列格子只够放主行，传 false 让
 *   「译名与原名」退化为与「仅译名」相同的显示，**不改变用户选的设置值**，切回列表即恢复。
 * @param chapterNumberLabel 篇目号文案，**只在篇目号模式下才需要非 null**，由调用方按需构造。
 *   不用 lambda 是因为它内部要调用 `stringResource`，而可组合函数无法在普通 lambda 里调用；
 *   放在调用方按需构造同样能做到「非篇目号模式不花这个成本」——大库里对每个篇目都做一次
 *   数字格式化是真的有开销。
 */
internal fun resolveChapterDisplayTitle(
    chapter: Chapter,
    displayMode: Long,
    showOriginalTitle: Boolean,
    chapterNumberLabel: String?,
): ChapterDisplayTitle {
    val title = when (displayMode) {
        Manga.CHAPTER_DISPLAY_NUMBER -> chapterNumberLabel ?: chapter.name
        Manga.CHAPTER_DISPLAY_TRANSLATED_ONLY,
        Manga.CHAPTER_DISPLAY_TRANSLATED_AND_ORIGINAL,
        -> chapter.translatedNameOrNull ?: chapter.name
        else -> chapter.name
    }
    // 没有译名时主行本身就回退成了原名，此时再挂一行原名只是重复。
    val originalTitle = chapter.name.takeIf {
        showOriginalTitle &&
            displayMode == Manga.CHAPTER_DISPLAY_TRANSLATED_AND_ORIGINAL &&
            it != title
    }
    return ChapterDisplayTitle(title, originalTitle)
}
