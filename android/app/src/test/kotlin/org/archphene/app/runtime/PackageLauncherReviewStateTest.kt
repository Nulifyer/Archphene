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
                observedTopology = 0x500,
                observedLaunchers = 1,
                incompleteObservations = 0,
                bridgeCapabilities = 0,
                unavailableBridgeCapabilities = 0,
            ),
            decodePackageLauncherReview(
                "R4\tready\t3\t1\t2\t2\t2\t0\t0\t0\t100\t2\t0\t500\t1\t0\t0\t0\twayland,input,ime,clipboard,documents,open-uri,notifications\n"
                    .ascii(),
            ),
        )
        assertEquals(
            "pending",
            decodePackageLauncherReview(
                "R4\tpending\tff\t1\t2\t2\t1\t1\t0\t0\t102\t2\t1\t100\t1\t1\t0\t0\twayland,input,ime,clipboard,documents,open-uri,notifications\n"
                    .ascii(),
            ).status,
        )
        assertEquals(
            0xff,
            decodePackageLauncherReview(
                "R4\tpending\tff\t1\t2\t2\t1\t1\t0\t0\t102\t2\t1\t100\t1\t1\t0\t0\twayland,input,ime,clipboard,documents,open-uri,notifications\n"
                    .ascii(),
            ).capabilities,
        )
    }

    @Test
    fun decodesOnlyBoundedUnavailableBridgeSubsets() {
        val review =
            decodePackageLauncherReview(
                "R4\tready\t3\t1\t1\t1\t1\t0\t0\t0\t4\t1\t0\t4\t1\t0\t3\t3\twayland,input,ime,clipboard,documents,open-uri,notifications\n"
                    .ascii(),
            )
        assertEquals(3, review.bridgeCapabilities)
        assertEquals(3, review.unavailableBridgeCapabilities)

        val audioInput =
            decodePackageLauncherReview(
                "R4\tready\t3\t1\t1\t1\t1\t0\t0\t0\t4\t1\t0\t4\t1\t0\t11\t10\twayland,input,ime,clipboard,documents,open-uri,notifications\n"
                    .ascii(),
            )
        assertEquals(0x11, audioInput.bridgeCapabilities)
        assertEquals(0x10, audioInput.unavailableBridgeCapabilities)

        assertThrows(IllegalStateException::class.java) {
            decodePackageLauncherReview(
                "R4\tready\t3\t1\t1\t1\t1\t0\t0\t0\t4\t1\t0\t4\t1\t0\t1\t2\twayland,input,ime,clipboard,documents,open-uri,notifications\n"
                    .ascii(),
            )
        }
    }

    @Test
    fun rejectsContradictoryOrUnboundedLauncherReviews() {
        listOf(
            "R4\tready\t3\t1\t2\t2\t1\t1\t0\t0\t100\t2\t0\t100\t1\t0\t0\t0\twayland,input,ime,clipboard,documents,open-uri,notifications\n",
            "R4\tpending\t3\t1\t2\t2\t2\t0\t0\t0\t100\t2\t0\t100\t1\t0\t0\t0\twayland,input,ime,clipboard,documents,open-uri,notifications\n",
            "R4\tfailed\t3\t1\t2\t2\t1\t0\t0\t0\t100\t2\t0\t100\t1\t0\t0\t0\twayland,input,ime,clipboard,documents,open-uri,notifications\n",
            "R4\tnot-installed\t1\t0\t0\t0\t0\t0\t0\t0\t0\t0\t0\t0\t0\t0\t0\t0\twayland,input,ime,clipboard,documents,open-uri,notifications\n",
            "R4\tready\t3\t1\t257\t2\t2\t0\t0\t0\t100\t2\t0\t100\t1\t0\t0\t0\twayland,input,ime,clipboard,documents,open-uri,notifications\n",
            "R4\tready\t3\t1\t2\t3\t2\t0\t0\t0\t100\t2\t0\t100\t1\t0\t0\t0\twayland,input,ime,clipboard,documents,open-uri,notifications\n",
            "R4\tready\t3\t1\t2\t2\t2\t0\t0\t0\t100\t3\t0\t100\t1\t0\t0\t0\twayland,input,ime,clipboard,documents,open-uri,notifications\n",
            "R4\tready\t3\t1\t2\t2\t2\t0\t0\t0\t100\t2\t3\t100\t1\t0\t0\t0\twayland,input,ime,clipboard,documents,open-uri,notifications\n",
            "R4\tready\t3\t1\t2\t2\t2\t0\t0\t0\t100\t0\t0\t100\t1\t0\t0\t0\twayland,input,ime,clipboard,documents,open-uri,notifications\n",
            "R4\tready\t3\t1\t2\t2\t2\t0\t0\t0\t80\t2\t0\t100\t1\t0\t0\t0\twayland,input,ime,clipboard,documents,open-uri,notifications\n",
            "R4\tready\t3\t1\t2\t2\t2\t0\t0\t0\t100\t2\t0\t100\t3\t0\t0\t0\twayland,input,ime,clipboard,documents,open-uri,notifications\n",
            "R4\tready\t3\t1\t2\t2\t2\t0\t0\t0\t100\t2\t0\t100\t1\t2\t0\t0\twayland,input,ime,clipboard,documents,open-uri,notifications\n",
            "R4\tready\t3\t1\t2\t2\t2\t0\t0\t0\t100\t2\t0\t100\t0\t0\t0\t0\twayland,input,ime,clipboard,documents,open-uri,notifications\n",
            "R4\tready\t3\t1\t2\t2\t2\t0\t0\t0\t100\t2\t0\t80\t1\t0\t0\t0\twayland,input,ime,clipboard,documents,open-uri,notifications\n",
            "R4\tready\t3\t1\t2\t2\t2\t0\t0\t0\t100\t2\t0\t100\t1\t0\t0\t0\twayland,input,ime,clipboard\n",
            "R4\tready\t3\t1\t02\t2\t2\t0\t0\t0\t100\t2\t0\t100\t1\t0\t0\t0\twayland,input,ime,clipboard,documents,open-uri,notifications\n",
            "R4\tready\t03\t1\t2\t2\t2\t0\t0\t0\t100\t2\t0\t100\t1\t0\t0\t0\twayland,input,ime,clipboard,documents,open-uri,notifications\n",
            "R4\tready\t100\t1\t2\t2\t2\t0\t0\t0\t100\t2\t0\t100\t1\t0\t0\t0\twayland,input,ime,clipboard,documents,open-uri,notifications\n",
            "R4\tready\t3\t1\t2\t2\t2\t0\t0\t0\t0100\t2\t0\t100\t1\t0\t0\t0\twayland,input,ime,clipboard,documents,open-uri,notifications\n",
            "R4\tready\t3\t1\t2\t2\t2\t0\t0\t0\t100\t2\t0\t0100\t1\t0\t0\t0\twayland,input,ime,clipboard,documents,open-uri,notifications\n",
        ).forEach { wire ->
            assertThrows(IllegalStateException::class.java) {
                decodePackageLauncherReview(wire.ascii())
            }
        }
    }

    @Test
    fun rejectsMaximumSizeTabFlood() {
        val wire = ByteArray(NativeRuntime.PACKAGE_OUTPUT_SIZE) { '\t'.code.toByte() }
        wire[wire.lastIndex] = '\n'.code.toByte()

        assertThrows(IllegalStateException::class.java) {
            decodePackageLauncherReview(wire)
        }
    }

    private fun String.ascii(): ByteArray = toByteArray(StandardCharsets.US_ASCII)
}
