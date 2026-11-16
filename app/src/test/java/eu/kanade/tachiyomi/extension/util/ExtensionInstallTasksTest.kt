package eu.kanade.tachiyomi.extension.util

import eu.kanade.tachiyomi.extension.model.InstallStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class ExtensionInstallTasksTest {

    @Test
    fun `cancel terminates observation and cannot turn into install`() = runTest {
        val cancelled = mutableListOf<Long>()
        val tasks = ExtensionInstallTasks(backgroundScope, { cancelled.add(it) }, { throw it })
        var installed = false
        val flow = tasks.start("test") {
            it.step.value = InstallStep.Downloading
            CompletableDeferred<Unit>().await()
            installed = true
        }
        val observed = mutableListOf<InstallStep>()
        val observer = launch { flow.toList(observed) }
        runCurrent()
        tasks.cancel("test")
        runCurrent()
        assertFalse(installed)
        assertTrue(observer.isCompleted)
        assertEquals(InstallStep.Idle, observed.last())
        assertTrue(cancelled.isNotEmpty())
    }

    @Test
    fun `old observer and late callback cannot clear retried task`() = runTest {
        val tasks = ExtensionInstallTasks(backgroundScope, {}, { throw it })
        var oldId = 0L
        var newId = 0L
        val old = tasks.start("test") {
            oldId = it.id
            awaitCancellation()
        }
        val oldObserver = launch { old.collect() }
        runCurrent()
        val retry = tasks.start("test") {
            newId = it.id
            it.step.value = InstallStep.Installing
        }
        val observed = mutableListOf<InstallStep>()
        val newObserver = launch { retry.toList(observed) }
        runCurrent()
        assertTrue(oldObserver.isCompleted)
        assertNotEquals(oldId, newId)
        tasks.update(oldId, InstallStep.Error)
        runCurrent()
        assertTrue(tasks.isActive(newId))
        assertFalse(newObserver.isCompleted)
        tasks.update(newId, InstallStep.Installed)
        runCurrent()
        assertEquals(InstallStep.Installed, observed.last())
        assertTrue(newObserver.isCompleted)
        assertFalse(tasks.isActive(newId))
    }

    @Test
    fun `failure terminates flow but another package still installs`() = runTest {
        val failures = mutableListOf<Throwable>()
        val tasks = ExtensionInstallTasks(backgroundScope, {}, { failures.add(it) })
        val failed = tasks.start("bad") { throw IOException("failed download") }
        val success = tasks.start("good") { it.step.value = InstallStep.Installed }
        val observed = mutableListOf<InstallStep>()
        val failedObserver = launch { failed.toList(observed) }
        val successfulObserver = launch { success.collect() }
        runCurrent()
        assertEquals(1, failures.size)
        assertEquals(InstallStep.Error, observed.last())
        assertTrue(failedObserver.isCompleted)
        assertTrue(successfulObserver.isCompleted)
    }

    @Test
    fun `leaving observation cancels download and cleans only its own queue`() = runTest {
        val cancelled = mutableListOf<Long>()
        val tasks = ExtensionInstallTasks(backgroundScope, { cancelled.add(it) }, { throw it })
        var id = 0L
        var cleaned = false
        val flow = tasks.start("test") {
            id = it.id
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { cleaned = true }
            }
        }
        val observer = launch { flow.collect() }
        runCurrent()
        observer.cancel()
        runCurrent()
        assertTrue(cleaned)
        assertFalse(tasks.isActive(id))
        assertEquals(listOf(id), cancelled)
    }

    @Test
    fun `operation cancellation is not reported as a failed install`() = runTest {
        val failures = mutableListOf<Throwable>()
        val tasks = ExtensionInstallTasks(backgroundScope, {}, { failures.add(it) })
        val flow = tasks.start("test") { throw CancellationException("request cancelled") }
        val observed = mutableListOf<InstallStep>()
        val observer = launch { flow.toList(observed) }
        runCurrent()
        assertTrue(observer.isCompleted)
        assertTrue(failures.isEmpty())
        assertEquals(InstallStep.Idle, observed.last())
    }
}
