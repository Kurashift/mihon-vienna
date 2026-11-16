package eu.kanade.tachiyomi.ui.manga

import app.cash.sqldelight.Query
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tachiyomi.core.common.util.lang.SubscriberGate
import tachiyomi.data.subscribeToList
import tachiyomi.data.subscribeToOne
import tachiyomi.data.subscribeToOneOrNull
import kotlin.time.Duration.Companion.seconds

class GatedQueryTest {

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2])
    fun `hidden invalidations do not execute SQL until the screen returns`(kind: Int) = runTest {
        val gate = SubscriberGate()
        val subscribers = MutableStateFlow(1)
        val query = CountingQuery()
        val displayed = mutableListOf<Long?>()
        val results = when (kind) {
            0 -> query.subscribeToList().map { it.single() }
            1 -> query.subscribeToOne()
            else -> query.subscribeToOneOrNull()
        }
        backgroundScope.launch { gate.observeSubscriptions(subscribers, 5.seconds) }
        backgroundScope.launch(gate) { results.collect { displayed.add(it) } }
        runCurrent()
        assertEquals(1, query.executions)
        subscribers.value = 0
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        repeat(100) {
            query.invalidate()
            runCurrent()
        }
        assertEquals(1, query.executions)
        assertEquals(listOf(0L), displayed)
        subscribers.value = 1
        runCurrent()

        assertEquals(2, query.executions)
        assertEquals(listOf(0L, 100L), displayed)
        assertEquals(1, query.listeners.size)
    }

    private class CountingQuery : Query<Long>({ it.getLong(0)!! }) {
        val listeners = mutableSetOf<Listener>()
        var executions = 0
        private var revision = 0L

        override fun addListener(listener: Listener) {
            listeners.add(listener)
        }

        override fun removeListener(listener: Listener) {
            listeners.remove(listener)
        }

        fun invalidate() {
            revision++
            listeners.toList().forEach { it.queryResultsChanged() }
        }

        override fun <Result> execute(mapper: (SqlCursor) -> QueryResult<Result>): QueryResult<Result> {
            executions++
            val cursor = object : SqlCursor {
                private var emitted = false
                override fun next(): QueryResult<Boolean> = QueryResult.Value(!emitted).also { emitted = true }
                override fun getLong(index: Int): Long = revision
                override fun getString(index: Int): String? = null
                override fun getBytes(index: Int): ByteArray? = null
                override fun getDouble(index: Int): Double? = null
                override fun getBoolean(index: Int): Boolean? = null
            }
            return mapper(cursor)
        }
    }
}
