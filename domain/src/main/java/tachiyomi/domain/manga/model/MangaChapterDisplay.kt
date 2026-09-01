package tachiyomi.domain.manga.model

private const val LOCAL_SOURCE_ID = 0L

/**
 * Returns a copy of [this] with the library-wide local chapter display mode applied, leaving the
 * stored chapter flags untouched.
 *
 * Whether local chapters show their translated title is a library display preference rather than
 * per-manga content, and it feeds [tachiyomi.domain.chapter.service.getChapterSort]: sorting by
 * name uses the translated title only while the translated display modes are active. The manga
 * details screen syncs the preference into the database before sorting, but a manga can be reached
 * without ever opening that screen, for example when the reader is launched from a random entry or
 * the library grid, or when "next chapters" are looked up for download. Those paths would read a
 * stale display mode, order chapters by a different title than the list the user sees, and pick a
 * different "next chapter" than the one sitting next to it in the details list.
 *
 * The preference is applied to the copy used for reading instead of being written back, so
 * opening a manga never mutates the user's stored settings.
 *
 * @param localDisplayMode the library-wide display mode for local manga, see
 *   [tachiyomi.domain.library.service.LibraryPreferences.localChapterDisplayMode].
 */
fun Manga.withLocalChapterDisplayMode(localDisplayMode: Long): Manga {
    if (source != LOCAL_SOURCE_ID) return this
    if (displayMode == localDisplayMode) return this
    return copy(
        chapterFlags = chapterFlags and Manga.CHAPTER_DISPLAY_MASK.inv() or
            (localDisplayMode and Manga.CHAPTER_DISPLAY_MASK),
    )
}

/**
 * Returns whether a chapter's parsed number is meaningful enough to show for a manga of [sourceId].
 *
 * Local chapters only carry a chapter number because one was parsed out of the file name, so a
 * name like "vol1-17.5" ends up as "第 1.17 篇". The chapter list hides that number unless the
 * user explicitly switched local chapters to the number display mode; every other surface that
 * prints a chapter number (history, the new chapters notification) must follow the same rule
 * instead of printing whatever was parsed out of the file name.
 *
 * @param localDisplayMode the library-wide display mode for local manga, see
 *   [tachiyomi.domain.library.service.LibraryPreferences.localChapterDisplayMode].
 */
fun shouldDisplayChapterNumber(sourceId: Long, localDisplayMode: Long): Boolean {
    if (sourceId != LOCAL_SOURCE_ID) return true
    return localDisplayMode and Manga.CHAPTER_DISPLAY_MASK == Manga.CHAPTER_DISPLAY_NUMBER
}
