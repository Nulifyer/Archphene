package org.archphene.app.storage

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BoundedDebugControlTest {
    @Test
    fun acceptsExactLimitUtf8() {
        val file = Files.createTempFile("archphene-debug-control", null).toFile()
        try {
            file.writeBytes(ByteArray(256) { 'x'.code.toByte() })

            assertEquals("x".repeat(256), readBoundedDebugControl(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsLimitPlusOneBeforeStringDecode() {
        val file = Files.createTempFile("archphene-debug-control", null).toFile()
        try {
            file.writeBytes(ByteArray(257) { 'x'.code.toByte() })

            assertThrows(IllegalStateException::class.java) {
                readBoundedDebugControl(file)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun preservesUtf8ControlText() {
        val file = Files.createTempFile("archphene-debug-control", null).toFile()
        try {
            val value = "operação\n"
            file.writeBytes(value.toByteArray(StandardCharsets.UTF_8))

            assertEquals(value, readBoundedDebugControl(file))
        } finally {
            file.delete()
        }
    }
}
