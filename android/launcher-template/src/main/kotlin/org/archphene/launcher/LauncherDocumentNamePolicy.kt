package org.archphene.launcher

internal object LauncherDocumentNamePolicy {
    const val MAX_UTF16 = 255
    const val MAX_UTF8_BYTES = 255

    fun valid(name: String): Boolean {
        if (name.length !in 1..MAX_UTF16 || name == "." || name == "..") return false
        var bytes = 0
        var index = 0
        while (index < name.length) {
            val character = name[index]
            if (
                character == '/' ||
                character == '\\' ||
                character == '\u0000' ||
                character.code < 32 ||
                character.code == 127
            ) {
                return false
            }
            bytes +=
                when {
                    character.code <= 0x7f -> 1
                    character.code <= 0x7ff -> 2
                    character.isHighSurrogate() -> {
                        if (
                            index + 1 >= name.length ||
                            !name[index + 1].isLowSurrogate()
                        ) {
                            return false
                        }
                        index++
                        4
                    }
                    character.isLowSurrogate() -> return false
                    else -> 3
                }
            if (bytes > MAX_UTF8_BYTES) return false
            index++
        }
        return true
    }
}
