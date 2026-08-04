package org.archphene.app.runtime

internal object ProjectSyncSummaryCodec {
    private const val FIELD_COUNT = 7

    fun decode(
        value: String,
        maximumEntries: Int,
    ): IntArray {
        require(maximumEntries >= 0)
        val counts = IntArray(FIELD_COUNT)
        var fieldStart = 0
        for (index in counts.indices) {
            val delimiter = value.indexOf('\t', fieldStart)
            val fieldEnd =
                if (index < counts.lastIndex) {
                    check(delimiter > fieldStart) {
                        "Native synchronization summary is invalid"
                    }
                    delimiter
                } else {
                    check(delimiter < 0) {
                        "Native synchronization summary is invalid"
                    }
                    value.length
                }
            counts[index] =
                value.substring(fieldStart, fieldEnd).toIntOrNull()
                    ?.takeIf { it in 0..maximumEntries }
                    ?: error(
                        if (index == 0) {
                            "Native synchronization count is invalid"
                        } else {
                            "Native synchronization summary count is invalid"
                        },
                    )
            fieldStart = fieldEnd + 1
        }
        return counts
    }
}
