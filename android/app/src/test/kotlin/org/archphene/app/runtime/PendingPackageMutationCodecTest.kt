package org.archphene.app.runtime

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PendingPackageMutationCodecTest {
    @Test
    fun decodesInstallRollbackAndRemovalRecords() {
        assertEquals(
            PendingPackageMutation(
                packageName = "dotnet-sdk-bin",
                status = "install\t10.0.10.sdk302-1\trollback",
                install = true,
            ),
            decode("dotnet-sdk-bin\tinstall\t10.0.10.sdk302-1\trollback"),
        )
        assertEquals(
            PendingPackageMutation(
                packageName = "foot",
                status = "remove\t1.27.0-2",
                install = false,
            ),
            decode("foot\tremove\t1.27.0-2"),
        )
    }

    @Test
    fun rejectsMalformedOrUnboundedRecords() {
        listOf(
            "",
            "../foot\tremove\t1.27.0-2",
            "foot\tunknown\t1.27.0-2",
            "foot\tremove\t1.27.0-2\trollback",
            "foot\tinstall\t1.27.0-2\textra",
            "foot\tinstall\tbad version",
            "foot\tinstall\t1.0\n",
        ).forEach { wire ->
            assertThrows(IllegalArgumentException::class.java) {
                decode(wire)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            PendingPackageMutationCodec.decode(ByteArray(16 * 1024 + 1))
        }
    }

    private fun decode(wire: String): PendingPackageMutation =
        PendingPackageMutationCodec.decode(
            wire.toByteArray(StandardCharsets.UTF_8),
        )
}
