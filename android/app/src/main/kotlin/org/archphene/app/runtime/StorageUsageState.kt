package org.archphene.app.runtime

internal const val STORAGE_USAGE_ENTRY_LIMIT = 2_000_000L

internal data class NativeStorageUsage(
    val packageDownloadsBytes: Long,
    val sharedRuntimeBytes: Long,
    val buildCacheBytes: Long,
    val userFilesBytes: Long,
)

internal fun decodeNativeStorageUsage(value: String): NativeStorageUsage {
    require(value.length >= 3 && value[0] == 'S' && value[1] == '1' && value[2] == '\t') {
        "Storage inventory returned an invalid summary"
    }
    val numbers = LongArray(8)
    var fieldStart = 3
    for (index in numbers.indices) {
        var fieldEnd = fieldStart
        while (fieldEnd < value.length && value[fieldEnd] != '\t' && value[fieldEnd] != '\n') {
            fieldEnd++
        }
        if (index < numbers.lastIndex) {
            require(fieldEnd < value.length && value[fieldEnd] == '\t') {
                "Storage inventory returned an invalid summary"
            }
        } else {
            require(fieldEnd == value.length || (fieldEnd == value.lastIndex && value[fieldEnd] == '\n')) {
                "Storage inventory returned an invalid summary"
            }
        }
        numbers[index] = value.substring(fieldStart, fieldEnd).toLongOrNull()
            ?: throw IllegalArgumentException("Storage inventory returned an invalid value")
        fieldStart = fieldEnd + 1
    }
    require(numbers.all { number -> number >= 0L }) {
        "Storage inventory returned a negative value"
    }
    var entries = 0L
    try {
        for (index in numbers.indices step 2) {
            entries = Math.addExact(entries, numbers[index])
        }
        Math.addExact(
            Math.addExact(numbers[1], numbers[3]),
            Math.addExact(numbers[5], numbers[7]),
        )
    } catch (_: ArithmeticException) {
        throw IllegalArgumentException("Storage inventory overflowed")
    }
    require(entries <= STORAGE_USAGE_ENTRY_LIMIT) {
        "Storage inventory exceeds its entry limit"
    }
    return NativeStorageUsage(
        packageDownloadsBytes = numbers[1],
        sharedRuntimeBytes = numbers[3],
        buildCacheBytes = numbers[5],
        userFilesBytes = numbers[7],
    )
}
