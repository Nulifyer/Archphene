package org.archphene.launcher

internal object LauncherNotificationPolicy {
    const val MAX_PENDING = 32

    fun valid(
        id: String,
        title: String,
        body: String,
    ): Boolean =
        validId(id) &&
            validText(title, 256, 1_024, allowWhitespace = false) &&
            validText(body, 4_096, 8_192, allowWhitespace = true)

    fun validId(id: String): Boolean =
        validText(id, 128, 512, allowWhitespace = false)

    private fun validText(
        value: String,
        maximumCharacters: Int,
        maximumUtf8Bytes: Int,
        allowWhitespace: Boolean,
    ): Boolean {
        if (value.isEmpty() || value.length > maximumCharacters) return false
        var bytes = 0
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (
                character == '\u0000' ||
                (
                    character.isISOControl() &&
                        !(allowWhitespace && (character == '\n' || character == '\t'))
                )
            ) {
                return false
            }
            bytes +=
                when {
                    character.code <= 0x7f -> 1
                    character.code <= 0x7ff -> 2
                    character.isHighSurrogate() -> {
                        if (
                            index + 1 >= value.length ||
                            !value[index + 1].isLowSurrogate()
                        ) {
                            return false
                        }
                        index++
                        4
                    }
                    character.isLowSurrogate() -> return false
                    else -> 3
                }
            if (bytes > maximumUtf8Bytes) return false
            index++
        }
        return true
    }
}
