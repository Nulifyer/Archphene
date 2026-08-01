package org.archphene.builder

import java.io.RandomAccessFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SentinelProbeTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun readableEmptyFileSucceeds() {
        assertTrue(canReadSentinel(temporaryFolder.newFile("empty")))
    }

    @Test
    fun missingFileFails() {
        assertFalse(canReadSentinel(temporaryFolder.root.resolve("missing")))
    }

    @Test
    fun sparseFileLargerThanJvmArrayLimitDoesNotGetReadIntoMemory() {
        val sentinel = temporaryFolder.newFile("large")
        RandomAccessFile(sentinel, "rw").use { file ->
            file.setLength(3L * 1024 * 1024 * 1024)
        }

        assertTrue(canReadSentinel(sentinel))
    }
}
