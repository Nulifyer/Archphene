package org.archphene.app.runtime

import android.net.Uri
import java.nio.ByteBuffer

internal data class ProjectSyncRecoveryResult(val retainedConflictPath: String?)

internal class ProjectSyncRecoveryCoordinator(
    private val provider: ProjectSyncProvider,
    private val documents: ProjectSyncAndroidDocuments,
    private val journalStore: ProjectSyncJournalStore,
) {
    fun recover(
        activeHandle: Long,
        activeTreeUri: Uri,
        output: ByteBuffer,
    ): ProjectSyncRecoveryResult? {
        val journal = journalStore.load() ?: return null
        check(journal.treeUri == activeTreeUri.toString()) {
            "An interrupted synchronization belongs to another Android folder"
        }
        val parentUri =
            Uri.parse(journal.parentUri)
                .takeIf { it.scheme == "content" }
                ?: error("Project synchronization journal parent is invalid")
        val target = documents.findChild(parentUri, journal.targetName)
        val staging =
            journal.stagingName
                .takeIf(String::isNotEmpty)
                ?.let { documents.findChild(parentUri, it) }
        val backup =
            journal.backupName
                .takeIf(String::isNotEmpty)
                ?.let { documents.findChild(parentUri, it) }
        val expected by lazy { decodeProjectSyncFingerprintText(journal.expected) }
        val targetIsPublished =
            if (journal.operation == SYNC_JOURNAL_PUSH) {
                target?.let {
                    try {
                        documents.verifyFingerprint(activeHandle, it.uri, expected, output)
                        true
                    } catch (_: ProjectSyncFingerprintMismatch) {
                        false
                    }
                } == true
            } else {
                false
            }
        val strategy =
            decideProjectSyncRecovery(
                journal,
                targetPresent = target != null,
                stagingPresent = staging != null,
                backupPresent = backup != null,
                targetMatchesExpected = targetIsPublished,
            )
        var retainedConflictPath: String? = null
        when (strategy) {
            ProjectSyncRecoveryStrategy.FINALIZE_COMMITTED_DELETE -> {
                backup?.let {
                    try {
                        documents.verifyFingerprint(activeHandle, it.uri, expected, output)
                    } catch (_: ProjectSyncFingerprintMismatch) {
                        retainedConflictPath = backupPath(journal)
                        return@let
                    }
                    check(
                        provider.delete(
                            it.uri,
                            "finalize a recovered Android project deletion",
                        ),
                    ) {
                        "Android provider retained a committed deletion backup"
                    }
                }
            }
            ProjectSyncRecoveryStrategy.RESTORE_DELETE_BACKUP -> {
                val restored =
                    provider.rename(
                        checkNotNull(backup).uri,
                        journal.targetName,
                        "restore an interrupted Android project deletion",
                    ) ?: error("Android provider could not restore interrupted deletion")
                documents.verifyFingerprint(activeHandle, restored, expected, output)
            }
            ProjectSyncRecoveryStrategy.KEEP_DELETE_TARGET_REMOVE_BACKUP -> {
                documents.verifyFingerprint(
                    activeHandle,
                    checkNotNull(target).uri,
                    expected,
                    output,
                )
                check(
                    provider.delete(
                        checkNotNull(backup).uri,
                        "remove a duplicate Android deletion backup",
                    ),
                ) {
                    "Android provider retained duplicate deletion backup"
                }
            }
            ProjectSyncRecoveryStrategy.KEEP_DELETE_TARGET -> {
                documents.verifyFingerprint(
                    activeHandle,
                    checkNotNull(target).uri,
                    expected,
                    output,
                )
            }
            ProjectSyncRecoveryStrategy.FINALIZE_PUBLISHED_UPDATE -> {
                staging?.let {
                    check(
                        provider.delete(
                            it.uri,
                            "discard recovered Android synchronization staging",
                        ),
                    ) {
                        "Android provider retained synchronization staging"
                    }
                }
                backup?.let {
                    check(
                        provider.delete(
                            it.uri,
                            "finalize a recovered Android synchronization backup",
                        ),
                    ) {
                        "Android provider retained synchronization backup"
                    }
                }
            }
            ProjectSyncRecoveryStrategy.RESTORE_UPDATE_BACKUP -> {
                val restored =
                    provider.rename(
                        checkNotNull(backup).uri,
                        journal.targetName,
                        "restore an interrupted Android project update",
                    ) ?: error("Android provider could not restore interrupted update")
                if (journal.hadOriginal) {
                    check(documents.queryName(restored) == journal.targetName) {
                        "Android provider changed restored project name"
                    }
                }
                staging?.let {
                    check(
                        provider.delete(
                            it.uri,
                            "discard interrupted Android synchronization staging",
                        ),
                    ) {
                        "Android provider retained synchronization staging"
                    }
                }
            }
            ProjectSyncRecoveryStrategy.KEEP_ORIGINAL_DISCARD_STAGING -> {
                staging?.let {
                    check(
                        provider.delete(
                            it.uri,
                            "discard collided Android synchronization staging",
                        ),
                    ) {
                        "Android provider retained synchronization staging"
                    }
                }
            }
            ProjectSyncRecoveryStrategy.DISCARD_NEW_FILE_STAGING -> {
                staging?.let {
                    check(
                        provider.delete(
                            it.uri,
                            "discard new-file Android synchronization staging",
                        ),
                    ) {
                        "Android provider retained synchronization staging"
                    }
                }
            }
        }
        journalStore.clear()
        return ProjectSyncRecoveryResult(retainedConflictPath)
    }

    private fun backupPath(journal: ProjectSyncJournal): String {
        val slash = journal.path.lastIndexOf('/')
        return if (slash < 0) {
            journal.backupName
        } else {
            "${journal.path.substring(0, slash)}/${journal.backupName}"
        }
    }
}
