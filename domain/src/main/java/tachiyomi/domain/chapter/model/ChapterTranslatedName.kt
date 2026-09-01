package tachiyomi.domain.chapter.model

/**
 * A chapter's manually assigned Chinese translated name (中文译名), tied to the manga it belongs to.
 *
 * Only non-empty translated names are ever produced; chapters without one are omitted so the
 * library search index stays sparse for the overwhelmingly common case of libraries that use no
 * translations at all.
 */
data class ChapterTranslatedName(
    val mangaId: Long,
    val name: String,
)
