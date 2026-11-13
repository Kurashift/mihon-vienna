package eu.kanade.tachiyomi.data.undo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UndoPillServiceTest {

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `undo runs the action and reports it`() = runTest {
        val service = UndoPillService()
        var undone = false

        val result = service.show("已删除 1 条记录") { undone = true }

        assertEquals("已删除 1 条记录", service.pill.value?.message)
        service.undo()

        assertTrue(result.await())
        assertTrue(undone)
        assertNull(service.pill.value)
    }

    @Test
    fun `a replaced pill is not undone`() = runTest {
        val service = UndoPillService()
        var firstUndone = false

        val first = service.show("第一条") { firstUndone = true }
        service.show("第二条") {}

        // The offer for the first action is gone the moment another one takes the pill: leaving it
        // undone is the only safe reading, since the user never tapped it.
        assertFalse(first.await())
        assertFalse(firstUndone)
        assertEquals("第二条", service.pill.value?.message)
    }

    @Test
    fun `dismissing leaves the change in place`() = runTest {
        val service = UndoPillService()
        var undone = false

        val result = service.show("已删除 1 条记录") { undone = true }
        service.dismiss()

        assertFalse(result.await())
        assertFalse(undone)
        assertNull(service.pill.value)
    }
}
