package org.archphene.app.runtime

internal object AurProviderCandidatesCodec {
    private const val MAX_CANDIDATES = 32
    private val PACKAGE_NAME = Regex("[A-Za-z0-9@+._-]{1,128}")

    fun decode(value: String): List<String> {
        val candidates = ArrayList<String>(MAX_CANDIDATES)
        var lineStart = 0
        var index = 0
        while (index <= value.length) {
            val atEnd = index == value.length
            if (atEnd || value[index] == '\n' || value[index] == '\r') {
                if (!isBlank(value, lineStart, index)) {
                    require(candidates.size < MAX_CANDIDATES) {
                        "AUR provider candidate output is too large"
                    }
                    val candidate = value.substring(lineStart, index)
                    require(PACKAGE_NAME.matches(candidate) && candidate !in candidates) {
                        "AUR provider candidate output is invalid"
                    }
                    candidates.add(candidate)
                }
                if (atEnd) {
                    break
                }
                if (value[index] == '\r' && index + 1 < value.length && value[index + 1] == '\n') {
                    index++
                }
                lineStart = index + 1
            }
            index++
        }
        return candidates
    }

    private fun isBlank(
        value: String,
        start: Int,
        end: Int,
    ): Boolean {
        var index = start
        while (index < end) {
            if (!value[index].isWhitespace()) {
                return false
            }
            index++
        }
        return true
    }
}
