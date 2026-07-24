package org.archphene.app.runtime

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class RuntimeSnapshot {
    var lifecycle: Int = 0
        private set
    var queueDepth: Int = 0
        private set
    var generation: Long = 0
        private set
    var acceptedEvents: Long = 0
        private set
    var rejectedEvents: Long = 0
        private set
    var drainedEvents: Long = 0
        private set
    var statusFlags: Int = 0
        private set

    val archRootReady: Boolean
        get() = statusFlags and STATUS_ARCH_ROOT_READY != 0

    val jobStoreReady: Boolean
        get() = statusFlags and STATUS_JOB_STORE_READY != 0

    val packageRuntimeReady: Boolean
        get() = statusFlags and STATUS_PACKAGE_RUNTIME_READY != 0

    val packageCatalogReady: Boolean
        get() = statusFlags and STATUS_PACKAGE_CATALOG_READY != 0

    val sessionInterrupted: Boolean
        get() = statusFlags and STATUS_SESSION_INTERRUPTED != 0

    private val buffer =
        ByteBuffer.allocateDirect(NativeRuntime.SNAPSHOT_SIZE).order(ByteOrder.LITTLE_ENDIAN)

    fun read(handle: Long): Boolean {
        if (handle == 0L) {
            return false
        }
        if (NativeRuntime.nativeWriteSnapshot(handle, buffer) != NativeRuntime.SNAPSHOT_SIZE) {
            return false
        }
        if (buffer.getInt(0) != SNAPSHOT_MAGIC) {
            return false
        }
        if (buffer.getInt(4) != NativeRuntime.PROTOCOL_VERSION) {
            return false
        }
        lifecycle = buffer.getInt(8)
        queueDepth = buffer.getInt(12)
        generation = buffer.getLong(16)
        acceptedEvents = buffer.getLong(24)
        rejectedEvents = buffer.getLong(32)
        drainedEvents = buffer.getLong(40)
        statusFlags = buffer.getInt(48)
        return true
    }

    private companion object {
        const val SNAPSHOT_MAGIC = 0x48505241
        const val STATUS_ARCH_ROOT_READY = 1
        const val STATUS_JOB_STORE_READY = 1 shl 1
        const val STATUS_PACKAGE_RUNTIME_READY = 1 shl 2
        const val STATUS_PACKAGE_CATALOG_READY = 1 shl 3
        const val STATUS_SESSION_INTERRUPTED = 1 shl 4
    }
}
