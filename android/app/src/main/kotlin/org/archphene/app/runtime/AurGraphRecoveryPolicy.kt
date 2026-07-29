package org.archphene.app.runtime

internal object AurGraphRecoveryPolicy {
    fun completedBaseCount(
        requiredPackageCounts: IntArray,
        outputCount: Int,
    ): Int {
        require(outputCount > 0)
        var expected = 0
        var completedBaseCount: Int? = null
        requiredPackageCounts.forEachIndexed { index, requiredPackageCount ->
            require(requiredPackageCount > 0)
            expected = Math.addExact(expected, requiredPackageCount)
            if (expected == outputCount) {
                completedBaseCount = index + 1
            }
        }
        return completedBaseCount
            ?: throw IllegalArgumentException(
                "AUR graph output does not end at a reviewed package-base boundary",
            )
    }
}
