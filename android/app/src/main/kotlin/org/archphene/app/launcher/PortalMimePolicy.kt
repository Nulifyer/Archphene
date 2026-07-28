package org.archphene.app.launcher

import java.util.Locale

internal object PortalMimePolicy {
    const val MAX_SPEC_UTF16 = 2048
    const val MAX_TYPES = 16
    private const val MAX_TYPE_UTF16 = 127

    fun parse(spec: String): List<String>? {
        if (spec.isEmpty() || spec.length > MAX_SPEC_UTF16) {
            return null
        }
        val values = spec.split(';', limit = MAX_TYPES + 1)
        if (values.size !in 1..MAX_TYPES) {
            return null
        }
        val unique = HashSet<String>(values.size)
        val normalized = ArrayList<String>(values.size)
        for (rawValue in values) {
            val value = rawValue.lowercase(Locale.ROOT)
            if (!safeType(value) || !unique.add(value)) {
                return null
            }
            normalized.add(value)
        }
        return normalized
    }

    fun valid(spec: String): Boolean = parse(spec) != null

    private fun safeType(value: String): Boolean {
        if (
            value.length !in 3..MAX_TYPE_UTF16 ||
            value.any { character -> !safeMimeCharacter(character) }
        ) {
            return false
        }
        val separator = value.indexOf('/')
        if (
            separator !in 1 until value.lastIndex ||
            separator != value.lastIndexOf('/')
        ) {
            return false
        }
        val topLevel = value.substring(0, separator)
        val subType = value.substring(separator + 1)
        if ('*' in topLevel && topLevel != "*") {
            return false
        }
        if ('*' in subType && subType != "*") {
            return false
        }
        return topLevel != "*" || subType == "*"
    }

    private fun safeMimeCharacter(character: Char): Boolean =
        character in 'a'..'z' ||
            character in 'A'..'Z' ||
            character in '0'..'9' ||
            character == '!' ||
            character == '#' ||
            character == '$' ||
            character == '&' ||
            character == '^' ||
            character == '_' ||
            character == '.' ||
            character == '+' ||
            character == '-' ||
            character == '*' ||
            character == '/'
}
