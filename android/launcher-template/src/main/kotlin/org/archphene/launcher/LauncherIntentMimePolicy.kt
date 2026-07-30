package org.archphene.launcher

internal object LauncherIntentMimePolicy {
    private const val MAX_TYPES = 16
    private const val MAX_SPEC_UTF16 = 2_080

    fun parseSpec(spec: String): List<String>? {
        if (spec.length > MAX_SPEC_UTF16) return null
        if (spec.isEmpty()) return emptyList()
        val types = spec.split(';')
        return types.takeIf {
            it.size <= MAX_TYPES &&
                it.distinct().size == it.size &&
                it.all(::valid)
        }
    }

    fun matches(
        declared: List<String>,
        actual: String,
    ): Boolean {
        if (!valid(actual) || actual.contains('*')) return false
        val slash = actual.indexOf('/')
        return declared.any { accepted ->
            accepted.equals(actual, ignoreCase = true) ||
                (
                    accepted.endsWith("/*") &&
                        accepted.regionMatches(
                            ignoreCase = true,
                            thisOffset = 0,
                            other = actual,
                            otherOffset = 0,
                            length = slash + 1,
                        )
                )
        }
    }

    private fun valid(value: String): Boolean {
        val slash = value.indexOf('/')
        return value.length in 3..129 &&
            slash in 1 until value.lastIndex &&
            value.indexOf('/', slash + 1) < 0 &&
            value.none { character ->
                character.isWhitespace() ||
                    character.isISOControl() ||
                    character == ';' ||
                    character == '\u0000'
            }
    }
}
