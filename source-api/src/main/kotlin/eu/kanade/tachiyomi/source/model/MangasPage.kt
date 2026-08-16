package eu.kanade.tachiyomi.source.model

class MangasPage(val mangas: List<SManga>, val hasNextPage: Boolean) {

    /** Optional positional counts for sources that know the complete result size. */
    var itemsBefore: Int = -1
    var itemsAfter: Int = -1

    @Deprecated("MangasPage is now a regular class")
    operator fun component1(): List<SManga> = mangas

    @Deprecated("MangasPage is now a regular class")
    operator fun component2(): Boolean = hasNextPage

    @Deprecated("MangasPage is now a regular class")
    fun copy(
        mangas: List<SManga> = this.mangas,
        hasNextPage: Boolean = this.hasNextPage,
    ): MangasPage = MangasPage(mangas = mangas, hasNextPage = hasNextPage).also {
        it.itemsBefore = itemsBefore
        it.itemsAfter = itemsAfter
    }
}
