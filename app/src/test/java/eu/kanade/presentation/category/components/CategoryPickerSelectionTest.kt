package eu.kanade.presentation.category.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.domain.category.model.Category

/**
 * The picker files works onto shelves; taking them off the shelf is the library row's own button,
 * so a pick that names no shelf at all is not something it can carry out. These cases pin down
 * when the confirm has to go dead, because getting it wrong either blocks a real filing or lets a
 * bare confirm file a batch of works nowhere.
 */
class CategorySelectionPicksNothingTest {

    private val named = Category(id = 5L, name = "全彩", order = 1L, flags = 0L)
    private val other = Category(id = 6L, name = "黑白", order = 2L, flags = 0L)
    private val default = Category(id = Category.UNCATEGORIZED_ID, name = "", order = -1L, flags = 0L)

    @Test
    fun `every shelf unchecked picks nothing`() {
        assertTrue(
            selectionPicksNothing(
                listOf(
                    CheckboxState.State.None(default),
                    CheckboxState.State.None(named),
                ),
            ),
        )
    }

    @Test
    fun `a checked shelf is a pick`() {
        assertFalse(
            selectionPicksNothing(
                listOf(
                    CheckboxState.State.None(default),
                    CheckboxState.State.Checked(named),
                ),
            ),
        )
    }

    @Test
    fun `the default shelf alone is a pick`() {
        // Checking "default" is how a work stays on the library without a category, so it is a
        // real answer and must keep the confirm live.
        assertFalse(
            selectionPicksNothing(
                listOf(
                    CheckboxState.State.Checked(default),
                    CheckboxState.State.None(named),
                ),
            ),
        )
    }

    @Test
    fun `a mixed batch is not an empty pick`() {
        // Works filed differently open with half-checked rows. Reading that as "nothing picked"
        // would disable the confirm on a selection the user never touched.
        assertFalse(
            selectionPicksNothing(
                listOf(
                    CheckboxState.TriState.Exclude(named),
                    CheckboxState.State.None(other),
                ),
            ),
        )
    }

    @Test
    fun `a half-checked default shelf is a pick`() {
        assertFalse(
            selectionPicksNothing(
                listOf(
                    CheckboxState.TriState.Exclude(default),
                    CheckboxState.State.None(named),
                ),
            ),
        )
    }

    @Test
    fun `an empty selection is not a pick`() {
        // No rows at all means the dialog shows its "no categories yet" branch instead, so this
        // never reaches the confirm; it must not read as "the user picked nothing" either.
        assertFalse(selectionPicksNothing(emptyList()))
    }

    @Test
    fun `an included category counts even beside unchecked ones`() {
        assertFalse(
            selectionPicksNothing(
                listOf(
                    CheckboxState.State.None(default),
                    CheckboxState.TriState.Include(named),
                    CheckboxState.State.None(other),
                ),
            ),
        )
    }
}

/**
 * The default shelf is the absence of a category row, so confirming the picker with only that row
 * checked must not write its id: a stored row pointing at the default shelf would make a work filed
 * nowhere report a category, which is the state the default shelf is defined as.
 */
class CategoryWritableIdsTest {

    private val named = Category(id = 5L, name = "全彩", order = 1L, flags = 0L)

    @Test
    fun `the default shelf id is never written`() {
        assertTrue(writableCategoryIds(listOf(Category.UNCATEGORIZED_ID)).isEmpty())
    }

    @Test
    fun `named categories pass through`() {
        assertEquals(listOf(named.id), writableCategoryIds(listOf(named.id)))
    }

    @Test
    fun `the default shelf is dropped from a mixed pick`() {
        assertEquals(listOf(named.id), writableCategoryIds(listOf(Category.UNCATEGORIZED_ID, named.id)))
    }

    @Test
    fun `an empty pick writes nothing`() {
        assertTrue(writableCategoryIds(emptyList()).isEmpty())
    }
}
