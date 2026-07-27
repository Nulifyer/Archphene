package org.archphene.app.runtime

internal fun reconcileAvailablePackageInstalledVersion(
    previous: AvailablePackageSnapshot,
    packageName: String,
    installedVersion: String,
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
        previous.installedVersions[index] == installedVersion
    ) {
        return previous
    }
    val installStates = previous.installStates.copyOf()
    val installedVersions = previous.installedVersions.copyOf()
    installStates[index] = installState
    installedVersions[index] = installedVersion
    return AvailablePackageSnapshot(
        previous.repositories,
        previous.names,
        previous.versions,
        previous.descriptions,
        installStates,
        installedVersions,
        previous.status,
        previous.revision + 1,
    )
}
