package org.archphene.app.runtime

internal object AurCandidateStateCodec {
    fun decode(value: String): List<String>? {
        if (value.isEmpty() || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) return null
        val delimiter = value.indexOf('\t')
        if (delimiter < 0 || value.indexOf('\t', delimiter + 1) >= 0) return null
        return listOf(value.substring(0, delimiter), value.substring(delimiter + 1))
    }
}
