package org.archphene.launcher

import java.io.IOException
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class LauncherSecretStoreTest {
    @Test
    fun indexRecordsPreserveJsonShapeSeparatorsAndCount() {
        val buffer = LauncherSecretStore.BoundedIndexBuffer(512)
        buffer.append('['.code)
        var count = 0
        count =
            LauncherSecretStore.appendIndexRecord(
                buffer,
                count,
                "first",
                "First",
                "{\"account\":\"one\"}",
                "text/plain",
            )
        count =
            LauncherSecretStore.appendIndexRecord(
                buffer,
                count,
                "second",
                "Second",
                "{}",
                "application/octet-stream",
            )
        buffer.append(']'.code)

        assertEquals(2, count)
        assertEquals(
            "[{\"id\":\"first\",\"label\":\"First\",\"attributes\":{\"account\":\"one\"}," +
                "\"contentType\":\"text/plain\"},{\"id\":\"second\",\"label\":\"Second\"," +
                "\"attributes\":{},\"contentType\":\"application/octet-stream\"}]",
            String(buffer.bytes, 0, buffer.size, StandardCharsets.UTF_8),
        )
    }

    @Test
    fun indexRecordsEscapeJsonStringsWithoutAndroidJsonRuntime() {
        val buffer = LauncherSecretStore.BoundedIndexBuffer(256)
        buffer.append('['.code)
        LauncherSecretStore.appendIndexRecord(
            buffer,
            0,
            "id\"\\",
            "label\"\\",
            "{}",
            "application/example+json",
        )
        buffer.append(']'.code)

        assertEquals(
            "[{\"id\":\"id\\\"\\\\\",\"label\":\"label\\\"\\\\\"," +
                "\"attributes\":{},\"contentType\":\"application/example+json\"}]",
            String(buffer.bytes, 0, buffer.size, StandardCharsets.UTF_8),
        )
    }

    @Test
    fun indexRecordAggregationRejectsBeforeSecondObjectGrowth() {
        val probe = LauncherSecretStore.BoundedIndexBuffer(256)
        LauncherSecretStore.appendIndexRecord(probe, 0, "first", "First", "{}", "text/plain")
        val buffer = LauncherSecretStore.BoundedIndexBuffer(probe.size + 1)
        var count =
            LauncherSecretStore.appendIndexRecord(
                buffer,
                0,
                "first",
                "First",
                "{}",
                "text/plain",
            )

        try {
            count =
                LauncherSecretStore.appendIndexRecord(
                    buffer,
                    count,
                    "second",
                    "Second",
                    "{}",
                    "text/plain",
                )
            fail("Expected aggregate index rejection")
        } catch (_: IOException) {
            assertEquals(1, count)
            assertEquals(buffer.bytes.size, buffer.size)
        }
    }

    @Test
    fun boundedIndexBufferAcceptsExactMultibyteCapacity() {
        val buffer = LauncherSecretStore.BoundedIndexBuffer(5)
        buffer.append('['.code)
        buffer.append("€")
        buffer.append(']'.code)

        assertEquals(5, buffer.size)
        assertEquals("[€]", String(buffer.bytes, 0, buffer.size, StandardCharsets.UTF_8))
    }

    @Test
    fun boundedIndexBufferRejectsBeforeAggregateGrowth() {
        val buffer = LauncherSecretStore.BoundedIndexBuffer(4)
        buffer.append("[{}]")

        try {
            buffer.append(']'.code)
            fail("Expected bounded index rejection")
        } catch (_: IOException) {
            assertEquals(4, buffer.size)
            assertEquals("[{}]", String(buffer.bytes, 0, buffer.size, StandardCharsets.UTF_8))
        }
    }

    @Test
    fun catalogStringAcceptsExactBinaryCapacity() {
        val buffer = LauncherSecretStore.BoundedIndexBuffer(5)

        LauncherSecretStore.writeCatalogString(buffer, "abc")

        assertEquals(5, buffer.size)
        assertEquals(0, buffer.bytes[0].toInt())
        assertEquals(3, buffer.bytes[1].toInt())
        assertEquals("abc", String(buffer.bytes, 2, 3, StandardCharsets.UTF_8))
    }

    @Test
    fun catalogIntegersRetainDataOutputBigEndianEncoding() {
        val buffer = LauncherSecretStore.BoundedIndexBuffer(7)

        buffer.appendInt(0x41504331)
        buffer.append(2)
        buffer.appendUnsignedShort(0x0102)

        assertArrayEquals(
            byteArrayOf(0x41, 0x50, 0x43, 0x31, 0x02, 0x01, 0x02),
            buffer.bytes,
        )
    }

    @Test
    fun catalogStringRejectsBeforePrefixOrPayloadGrowth() {
        val buffer = LauncherSecretStore.BoundedIndexBuffer(4)

        try {
            LauncherSecretStore.writeCatalogString(buffer, "abc")
            fail("Expected bounded catalog rejection")
        } catch (_: IOException) {
            assertEquals(0, buffer.size)
            assertEquals(4, buffer.bytes.size)
        }
    }
}
