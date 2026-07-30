package org.archphene.app.runtime

import java.nio.charset.StandardCharsets
import org.archphene.app.launcher.LauncherApkAssembler

internal data class PackageLauncherReview(
    val status: String,
    val capabilities: Int,
    val capabilitiesAnalyzed: Boolean,
    val launchers: Int,
    val verifiedExecutables: Int,
    val current: Int,
    val pending: Int,
    val attention: Int,
    val failed: Int,
    val integrationTopology: Int,
    val profiledExecutables: Int,
    val incompleteProfiles: Int,
    val observedTopology: Int,
    val observedLaunchers: Int,
    val incompleteObservations: Int,
)

private const val INTEGRATION_TOPOLOGY_MASK = 0x0f7f

internal fun decodePackageLauncherReview(bytes: ByteArray): PackageLauncherReview {
    val text = String(bytes, StandardCharsets.US_ASCII)
    if (!text.endsWith('\n') || text.count { character -> character == '\n' } != 1) {
        throw IllegalStateException("Rust returned invalid package launcher review")
    }
    val fields = text.dropLast(1).split('\t')
    val status = fields.getOrNull(1)
    val capabilities = reviewCapabilities(fields.getOrNull(2))
    val analyzed =
        when (fields.getOrNull(3)) {
            "0" -> false
            "1" -> true
            else -> null
        }
    val launchers = reviewCount(fields.getOrNull(4))
    val verifiedExecutables = reviewCount(fields.getOrNull(5))
    val current = reviewCount(fields.getOrNull(6))
    val pending = reviewCount(fields.getOrNull(7))
    val attention = reviewCount(fields.getOrNull(8))
    val failed = reviewCount(fields.getOrNull(9))
    val integrationTopology = reviewHex(fields.getOrNull(10), 4, 0xffff)
    val profiledExecutables = reviewCount(fields.getOrNull(11))
    val incompleteProfiles = reviewCount(fields.getOrNull(12))
    val observedTopology = reviewHex(fields.getOrNull(13), 4, 0xffff)
    val observedLaunchers = reviewCount(fields.getOrNull(14))
    val incompleteObservations = reviewCount(fields.getOrNull(15))
    val validStatus =
        when (status) {
            "not-installed",
            "no-launcher",
            "ready",
            "pending",
            "attention",
            "failed",
            "unavailable",
            -> true
            else -> false
        }
    if (
        fields.size != 17 ||
        fields[0] != "R3" ||
        !validStatus ||
        capabilities == null ||
        analyzed == null ||
        launchers == null ||
        verifiedExecutables == null ||
        current == null ||
        pending == null ||
        attention == null ||
        failed == null ||
        integrationTopology == null ||
        profiledExecutables == null ||
        incompleteProfiles == null ||
        observedTopology == null ||
        observedLaunchers == null ||
        incompleteObservations == null ||
        fields[16] != LauncherApkAssembler.CAPABILITIES_V4 ||
        verifiedExecutables > launchers ||
        profiledExecutables > launchers ||
        incompleteProfiles > profiledExecutables ||
        profiledExecutables == 0 && integrationTopology != 0 ||
        (integrationTopology and INTEGRATION_TOPOLOGY_MASK.inv()) != 0 ||
        observedLaunchers > launchers ||
        incompleteObservations > observedLaunchers ||
        observedLaunchers == 0 && observedTopology != 0 ||
        (observedTopology and INTEGRATION_TOPOLOGY_MASK.inv()) != 0 ||
        current + pending + attention + failed > launchers ||
        status == "not-installed" &&
            (
                capabilities != 0 ||
                    analyzed ||
                    launchers != 0 ||
                    verifiedExecutables != 0 ||
                    integrationTopology != 0 ||
                    profiledExecutables != 0 ||
                    incompleteProfiles != 0 ||
                    observedTopology != 0 ||
                    observedLaunchers != 0 ||
                    incompleteObservations != 0
            ) ||
        status == "no-launcher" &&
            (
                launchers != 0 ||
                    integrationTopology != 0 ||
                    profiledExecutables != 0 ||
                    incompleteProfiles != 0 ||
                    observedTopology != 0 ||
                    observedLaunchers != 0 ||
                    incompleteObservations != 0
            ) ||
        status == "ready" &&
            (
                launchers == 0 ||
                    verifiedExecutables != launchers ||
                    current != launchers ||
                    pending != 0 ||
                    attention != 0 ||
                    failed != 0
            ) ||
        status == "pending" && (pending == 0 || attention != 0 || failed != 0) ||
        status == "attention" && (attention == 0 || failed != 0) ||
        status == "failed" && failed == 0
    ) {
        throw IllegalStateException("Rust returned inconsistent package launcher review")
    }
    return PackageLauncherReview(
        status = checkNotNull(status),
        capabilities = capabilities,
        capabilitiesAnalyzed = analyzed,
        launchers = launchers,
        verifiedExecutables = verifiedExecutables,
        current = current,
        pending = pending,
        attention = attention,
        failed = failed,
        integrationTopology = integrationTopology,
        profiledExecutables = profiledExecutables,
        incompleteProfiles = incompleteProfiles,
        observedTopology = observedTopology,
        observedLaunchers = observedLaunchers,
        incompleteObservations = incompleteObservations,
    )
}

private fun reviewCapabilities(value: String?): Int? {
    return reviewHex(value, 2, 0xff)
}

private fun reviewHex(
    value: String?,
    maximumLength: Int,
    maximum: Int,
): Int? {
    if (
        value == null ||
        value.isEmpty() ||
        value.length > maximumLength ||
        value.length > 1 && value.startsWith('0') ||
        value.any { character -> character !in '0'..'9' && character !in 'a'..'f' }
    ) {
        return null
    }
    return value.toIntOrNull(16)?.takeIf { parsed -> parsed in 0..maximum }
}

private fun reviewCount(value: String?): Int? {
    val parsed = value?.toIntOrNull() ?: return null
    return parsed.takeIf { count -> count in 0..256 && count.toString() == value }
}
