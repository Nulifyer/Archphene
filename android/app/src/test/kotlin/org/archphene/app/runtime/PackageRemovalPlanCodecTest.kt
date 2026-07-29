package org.archphene.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PackageRemovalPlanCodecTest {
    @Test
    fun exact_target_and_unused_dependencies_decode_in_order() {
        assertEquals(
            listOf(
                PlannedPackageRemoval("tool", "2.0-1"),
                PlannedPackageRemoval("unused-library", "1:3.4-2"),
            ),
            PackageRemovalPlanCodec.decode(
                (
                    "org.archphene.package-removal-plan.v1\n" +
                        "removals\t2\n" +
                        "remove\ttool\t2.0-1\n" +
                        "remove\tunused-library\t1:3.4-2\n"
                ).toByteArray(),
            ),
        )
    }

    @Test
    fun install_plan_header_is_not_removal_authority() {
        assertThrows(IllegalArgumentException::class.java) {
            PackageRemovalPlanCodec.decode(
                (
                    "org.archphene.package-install-plan.v1\n" +
                        "removals\t1\n" +
                        "remove\ttool\t2.0-1\n"
                ).toByteArray(),
            )
        }
    }
}
