package org.archphene.app.runtime

import android.net.Uri
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

internal data class ProjectSyncDeletedDocument(
    val uri: Uri,
    val expected: ProjectSyncFingerprint,
    val backupPath: String,
)

internal data class ProjectSyncResult(
    var pulled: Int = 0,
    var pushed: Int = 0,
    var deferredDeletes: Int = 0,
    var androidDeletesApplied: Int = 0,
    var rescanRequired: Boolean = false,
    val conflictPaths: MutableSet<String> = linkedSetOf(),
    val ignoredDocumentIds: MutableSet<String> = linkedSetOf(),
    val deletedDocuments: MutableList<ProjectSyncDeletedDocument> = ArrayList(),
)

internal data class ProjectSyncTransactionContext(
    val activeHandle: Long,
    val treeUri: Uri,
    val mappingId: String,
    val remote: LinkedHashMap<String, ProjectSyncRemoteEntry>,
    val request: ByteBuffer,
    val output: ByteBuffer,
)

internal interface ProjectSyncMutationBackend {
    fun createAndroidDirectory(
        context: ProjectSyncTransactionContext,
        entry: ProjectSyncPlanEntry,
    )

    fun pushAndroidFile(
        context: ProjectSyncTransactionContext,
        entry: ProjectSyncPlanEntry,
    )

    fun pullLinuxFile(
        context: ProjectSyncTransactionContext,
        entry: ProjectSyncPlanEntry,
    )

    fun stageAndroidDeletion(
        context: ProjectSyncTransactionContext,
        entry: ProjectSyncPlanEntry,
        result: ProjectSyncResult,
    )

    fun deleteAndroidDirectory(
        context: ProjectSyncTransactionContext,
        entry: ProjectSyncPlanEntry,
    ): Boolean
}

