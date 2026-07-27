package org.archphene.app.runtime

internal enum class ProjectSyncRecoveryStrategy {
    FINALIZE_COMMITTED_DELETE,
    RESTORE_DELETE_BACKUP,
    KEEP_DELETE_TARGET_REMOVE_BACKUP,
    KEEP_DELETE_TARGET,
    FINALIZE_PUBLISHED_UPDATE,
    RESTORE_UPDATE_BACKUP,
    KEEP_ORIGINAL_DISCARD_STAGING,
    DISCARD_NEW_FILE_STAGING,
}

internal fun decideProjectSyncRecovery(
    journal: ProjectSyncJournal,
    targetPresent: Boolean,
    stagingPresent: Boolean,
    backupPresent: Boolean,
    targetMatchesExpected: Boolean,
): ProjectSyncRecoveryStrategy {
    check(!targetMatchesExpected || targetPresent) {
        "A missing synchronization target cannot match"
    }
    if (journal.operation == SYNC_JOURNAL_DELETE) {
        check(!stagingPresent) {
            "Android deletion recovery found unexpected staging"
        }
        if (journal.phase == SYNC_JOURNAL_COMMITTED) {
            check(!targetPresent) {
                "Committed Android deletion target reappeared"
            }
            return ProjectSyncRecoveryStrategy.FINALIZE_COMMITTED_DELETE
        }
        return when {
            backupPresent && !targetPresent ->
                ProjectSyncRecoveryStrategy.RESTORE_DELETE_BACKUP
            backupPresent ->
                ProjectSyncRecoveryStrategy.KEEP_DELETE_TARGET_REMOVE_BACKUP
            targetPresent ->
                ProjectSyncRecoveryStrategy.KEEP_DELETE_TARGET
            else -> error("Interrupted Android deletion lost both versions")
        }
    }

    check(journal.operation == SYNC_JOURNAL_PUSH) {
        "Project synchronization recovery operation is invalid"
    }
    return when {
        targetMatchesExpected ->
            ProjectSyncRecoveryStrategy.FINALIZE_PUBLISHED_UPDATE
        backupPresent && !targetPresent ->
            ProjectSyncRecoveryStrategy.RESTORE_UPDATE_BACKUP
        backupPresent ->
            error("Interrupted Android update retained two unequal versions")
        targetPresent -> {
            check(journal.hadOriginal) {
                "Interrupted Android update collided with a new target"
            }
            ProjectSyncRecoveryStrategy.KEEP_ORIGINAL_DISCARD_STAGING
        }
        !journal.hadOriginal ->
            ProjectSyncRecoveryStrategy.DISCARD_NEW_FILE_STAGING
        else -> error("Interrupted Android update lost the previous project file")
    }
}
