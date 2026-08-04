package org.archphene.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StorageReportCodecTest {
    @Test
    fun decodesExactStorageReportSchemas() {
        assertEquals(
            listOf("Project", "12", "345"),
            StorageReportCodec.decodePortalFolderImport("Project\t12\t345"),
        )
        assertEquals(listOf("12", "345"), StorageReportCodec.decodeProjectMirror("12\t345"))
        assertEquals(
            listOf("document.txt", "345"),
            StorageReportCodec.decodeDocumentImport("document.txt\t345"),
        )
    }

    @Test
    fun preservesEmptyFieldsForServiceBoundaryValidation() {
        assertEquals(listOf("", "", ""), StorageReportCodec.decodePortalFolderImport("\t\t"))
        assertEquals(listOf("", ""), StorageReportCodec.decodeProjectMirror("\t"))
        assertEquals(listOf("", ""), StorageReportCodec.decodeDocumentImport("\t"))
    }

    @Test
    fun rejectsEmptyUnderflowOverflowAndLineBreaks() {
        for (value in listOf("", "Project\t1", "Project\t1\t2\t3", "Project\t1\t2\n")) {
            assertNull(StorageReportCodec.decodePortalFolderImport(value))
        }
        for (value in listOf("", "1", "1\t2\t3", "1\t2\r")) {
            assertNull(StorageReportCodec.decodeProjectMirror(value))
            assertNull(StorageReportCodec.decodeDocumentImport(value))
        }
    }

    @Test
    fun rejectsExactEightKiBTabFloodBeforeRetainingExcessFields() {
        val flood = "\t".repeat(8 * 1024)

        assertNull(StorageReportCodec.decodePortalFolderImport(flood))
        assertNull(StorageReportCodec.decodeProjectMirror(flood))
        assertNull(StorageReportCodec.decodeDocumentImport(flood))
    }
}
