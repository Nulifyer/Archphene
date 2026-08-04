package org.archphene.app.runtime

internal fun checkedNativeOutputLength(
    length: Int,
    capacity: Int,
    maximumLength: Int = capacity,
): Int {
    check(maximumLength in 0..capacity && length in 0..maximumLength) {
        "Native output length is invalid"
    }
    return length
}
