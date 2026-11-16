package tachiyomi.core.common.util.lang

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

class SubscriberGate : AbstractCoroutineContextElement(SubscriberGate) {

    companion object Key : CoroutineContext.Key<SubscriberGate>

    internal val open = MutableStateFlow(true)

    val isOpen: Boolean
        get() = open.value

    suspend fun observeSubscriptions(subscriptionCount: StateFlow<Int>, stopTimeout: Duration) {
        subscriptionCount
            .map { it > 0 }
            .distinctUntilChanged()
            .collectLatest { subscribed ->
                if (!subscribed) delay(stopTimeout)
                open.value = subscribed
            }
    }
}

fun <T> Flow<T>.pausedWhileUnsubscribed(): Flow<T> = flow {
    val gate = currentCoroutineContext()[SubscriberGate]
    if (gate == null) {
        emitAll(this@pausedWhileUnsubscribed)
    } else {
        emitAll(
            this@pausedWhileUnsubscribed
                .combine(gate.open) { value, open -> value to open }
                .filter { it.second }
                .map { it.first },
        )
    }
}
