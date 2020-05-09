@file:Suppress("EXPERIMENTAL_API_USAGE")

package com.airbnb.mvrx

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelFutureOnCompletion
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch

/**
 * Similar to LifecycleScope.launchWhenStarted but cancels the block when the owner is stopped.
 * This function suspends until the next time the owner is started.
 */
private suspend fun LifecycleOwner.launchWhenStartedAndCancelWhenStopped(block: suspend () -> Unit) {
    var parentJob = SupervisorJob()
    lifecycle.addObserver(object : LifecycleEventObserver {
        override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
            when (event) {
                Lifecycle.Event.ON_START -> lifecycleScope.launch(parentJob) { block() }
                Lifecycle.Event.ON_STOP -> parentJob.cancel()
                Lifecycle.Event.ON_DESTROY -> lifecycle.removeObserver(this)
                else -> Unit
            }
        }
    })
    return parentJob.join()
}

/**
 * Transforms a [Flow] into a new one that only emits when the lifecycle owner is started.
 *
 * The behavior when the owner transitions back to start can be specified by [deliveryMode].
 */
fun <T : Any> Flow<T>.flowWhenStarted(
        owner: LifecycleOwner,
        deliveryMode: DeliveryMode = RedeliverOnStart,
        lastDeliveredValueFromPriorObserver: T? = null
): Flow<T> = channelFlow {
    val stateChannel = Channel<T>(capacity = Channel.CONFLATED)
    invokeOnClose { stateChannel.close() }
    onEach(stateChannel::send).launchIn(owner.lifecycleScope)

    var lastDeliveredItem: T? = lastDeliveredValueFromPriorObserver
    while (true) {
        owner.launchWhenStartedAndCancelWhenStopped {
            when (deliveryMode) {
                RedeliverOnStart -> stateChannel.poll() ?: lastDeliveredItem
                is UniqueOnly -> stateChannel.poll()?.takeIf { it != lastDeliveredItem }
            }?.let(stateChannel::offer)

            while (true) {
                val item = stateChannel.receive()
                send(item)
                lastDeliveredItem = item
            }
        }
    }
}