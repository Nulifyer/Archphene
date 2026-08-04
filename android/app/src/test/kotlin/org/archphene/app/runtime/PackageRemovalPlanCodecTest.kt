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

    @Test
    fun exact_removal_limit_decodes_without_collecting_all_lines() {
        val plan =
            buildString {
                append("org.archphene.package-removal-plan.v1\nremovals\t48\n")
                repeat(48) { index -> append("remove\tpackage-$index\t1.0-$index\n") }
            }
        assertEquals(48, PackageRemovalPlanCodec.decode(plan.toByteArray()).size)
    }

    @Test
    fun excess_lines_and_fields_are_rejected_during_parsing() {
        val excessLines =
            buildString(16 * 1024) {
                append("org.archphene.package-removal-plan.v1\nremovals\t0\n")
                while (length < 16 * 1024) append('\n')
            }
        assertEquals(16 * 1024, excessLines.toByteArray().size)
        assertThrows(IllegalArgumentException::class.java) {
            PackageRemovalPlanCodec.decode(excessLines.toByteArray())
        }

        val excessFields =
            buildString(16 * 1024) {
                append("org.archphene.package-removal-plan.v1\nremovals\t1\n")
                while (length < 16 * 1024 - 1) append(if (length % 2 == 0) 'x' else '\t')
                append('\n')
            }
        assertEquals(16 * 1024, excessFields.toByteArray().size)
        assertThrows(IllegalArgumentException::class.java) {
            PackageRemovalPlanCodec.decode(excessFields.toByteArray())
        }
    }
}
