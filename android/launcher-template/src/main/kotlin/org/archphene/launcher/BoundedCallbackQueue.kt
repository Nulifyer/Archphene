package org.archphene.launcher

import java.util.ArrayDeque

internal class BoundedCallbackQueue<T : Any>(
    private val capacity: Int,
    private val schedule: (Runnable) -> Boolean,
    private val consume: (T) -> Unit,
    private val discard: (T) -> Unit = {},
    private val reject: (T) -> Unit = discard,
) {
    private val queue = ArrayDeque<T>(capacity)
    private var scheduled = false
    private var closed = false
    private var accepting = true
    private var generation = 0L

    init {
        require(capacity > 0)
    }

    fun offer(value: T): Boolean {
        var rejected: T? = null
        val accepted =
            synchronized(this) {
                if (closed || !accepting || queue.size >= capacity) {
                    rejected = value
                    false
                } else {
                    queue.addLast(value)
                    if (scheduled) {
                        true
                    } else {
                        scheduled = true
                        val token = ++generation
                        if (schedule(drain(token))) {
                            true
                        } else {
                            scheduled = false
                            queue.removeLast()
                            rejected = value
                            false
                        }
                    }
                }
            }
        rejected?.let(reject)
        return accepted
    }

    fun clear() {
        val rejected =
            synchronized(this) {
                generation++
                scheduled = false
                removeAllLocked()
            }
        rejected.forEach(discard)
    }

    fun pause() {
        val rejected =
            synchronized(this) {
                accepting = false
                generation++
                scheduled = false
                removeAllLocked()
            }
        rejected.forEach(discard)
    }

    fun resume() {
        synchronized(this) {
            if (!closed) accepting = true
        }
    }

    fun close() {
        val rejected =
            synchronized(this) {
                closed = true
                accepting = false
                generation++
                scheduled = false
                removeAllLocked()
            }
        rejected.forEach(discard)
    }

    internal fun pendingCount(): Int = synchronized(this) { queue.size }

    private fun drain(token: Long): Runnable =
        Runnable {
            val next =
                synchronized(this) {
                    if (closed || token != generation || queue.isEmpty()) {
                        return@Runnable
                    }
                    queue.removeFirst()
                }
            consume(next)
            val discarded =
                synchronized(this) {
                    if (closed || token != generation) {
                        emptyList()
                    } else if (queue.isEmpty()) {
                        scheduled = false
                        emptyList()
                    } else if (schedule(drain(token))) {
                        emptyList()
                    } else {
                        scheduled = false
                        removeAllLocked()
                    }
                }
            discarded.forEach(discard)
        }

    private fun removeAllLocked(): List<T> =
        ArrayList<T>(queue.size).also { removed ->
            while (queue.isNotEmpty()) {
                removed.add(queue.removeFirst())
            }
        }
}
