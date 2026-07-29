package org.archphene.app.runtime

import java.nio.charset.CharacterCodingException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PackageInstallPlanCodecTest {
    @Test
    fun exact_removal_plan_decodes_in_order() {
        assertEquals(
            listOf(
                PlannedPackageRemoval("old-name", "1.0-1"),
                PlannedPackageRemoval("old-library", "2:3.4-5"),
            ),
            PackageInstallPlanCodec.decode(
                (
                    "org.archphene.package-install-plan.v1\n" +
                        "removals\t2\n" +
                        "remove\told-name\t1.0-1\n" +
                        "remove\told-library\t2:3.4-5\n"
                ).toByteArray(),
            ),
        )
        assertEquals(
            emptyList<PlannedPackageRemoval>(),
            PackageInstallPlanCodec.decode(
                (
                    "org.archphene.package-install-plan.v1\n" +
                        "removals\t0\n"
                ).toByteArray(),
            ),
        )

        val aurPlan =
            (
                "org.archphene.aur-install-plan.v1\n" +
                    "removals\t1\n" +
                    "remove\tdotnet-runtime\t10.0.4.sdk104-1\n"
            ).toByteArray()
        assertEquals(true, AurPackageInstallPlanCodec.isPlan(aurPlan))
        assertEquals(
            listOf(PlannedPackageRemoval("dotnet-runtime", "10.0.4.sdk104-1")),
            AurPackageInstallPlanCodec.decode(aurPlan),
        )
        assertEquals(
            false,
            AurPackageInstallPlanCodec.isPlan(
                "org.archphene.package-install-plan.v1\nremovals\t0\n".toByteArray(),
            ),
        )
    }

    @Test
    fun malformed_or_ambiguous_plans_fail_closed() {
        for (invalid in listOf(
            "",
            "org.archphene.package-install-plan.v1\nremovals\t0",
            "org.archphene.package-install-plan.v1\nremovals\t1\n",
            "org.archphene.package-install-plan.v1\nremovals\t0\nextra\n",
            "org.archphene.package-install-plan.v1\nremovals\t1\n" +
                "remove\t../old\t1.0-1\n",
            "org.archphene.package-install-plan.v1\nremovals\t2\n" +
                "remove\told\t1.0-1\nremove\told\t2.0-1\n",
            "different\nremovals\t0\n",
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                PackageInstallPlanCodec.decode(invalid.toByteArray())
            }
        }
        assertThrows(CharacterCodingException::class.java) {
            PackageInstallPlanCodec.decode(
                byteArrayOf(0xc3.toByte(), 0x28),
            )
        }
    }
}
