package eu.kanade.presentation.audio

/**
 * The queue's work rows are an accordion: opening one closes the others.
 *
 * State is kept as two explicit sets rather than one "expanded" set, because a row nobody has
 * touched has to fall back to following the current track. That fallback is exactly why the other
 * rows must be written into [collapsedSections] when one opens - just dropping them from
 * [expandedSections] would let a previously untouched row spring back open when it becomes current.
 *
 * Kept free of Android/Compose types so the rule can be exercised by a plain JVM unit test.
 */
object AudioQueueExpansion {

    /**
     * State after the user toggles [key].
     *
     * Opening keeps only [key] expanded and explicitly collapses every other live key. Collapsing
     * leaves the other rows untouched: an accordion is allowed to be fully closed, and closing one
     * row should not reopen something the user had shut earlier.
     */
    fun toggleSection(
        key: String,
        expanded: Boolean,
        liveKeys: Collection<String>,
        expandedSections: Set<String>,
        collapsedSections: Set<String>,
    ): ExpansionState {
        if (!expanded) {
            return ExpansionState(
                expanded = expandedSections - key,
                collapsed = collapsedSections + key,
            )
        }
        val others = (liveKeys - key).toHashSet()
        return ExpansionState(
            expanded = setOf(key),
            // Keep any already-closed rows closed, and add every other live row so none of them
            // falls back to "follow the current track" and springs open again.
            collapsed = collapsedSections + others - key,
        )
    }

    /**
     * Same accordion rule for the folder rows inside one work. Other works' folders are left
     * alone here: they are not visible at the same time, and each work remembers its own folder
     * state across visits.
     */
    fun toggleFolder(
        key: String,
        expanded: Boolean,
        liveKeysInWork: Collection<String>,
        expandedFolders: Set<String>,
        collapsedFolders: Set<String>,
    ): ExpansionState {
        if (!expanded) {
            return ExpansionState(
                expanded = expandedFolders - key,
                collapsed = collapsedFolders + key,
            )
        }
        val others = (liveKeysInWork - key).toHashSet()
        return ExpansionState(
            expanded = (expandedFolders - others) + key,
            collapsed = collapsedFolders - key + others,
        )
    }

    /**
     * Whether a row with no explicit state should follow the current track.
     *
     * Once any row is explicitly open the accordion is "in use": a newly added row (and therefore
     * absent from both sets) must stay closed even if it becomes current, or two rows would end up
     * open at once. With nothing explicitly open, the untouched rows keep the original default.
     */
    fun followsCurrentTrack(expanded: Set<String>, key: String, currentKey: String?): Boolean {
        return expanded.isEmpty() && key == currentKey
    }

    data class ExpansionState(
        val expanded: Set<String>,
        val collapsed: Set<String>,
    )
}
