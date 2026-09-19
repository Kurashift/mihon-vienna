package eu.kanade.presentation.category.components

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.domain.category.model.Category

/**
 * Unchecking every shelf is how the local library takes works off the library, standing in for a
 * separate button. These cases pin down when that reading is allowed, because getting it wrong
 * drops works off the library on a bare confirm.
 */
class CategorySelectionLeavesLibraryTest {

    private val named = Category(id = 5L, name = "全彩", order = 1L, flags = 0L)
    private val other = Category(id = 6L, name = "黑白", order = 2L, flags = 0L)
    private val default = Category(id = Category.UNCATEGORIZED_ID, name = "", order = -1L, flags = 0L)

    @Test
    fun `everything unchecked means leaving the library`() {
        assertTrue(
            selectionLeavesLibrary(
                listOf(
                    CheckboxState.State.None(default),
                    CheckboxState.State.None(named),
                ),
            ),
        )
    }

    @Test
    fun `a checked shelf keeps the work on the library`() {
        assertFalse(
            selectionLeavesLibrary(
                listOf(
                    CheckboxState.State.None(default),
                    CheckboxState.State.Checked(named),
                ),
            ),
        )
    }

    @Test
    fun `the default shelf alone is still the library`() {
        // Checking "default" is how a work stays on the library without a category, so it must
        // not read as "no shelf at all".
        assertFalse(
            selectionLeavesLibrary(
                listOf(
                    CheckboxState.State.Checked(default),
                    CheckboxState.State.None(named),
                ),
            ),
        )
    }

    @Test
    fun `a mixed batch does not leave the library on a bare confirm`() {
        // Works filed differently open with half-checked rows. Treating that as "nothing picked"
        // would take the whole batch off the library without the user touching anything.
        assertFalse(
            selectionLeavesLibrary(
                listOf(
                    CheckboxState.TriState.Exclude(named),
                    CheckboxState.State.None(other),
                ),
            ),
        )
    }

    @Test
    fun `a half-checked default shelf is not an empty choice`() {
        assertFalse(
            selectionLeavesLibrary(
                listOf(
                    CheckboxState.TriState.Exclude(default),
                    CheckboxState.State.None(named),
                ),
            ),
        )
    }

    @Test
    fun `an empty selection is not a decision`() {
        assertFalse(selectionLeavesLibrary(emptyList()))
    }

    @Test
    fun `an included category counts even beside unchecked ones`() {
        assertFalse(
            selectionLeavesLibrary(
                listOf(
                    CheckboxState.State.None(default),
                    CheckboxState.TriState.Include(named),
                    CheckboxState.State.None(other),
                ),
            ),
        )
    }
}
