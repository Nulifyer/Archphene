package org.archphene.app.runtime

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PackageLauncherReviewStateTest {
    @Test
    fun decodesReadyAndPendingLauncherReviews() {
        assertEquals(
            PackageLauncherReview(
                status = "ready",
                capabilities = 3,
                capabilitiesAnalyzed = true,
                launchers = 2,
                verifiedExecutables = 2,
                current = 2,
                pending = 0,
                attention = 0,
                failed = 0,
                integrationTopology = 0x100,
                profiledExecutables = 2,
                incompleteProfiles = 0,
            ),
            decodePackageLauncherReview(
                "R2\tready\t3\t1\t2\t2\t2\t0\t0\t0\t100\t2\t0\twayland,input,ime,clipboard,documents\n"
                    .ascii(),
            ),
        )
        assertEquals(
            "pending",
            decodePackageLauncherReview(
                "R2\tpending\tff\t1\t2\t2\t1\t1\t0\t0\t102\t2\t1\twayland,input,ime,clipboard,documents\n"
                    .ascii(),
            ).status,
        )
        assertEquals(
            0xff,
            decodePackageLauncherReview(
                "R2\tpending\tff\t1\t2\t2\t1\t1\t0\t0\t102\t2\t1\twayland,input,ime,clipboard,documents\n"
                    .ascii(),
            ).capabilities,
        )
    }

    @Test
    fun rejectsContradictoryOrUnboundedLauncherReviews() {
        listOf(
            "R2\tready\t3\t1\t2\t2\t1\t1\t0\t0\t100\t2\t0\twayland,input,ime,clipboard,documents\n",
            "R2\tpending\t3\t1\t2\t2\t2\t0\t0\t0\t100\t2\t0\twayland,input,ime,clipboard,documents\n",
            "R2\tfailed\t3\t1\t2\t2\t1\t0\t0\t0\t100\t2\t0\twayland,input,ime,clipboard,documents\n",
            "R2\tnot-installed\t1\t0\t0\t0\t0\t0\t0\t0\t0\t0\t0\twayland,input,ime,clipboard,documents\n",
            "R2\tready\t3\t1\t257\t2\t2\t0\t0\t0\t100\t2\t0\twayland,input,ime,clipboard,documents\n",
            "R2\tready\t3\t1\t2\t3\t2\t0\t0\t0\t100\t2\t0\twayland,input,ime,clipboard,documents\n",
            "R2\tready\t3\t1\t2\t2\t2\t0\t0\t0\t100\t3\t0\twayland,input,ime,clipboard,documents\n",
            "R2\tready\t3\t1\t2\t2\t2\t0\t0\t0\t100\t2\t3\twayland,input,ime,clipboard,documents\n",
            "R2\tready\t3\t1\t2\t2\t2\t0\t0\t0\t100\t2\t0\twayland,input,ime,clipboard\n",
            "R2\tready\t3\t1\t02\t2\t2\t0\t0\t0\t100\t2\t0\twayland,input,ime,clipboard,documents\n",
            "R2\tready\t03\t1\t2\t2\t2\t0\t0\t0\t100\t2\t0\twayland,input,ime,clipboard,documents\n",
            "R2\tready\t100\t1\t2\t2\t2\t0\t0\t0\t100\t2\t0\twayland,input,ime,clipboard,documents\n",
            "R2\tready\t3\t1\t2\t2\t2\t0\t0\t0\t0100\t2\t0\twayland,input,ime,clipboard,documents\n",
        ).forEach { wire ->
            assertThrows(IllegalStateException::class.java) {
                decodePackageLauncherReview(wire.ascii())
            }
        }
    }

    private fun String.ascii(): ByteArray = toByteArray(StandardCharsets.US_ASCII)
}
