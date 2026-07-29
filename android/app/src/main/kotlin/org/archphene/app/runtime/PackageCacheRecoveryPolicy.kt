package org.archphene.app.runtime

internal object PackageCacheRecoveryPolicy {
    private const val MAX_CACHE_PACKAGES = 4096

    fun reclaimablePackages(
        cachedPackages: Array<String>,
        protectedPackages: Set<String>,
    ): List<String> {
        require(cachedPackages.size <= MAX_CACHE_PACKAGES)
        require(protectedPackages.size <= MAX_CACHE_PACKAGES)
        require(
            strictlyOrdered(cachedPackages) &&
                cachedPackages.all(::safePackageName) &&
                protectedPackages.all(::safePackageName),
        )
        return cachedPackages.filterNot(protectedPackages::contains)
    }

    fun protectedPackages(
        cachedPackages: Array<String>,
        officialClosure: Collection<String>?,
        verifiedAurBuildClosure: Collection<String>?,
    ): HashSet<String> {
        require(cachedPackages.size <= MAX_CACHE_PACKAGES)
        require(strictlyOrdered(cachedPackages) && cachedPackages.all(::safePackageName))
        require(validClosure(officialClosure))
        require(validClosure(verifiedAurBuildClosure))
        return (
            verifiedAurBuildClosure
                ?: officialClosure
                ?: cachedPackages.asList()
        ).toHashSet()
    }

    private fun validClosure(values: Collection<String>?): Boolean =
        values == null ||
            (
                values.size <= MAX_CACHE_PACKAGES &&
                    values.all(::safePackageName)
            )

    private fun strictlyOrdered(values: Array<String>): Boolean {
        for (index in 1 until values.size) {
            if (values[index - 1] >= values[index]) {
                return false
            }
        }
        return true
    }

    private fun safePackageName(value: String): Boolean =
        value.isNotEmpty() &&
            value.length <= 128 &&
            value.none(Char::isWhitespace)
}
