package org.archphene.app

import java.util.concurrent.atomic.AtomicBoolean

internal class LatestReadyCallback<T : Any> {
    private var pending: Registration<T>? = null

    @Synchronized
    fun register(callback: (T) -> Unit): Registration<T> {
        pending?.deactivate()
        return Registration(this, callback).also { pending = it }
    }

    @Synchronized
    fun take(): Registration<T>? = pending

    @Synchronized
    internal fun pendingCount(): Int = if (pending == null) 0 else 1

    internal class Registration<T : Any>(
        private val owner: LatestReadyCallback<T>,
        internal val callback: (T) -> Unit,
    ) {
        private val active = AtomicBoolean(true)

        fun cancel() {
            owner.cancel(this)
        }

        fun deliver(value: T): Boolean = owner.deliver(this, value)

        internal fun claim(): Boolean = active.compareAndSet(true, false)

        internal fun deactivate() = active.set(false)
    }

    @Synchronized
    private fun cancel(registration: Registration<T>) {
        registration.deactivate()
        if (pending === registration) pending = null
    }

    private fun deliver(
        registration: Registration<T>,
        value: T,
    ): Boolean {
        val callback =
            synchronized(this) {
                if (pending !== registration || !registration.claim()) return false
                pending = null
                registration.callback
            }
        callback(value)
        return true
    }
}
