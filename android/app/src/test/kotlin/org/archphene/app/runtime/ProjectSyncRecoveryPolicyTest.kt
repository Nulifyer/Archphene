package org.archphene.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProjectSyncRecoveryPolicyTest {
    @Test
    fun committedDeletionOnlyFinalizesWithoutAReappearedTarget() {
        assertEquals(
            ProjectSyncRecoveryStrategy.FINALIZE_COMMITTED_DELETE,
            decide(
                deletion(SYNC_JOURNAL_COMMITTED),
                target = false,
                backup = true,
            ),
        )
        assertThrows(IllegalStateException::class.java) {
            decide(
                deletion(SYNC_JOURNAL_COMMITTED),
                target = true,
                backup = true,
            )
        }
    }

    @Test
    fun interruptedDeletionRestoresOrVerifiesTheSurvivingVersion() {
        assertEquals(
            ProjectSyncRecoveryStrategy.RESTORE_DELETE_BACKUP,
            decide(deletion(), target = false, backup = true),
        )
        assertEquals(
            ProjectSyncRecoveryStrategy.KEEP_DELETE_TARGET_REMOVE_BACKUP,
            decide(deletion(), target = true, backup = true),
        )
        assertEquals(
            ProjectSyncRecoveryStrategy.KEEP_DELETE_TARGET,
            decide(deletion(), target = true, backup = false),
        )
        assertThrows(IllegalStateException::class.java) {
            decide(deletion(), target = false, backup = false)
        }
    }

    @Test
    fun publishedUpdateWinsAndCleansBothTemporaryDocuments() {
        assertEquals(
            ProjectSyncRecoveryStrategy.FINALIZE_PUBLISHED_UPDATE,
            decide(
                push(hadOriginal = true),
                target = true,
                staging = true,
                backup = true,
                matches = true,
            ),
        )
    }

    @Test
    fun interruptedUpdateRestoresOriginalOrDiscardsUnpublishedNewFile() {
        assertEquals(
            ProjectSyncRecoveryStrategy.RESTORE_UPDATE_BACKUP,
            decide(push(hadOriginal = true), target = false, staging = true, backup = true),
        )
        assertEquals(
            ProjectSyncRecoveryStrategy.KEEP_ORIGINAL_DISCARD_STAGING,
            decide(push(hadOriginal = true), target = true, staging = true, backup = false),
        )
        assertEquals(
            ProjectSyncRecoveryStrategy.DISCARD_NEW_FILE_STAGING,
            decide(push(hadOriginal = false), target = false, staging = true, backup = false),
        )
    }

    @Test
    fun ambiguousOrLostUpdateFailsClosed() {
        assertThrows(IllegalStateException::class.java) {
            decide(push(hadOriginal = true), target = true, backup = true)
        }
        assertThrows(IllegalStateException::class.java) {
            decide(push(hadOriginal = false), target = true, backup = false)
        }
        assertThrows(IllegalStateException::class.java) {
            decide(push(hadOriginal = true), target = false, backup = false)
        }
    }

    @Test
    fun deletionRejectsAnImpossibleStagingDocument() {
        assertThrows(IllegalStateException::class.java) {
            decide(deletion(), target = true, staging = true, backup = false)
        }
    }

    private fun decide(
        journal: ProjectSyncJournal,
        target: Boolean,
        staging: Boolean = false,
        backup: Boolean,
        matches: Boolean = false,
    ): ProjectSyncRecoveryStrategy =
        decideProjectSyncRecovery(
            journal,
            targetPresent = target,
            stagingPresent = staging,
            backupPresent = backup,
            targetMatchesExpected = matches,
        )

    private fun deletion(phase: Int = SYNC_JOURNAL_BACKED_UP): ProjectSyncJournal =
        journal(SYNC_JOURNAL_DELETE, phase, hadOriginal = true)

    private fun push(hadOriginal: Boolean): ProjectSyncJournal =
        journal(SYNC_JOURNAL_PUSH, SYNC_JOURNAL_BACKED_UP, hadOriginal)

    private fun journal(
        operation: Int,
        phase: Int,
        hadOriginal: Boolean,
    ): ProjectSyncJournal =
        ProjectSyncJournal(
            operation = operation,
            phase = phase,
            treeUri = "content://provider/tree/root",
            parentUri = "content://provider/document/root",
            path = "src/main.rs",
            targetName = "main.rs",
            stagingName = if (operation == SYNC_JOURNAL_PUSH) ".stage" else "",
            backupName = ".backup",
            expected = "f:1:${"00".repeat(32)}",
            hadOriginal = hadOriginal,
        )
}
