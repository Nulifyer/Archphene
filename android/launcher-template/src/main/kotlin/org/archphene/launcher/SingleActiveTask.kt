package org.archphene.launcher

import java.io.Closeable

internal class SingleActiveTask {
    private var active = false
    private var closed = false

    @Synchronized
    fun tryAcquire(): Boolean {
        if (active || closed) return false
        active = true
        return true
    }

    @Synchronized
    fun release() {
        check(active)
        active = false
    }

    @Synchronized
    fun close() {
        closed = true
    }
}

internal enum class SingleTaskOutcome {
    FINISHED,
    CANCELLED,
    FAILED,
}

internal class SingleTaskCompletion(
    private val task: SingleActiveTask,
    private val callback: (SingleTaskOutcome) -> Unit,
) {
    private var completed = false

    fun complete(outcome: SingleTaskOutcome): Boolean {
        synchronized(this) {
            if (completed) return false
            completed = true
        }
        task.release()
        callback(outcome)
        return true
    }
}

internal class PrintWriteCancellation {
    @Volatile
    var cancelled = false
        private set

    private var committed = false
    private var input: Closeable? = null
    private var output: Closeable? = null

    fun attachInput(value: Closeable) = attach(value, outputSlot = false)

    fun attachOutput(value: Closeable) = attach(value, outputSlot = true)

    @Synchronized
    private fun attach(
        value: Closeable,
        outputSlot: Boolean,
    ) {
        if (cancelled || committed) {
            runCatching { value.close() }
            return
        }
        if (outputSlot) output = value else input = value
    }

    @Synchronized
    fun cancel() {
        if (committed) return
        cancelled = true
        val resources = takeResources()
        resources.first?.let { runCatching { it.close() } }
        resources.second?.let { runCatching { it.close() } }
    }

    fun close(): Boolean {
        val resources = synchronized(this) { takeResources() }
        val outputClosed = resources.second?.let { runCatching { it.close() }.isSuccess } ?: true
        val inputClosed = resources.first?.let { runCatching { it.close() }.isSuccess } ?: true
        return outputClosed && inputClosed
    }

    @Synchronized
    fun commit(outcome: SingleTaskOutcome): SingleTaskOutcome {
        check(!committed)
        committed = true
        return if (cancelled) SingleTaskOutcome.CANCELLED else outcome
    }

    private fun takeResources(): Pair<Closeable?, Closeable?> {
        val resources = input to output
        input = null
        output = null
        return resources
    }
}
