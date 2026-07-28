package org.archphene.app.runtime

internal object AurBuildConcurrency {
    private const val GIBIBYTE = 1024L * 1024L * 1024L
    const val MAXIMUM_JOBS = 4

    fun recommendedJobs(
        availableProcessors: Int,
        availableMemoryBytes: Long,
        lowMemory: Boolean,
        thermalStatus: Int,
    ): Int {
        if (
            lowMemory ||
            availableProcessors <= 0 ||
            availableMemoryBytes <= 0L ||
            thermalStatus >= 3
        ) {
            return 1
        }
        val cpuLimit = availableProcessors.coerceAtMost(MAXIMUM_JOBS)
        val memoryLimit =
            when {
                availableMemoryBytes < 3L * GIBIBYTE -> 1
                availableMemoryBytes < 6L * GIBIBYTE -> 2
                else -> MAXIMUM_JOBS
            }
        val thermalLimit = if (thermalStatus >= 2) 2 else MAXIMUM_JOBS
        return minOf(cpuLimit, memoryLimit, thermalLimit).coerceAtLeast(1)
    }
}
