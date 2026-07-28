package org.archphene.app.runtime

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PackageCompatibilityStateTest {
    @Test
    fun decodesBoundedCompatibilityStates() {
        assertEquals(
            PackageCompatibility(
                status = "bridge-eligible",
                capabilities = 3,
                packageCount = 12,
                elfCount = 47,
                commandCount = 2,
                diagnostic = "none",
                diagnosticPackage = null,
            ),
            decodePackageCompatibility("bridge-eligible\t3\t12\t47\t2\tnone\t-\n".ascii()),
        )
        assertEquals(
            PackageCompatibility(
                status = "not-analyzed",
                capabilities = 0,
                packageCount = 4,
                elfCount = 0,
                commandCount = 0,
                diagnostic = "not-cached",
                diagnosticPackage = null,
            ),
            decodePackageCompatibility("not-analyzed\t0\t4\t0\t0\tnot-cached\t-\n".ascii()),
        )
        assertEquals(
            PackageCompatibility(
                status = "unsupported",
                capabilities = 2,
                packageCount = 3,
                elfCount = 8,
                commandCount = 1,
                diagnostic = "foreign-elf",
                diagnosticPackage = "glibc",
            ),
            decodePackageCompatibility(
                "unsupported\t2\t3\t8\t1\tforeign-elf\tglibc\n".ascii(),
            ),
        )
    }

    @Test
    fun rejectsContradictoryOrUnboundedCompatibilityStates() {
        listOf(
            "bridge-eligible\t3\t12\t47\t2\tforeign-elf\tglibc\n",
            "unsupported\t2\t3\t8\t1\tnone\tglibc\n",
            "unsupported\t2\t3\t8\t1\tincompatible-page-size\t-\n",
            "not-analyzed\t1\t4\t0\t0\tnot-cached\t-\n",
            "bridge-eligible\t3\t513\t47\t2\tnone\t-\n",
            "bridge-eligible\tA\t12\t47\t2\tnone\t-\n",
            "bridge-eligible\t3\t+12\t47\t2\tnone\t-\n",
            "bridge-eligible\t3\t012\t47\t2\tnone\t-\n",
            "unsupported\t2\t3\t8\t1\tforeign-elf\t../glibc\n",
            "bridge-eligible\t3\t12\t47\t2\tnone\t-",
            "bridge-eligible\t3\t12\t47\t2\tnone\t-\nextra\n",
        ).forEach { wire ->
            assertThrows(IllegalStateException::class.java) {
                decodePackageCompatibility(wire.ascii())
            }
        }
    }

    private fun String.ascii(): ByteArray = toByteArray(StandardCharsets.US_ASCII)
}
