package com.fliplauncher

import android.os.Handler

/** One pending payload per ID, with a fixed first deadline so busy widgets cannot starve others. */
internal class WidgetUpdateQueue<T>(
    private val handler: Handler,
    private val consume: (Int, T, (() -> Unit) -> Boolean) -> Unit
) {
    private class Ticket<T>(val value: T)
    private val lock = Any()
    private val pending = linkedMapOf<Int, Ticket<T>>()
    private val current = mutableMapOf<Int, Ticket<T>>()
    private var scheduled = false
    private val drain = Runnable { drainOne() }

    fun submit(id: Int, value: T) = synchronized(lock) {
        val ticket = Ticket(value)
        pending[id] = ticket
        current[id] = ticket
        schedule(100)
    }

    fun cancel(id: Int) = synchronized(lock) {
        pending.remove(id)
        current.remove(id)
        cancelEmpty()
    }

    fun retain(ids: Set<Int>) = synchronized(lock) {
        pending.keys.retainAll(ids)
        current.keys.retainAll(ids)
        cancelEmpty()
    }

    private fun schedule(delay: Long) {
        if (!scheduled && pending.isNotEmpty()) {
            scheduled = true
            handler.postDelayed(drain, delay)
        }
    }

    private fun cancelEmpty() {
        if (pending.isEmpty()) {
            handler.removeCallbacks(drain)
            scheduled = false
        }
    }

    private fun drainOne() {
        val next = synchronized(lock) {
            scheduled = false
            pending.entries.firstOrNull()?.let { it.key to pending.remove(it.key)!! }
        } ?: return
        val (id, ticket) = next
        try {
            consume(id, ticket.value) { publish ->
                synchronized(lock) {
                    if (current[id] !== ticket) false
                    else { publish(); true }
                }
            }
        } finally {
            synchronized(lock) {
                if (current[id] === ticket) current.remove(id)
                // Yield between inflations; never inflate a whole board in one callback.
                schedule(16)
            }
        }
    }
}
