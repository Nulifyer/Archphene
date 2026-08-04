package org.archphene.app.runtime

internal object StorageReportCodec {
    fun decodePortalFolderImport(value: String): List<String>? = decode(value, 3)

    fun decodeProjectMirror(value: String): List<String>? = decode(value, 2)

    fun decodeDocumentImport(value: String): List<String>? = decode(value, 2)

    private fun decode(
        value: String,
        fieldCount: Int,
    ): List<String>? {
        if (value.isEmpty() || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) return null

        val fields = ArrayList<String>(fieldCount)
        var fieldStart = 0
        repeat(fieldCount - 1) {
            val fieldEnd = value.indexOf('\t', fieldStart)
            if (fieldEnd < fieldStart) return null
            fields.add(value.substring(fieldStart, fieldEnd))
            fieldStart = fieldEnd + 1
        }
        if (value.indexOf('\t', fieldStart) >= fieldStart) return null
        fields.add(value.substring(fieldStart))
        return fields
    }
}
