package org.archphene.launcher

internal class LatestCallbackSlot<T>(
    private val discard: (T) -> Unit = {},
    private val merge: (T, T) -> T = { _, next -> next },
) {
    private val lock = Any()
    private var pending: T? = null
    private var hasPending = false
    private var scheduled = false
    private var closed = false

    fun offer(
        value: T,
        schedule: () -> Boolean,
    ): Boolean =
        synchronized(lock) {
            if (closed) {
                discard(value)
                return@synchronized false
            }
            if (hasPending) {
                @Suppress("UNCHECKED_CAST")
                val previous = pending as T
                pending = merge(previous, value)
                discard(previous)
            } else {
                pending = value
                hasPending = true
            }
            if (scheduled) return@synchronized true
            if (schedule()) {
                scheduled = true
                true
            } else {
                @Suppress("UNCHECKED_CAST")
                discard(pending as T)
                pending = null
                hasPending = false
                false
            }
        }

    fun take(): T? =
        synchronized(lock) {
            if (!hasPending) {
                scheduled = false
                return@synchronized null
            }
            @Suppress("UNCHECKED_CAST")
            val value = pending as T
            pending = null
            hasPending = false
            scheduled = false
            value
        }

    fun clear() {
        synchronized(lock) {
            if (hasPending) {
                @Suppress("UNCHECKED_CAST")
                discard(pending as T)
            }
            pending = null
            hasPending = false
            scheduled = false
        }
    }

    fun close() {
        synchronized(lock) {
            clearLocked()
            closed = true
        }
    }

    private fun clearLocked() {
        if (hasPending) {
            @Suppress("UNCHECKED_CAST")
            discard(pending as T)
        }
        pending = null
        hasPending = false
        scheduled = false
    }

    internal fun pendingCount(): Int = synchronized(lock) { if (hasPending) 1 else 0 }
}
