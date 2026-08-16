package eu.kanade.tachiyomi.ui.reader

internal object RandomReaderHistory {

    data class Entry(
        val mangaId: Long,
        val chapterId: Long,
        val pageIndex: Int,
        val returnDirection: String,
    )

    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun push(entry: Entry) {
        entries.addLast(entry)
        while (entries.size > MAX_ENTRIES) {
            entries.removeFirst()
        }
    }

    @Synchronized
    fun pop(): Entry? = entries.removeLastOrNull()

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    internal fun size(): Int = entries.size

    private const val MAX_ENTRIES = 20
}
