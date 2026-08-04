package org.archphene.app.runtime

internal object PackageCacheSummaryCodec {
    private const val NUMBER_COUNT = 2

    fun decode(value: String): LongArray? {
        var end = value.length
        while (end > 0 && value[end - 1] == '\n') end--
        if (end < 3 || value[0] != 'C' || value[1] != '1' || value[2] != '\t') {
            return null
        }

        val numbers = LongArray(NUMBER_COUNT)
        var fieldStart = 3
        for (index in numbers.indices) {
            val delimiter = value.indexOf('\t', fieldStart)
            val fieldEnd =
                if (index < numbers.lastIndex) {
                    if (delimiter <= fieldStart || delimiter >= end) return null
                    delimiter
                } else {
                    if (delimiter >= fieldStart && delimiter < end) return null
                    end
                }
            numbers[index] = value.substring(fieldStart, fieldEnd).toLongOrNull() ?: return null
            fieldStart = fieldEnd + 1
        }
        return numbers
    }
}
