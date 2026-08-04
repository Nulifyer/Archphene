package org.archphene.launcher

internal object LauncherUtf8Policy {
    fun lengthAtMost(
        value: String,
        maximumBytes: Int,
    ): Boolean {
        if (maximumBytes < 0) return false
        var bytes = 0
        var index = 0
        while (index < value.length) {
            val character = value[index]
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
            if (bytes > maximumBytes) return false
            index++
        }
        return true
    }
}
