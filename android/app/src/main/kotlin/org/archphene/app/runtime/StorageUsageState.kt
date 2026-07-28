package org.archphene.app.runtime

internal const val STORAGE_USAGE_ENTRY_LIMIT = 2_000_000L

internal data class NativeStorageUsage(
    val packageDownloadsBytes: Long,
    val sharedRuntimeBytes: Long,
    val buildCacheBytes: Long,
    val userFilesBytes: Long,
)

internal fun decodeNativeStorageUsage(value: String): NativeStorageUsage {
    val fields = value.removeSuffix("\n").split('\t')
    require(fields.size == 9 && fields[0] == "S1") {
        "Storage inventory returned an invalid summary"
    }
    val numbers =
        LongArray(8) { index ->
            fields[index + 1].toLongOrNull()
                ?: throw IllegalArgumentException("Storage inventory returned an invalid value")
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
