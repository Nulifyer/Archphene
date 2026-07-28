package org.archphene.app.runtime

internal fun reconcileAvailablePackageInstalledVersion(
    previous: AvailablePackageSnapshot,
    packageName: String,
    installedVersion: String,
    installedCapabilities: Int = 0,
    installedCapabilitiesAnalyzed: Boolean = false,
): AvailablePackageSnapshot {
    val index = previous.names.indexOf(packageName)
    if (index < 0) {
        return previous
    }
    val installState =
        when {
            installedVersion.isEmpty() -> "available"
            installedVersion == previous.versions[index] -> "installed"
            else -> "different"
        }
    if (
        previous.installStates[index] == installState &&
        previous.installedVersions[index] == installedVersion &&
        previous.installedCapabilities[index] == installedCapabilities &&
        previous.installedCapabilitiesAnalyzed[index] == installedCapabilitiesAnalyzed
    ) {
        return previous
    }
    val installStates = previous.installStates.copyOf()
    val installedVersions = previous.installedVersions.copyOf()
    val capabilities = previous.installedCapabilities.copyOf()
    val capabilitiesAnalyzed = previous.installedCapabilitiesAnalyzed.copyOf()
    installStates[index] = installState
    installedVersions[index] = installedVersion
    capabilities[index] = if (installedVersion.isEmpty()) 0 else installedCapabilities
    capabilitiesAnalyzed[index] =
        installedVersion.isNotEmpty() && installedCapabilitiesAnalyzed
    return AvailablePackageSnapshot(
        previous.repositories,
        previous.names,
        previous.versions,
        previous.descriptions,
        installStates,
        installedVersions,
        capabilities,
        capabilitiesAnalyzed,
        previous.status,
        previous.revision + 1,
    )
}