internal class ProjectSyncTransactionCoordinator(
    private val provider: ProjectSyncProvider,
    private val backend: ProjectSyncMutationBackend,
    private val checkCancellation: () -> Unit,
    private val publishProgress:
        (
            index: Int,
            total: Int,
            action: Int,
            pulled: Int,
            pushed: Int,
            conflicts: Int,
        ) -> Unit,
) {
    fun execute(
        context: ProjectSyncTransactionContext,
        plan: List<ProjectSyncPlanEntry>,
    ): ProjectSyncResult {
        val result = ProjectSyncResult()
        val actionTotal = plan.count { entry -> entry.action != SYNC_ACTION_CONVERGED }
        var actionIndex = 0
        plan.forEach { entry ->
            checkCancellation()
            when {
                entry.action == SYNC_ACTION_PUSH_ANDROID &&
                    entry.linux?.kind == SYNC_KIND_DIRECTORY -> {
                    publishProgress(
                        ++actionIndex,
                        actionTotal,
                        entry.action,
                        result.pulled,
                        result.pushed,
                        result.conflictPaths.size,
                    )
                    backend.createAndroidDirectory(context, entry)
                    result.pushed++
                }
                entry.action == SYNC_ACTION_PULL_LINUX &&
                    entry.android?.kind == SYNC_KIND_DIRECTORY -> {
                    publishProgress(
                        ++actionIndex,
                        actionTotal,
                        entry.action,
                        result.pulled,
                        result.pushed,
                        result.conflictPaths.size,
                    )
                    executeLocal(
                        context,
                        SYNC_LOCAL_CREATE_DIRECTORY,
                        entry.path,
                        operation = "create Linux project directory",
                    )
                    result.pulled++
                }
            }
        }

        plan.forEach { entry ->
            checkCancellation()
            when (entry.action) {
                SYNC_ACTION_PUSH_ANDROID -> {
                    if (entry.linux?.kind == SYNC_KIND_FILE) {
                        publishProgress(
                            ++actionIndex,
                            actionTotal,
                            entry.action,
                            result.pulled,
                            result.pushed,
                            result.conflictPaths.size,
                        )
                        backend.pushAndroidFile(context, entry)
                        result.pushed++
                    }
                }
                SYNC_ACTION_PULL_LINUX -> {
                    if (entry.android?.kind == SYNC_KIND_FILE) {
                        publishProgress(
                            ++actionIndex,
                            actionTotal,
                            entry.action,
                            result.pulled,
                            result.pushed,
                            result.conflictPaths.size,
                        )
                        backend.pullLinuxFile(context, entry)
                        result.pulled++
                    }
                }
                SYNC_ACTION_CONFLICT -> {
                    publishProgress(
                        ++actionIndex,
                        actionTotal,
                        entry.action,
                        result.pulled,
                        result.pushed,
                        result.conflictPaths.size,
                    )
                    preserveConflict(context, entry)
                    result.conflictPaths.add(entry.path)
                }
            }
        }

        plan.asReversed().forEach { entry ->
            checkCancellation()
            when (entry.action) {
                SYNC_ACTION_DELETE_LINUX -> {
                    publishProgress(
                        ++actionIndex,
                        actionTotal,
                        entry.action,
                        result.pulled,
                        result.pushed,
                        result.conflictPaths.size,
                    )
                    val expected =
                        entry.linux
                            ?: error("Linux deletion has no expected fingerprint")
                    executeLocal(
                        context,
                        SYNC_LOCAL_DELETE,
                        entry.path,
                        expected.encode(),
                        operation = "delete Linux project entry",
                    )
                    result.pulled++
                }
                SYNC_ACTION_DELETE_ANDROID -> {
                    publishProgress(
                        ++actionIndex,
                        actionTotal,
                        entry.action,
                        result.pulled,
                        result.pushed,
                        result.conflictPaths.size,
                    )
                    when {
                        result.deletedDocuments.isEmpty() &&
                            entry.android?.kind == SYNC_KIND_FILE -> {
                            backend.stageAndroidDeletion(context, entry, result)
                            result.pushed++
                            result.androidDeletesApplied++
                        }
                        entry.android?.kind == SYNC_KIND_DIRECTORY &&
                            backend.deleteAndroidDirectory(context, entry) -> {
                            result.pushed++
                            result.androidDeletesApplied++
                        }
                        else -> result.deferredDeletes++
                    }
                }
            }
        }
        check(actionIndex == actionTotal) {
            "Project synchronization plan contains an incompatible action"
        }
        return result
    }

    private fun preserveConflict(
        context: ProjectSyncTransactionContext,
        entry: ProjectSyncPlanEntry,
    ) {
        if (entry.android?.kind != SYNC_KIND_FILE) {
            return
        }
        val remoteEntry =
            context.remote[entry.path]
                ?: error("Android conflict source disappeared")
        provider.open(remoteEntry.uri, "r", "open an Android conflict file").use { source ->
            val length =
                putProjectSyncRequest(
                    context.request,
                    entry.path,
                    entry.android.encode(),
                )
            context.output.clear()
            requireSuccess(
                NativeRuntime.nativeExecuteProjectSyncLocal(
                    context.activeHandle,
                    SYNC_LOCAL_PRESERVE_CONFLICT,
                    context.request,
                    length,
                    source.fd,
                    context.output,
                ).toLong(),
                context.output,
                "preserve Android project conflict",
            )
        }
    }

    private fun executeLocal(
        context: ProjectSyncTransactionContext,
        operationCode: Int,
        vararg fields: String,
        operation: String,
    ) {
        val length = putProjectSyncRequest(context.request, *fields)
        context.output.clear()
        requireSuccess(
            NativeRuntime.nativeExecuteProjectSyncLocal(
                context.activeHandle,
                operationCode,
                context.request,
                length,
                -1,
                context.output,
            ).toLong(),
            context.output,
            operation,
        )
    }

    private fun requireSuccess(
        result: Long,
        output: ByteBuffer,
        operation: String,
    ) {
        if (result >= 0) {
            return
        }
        var length = 0
        while (length < output.capacity() && output.get(length) != 0.toByte()) {
            length++
        }
        val bytes = ByteArray(length)
        output.position(0)
        output.get(bytes)
        throw IllegalStateException(
            String(bytes, StandardCharsets.UTF_8).ifEmpty { "$operation failed ($result)" },
        )
    }
}
