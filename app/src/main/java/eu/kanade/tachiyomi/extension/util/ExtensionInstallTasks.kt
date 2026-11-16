package eu.kanade.tachiyomi.extension.util

import eu.kanade.tachiyomi.extension.model.InstallStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal class ExtensionInstallTasks(
    private val scope: CoroutineScope,
    private val cancelQueued: (Long) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    private val nextId = AtomicLong(System.currentTimeMillis())
    private val byPackage = ConcurrentHashMap<String, Task>()
    private val byId = ConcurrentHashMap<Long, Task>()

    fun start(packageName: String, install: suspend (Task) -> Unit): Flow<InstallStep> {
        val task = Task(nextId.incrementAndGet())
        task.job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                install(task)
            } catch (error: CancellationException) {
                task.step.value = InstallStep.Idle
                throw error
            } catch (error: Exception) {
                task.job.ensureActive()
                onFailure(error)
                task.step.value = InstallStep.Error
            } finally {
                if (task.job.isCancelled && task.step.value != InstallStep.Installed) {
                    task.step.value = InstallStep.Idle
                }
            }
        }
        byId[task.id] = task
        byPackage.put(packageName, task)?.let(::cancel)
        task.job.start()
        return task.step.transformWhile { step ->
            emit(step)
            !step.isCompleted()
        }.onCompletion {
            byPackage.remove(packageName, task)
            byId.remove(task.id, task)
            task.job.cancel()
            if (task.step.value != InstallStep.Installed) cancelQueued(task.id)
        }
    }

    fun cancel(packageName: String) {
        byPackage.remove(packageName)?.let(::cancel)
    }

    private fun cancel(task: Task) {
        byId.remove(task.id, task)
        task.job.cancel()
        task.step.value = InstallStep.Idle
        cancelQueued(task.id)
    }

    fun update(id: Long, step: InstallStep) {
        byId[id]?.step?.value = step
    }

    fun isActive(id: Long): Boolean = byId[id]?.step?.value?.isCompleted() == false

    class Task(val id: Long) {
        val step = MutableStateFlow(InstallStep.Pending)
        lateinit var job: Job
    }
}
