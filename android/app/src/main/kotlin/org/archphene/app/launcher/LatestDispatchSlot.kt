package org.archphene.app.launcher

internal class LatestDispatchSlot<T : Any>(
    private val schedule: (Runnable) -> Boolean,
    private val cancel: (Runnable) -> Unit,
    private val consume: (T) -> Unit,
) {
    private var pending: T? = null
    private var posted: Drain? = null
    private var generation = 0L
    private var closed = false

    @Synchronized
    fun offer(value: T): Boolean {
        if (closed) return false
        val token = generation + 1
        val command = Drain(token)
        if (!schedule(command)) return false
        val previous = posted
        pending = value
        generation = token
        posted = command
        previous?.let(cancel)
        return true
    }

    @Synchronized
    fun close() {
        closed = true
        generation++
        posted?.let(cancel)
        posted = null
        pending = null
    }

    @Synchronized
    internal fun pendingCount(): Int = if (pending == null) 0 else 1

    private inner class Drain(private val token: Long) : Runnable {
        override fun run() {
            val value =
                synchronized(this@LatestDispatchSlot) {
                    if (closed || token != generation || posted !== this) return
                    val current = pending ?: return
                    pending = null
                    posted = null
                    current
                }
            consume(value)
        }
    }
}
