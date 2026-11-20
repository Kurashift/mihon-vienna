package eu.kanade.tachiyomi

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AppStartupStatus(
    val databaseMigrationInProgress: Boolean = false,
    val ready: Boolean = false,
)

class AppStartupState {
    private val _status = MutableStateFlow(AppStartupStatus())
    val status: StateFlow<AppStartupStatus> = _status.asStateFlow()

    fun markDatabaseMigrationRequired() {
        _status.update {
            it.copy(databaseMigrationInProgress = true)
        }
    }

    fun markReady() {
        _status.update {
            it.copy(
                databaseMigrationInProgress = false,
                ready = true,
            )
        }
    }
}
