package org.archphene.app.runtime

internal const val AVAILABLE_PACKAGE_LIMIT = 100

internal fun mergeReviewedAurPackage(
    previous: AvailablePackageSnapshot,
    installed: InstalledPackageSnapshot,
    packageName: String,
    version: String,
    description: String,
    installState: String,
    installedVersion: String,
): AvailablePackageSnapshot {
    require(
        previous.repositories.size == previous.names.size &&
            previous.versions.size == previous.names.size &&
            previous.descriptions.size == previous.names.size &&
            previous.installStates.size == previous.names.size &&
            previous.installedVersions.size == previous.names.size &&
            previous.installedCapabilities.size == previous.names.size &&
            previous.installedCapabilitiesAnalyzed.size == previous.names.size,
    )
    require(
        packageName.isNotEmpty() &&
            packageName.length <= 128 &&
            packageName.all { character ->
                character.code <= 0x7f &&
                    (character.isLetterOrDigit() || character in "@._+-")
            },
    )
    require(
        version.isNotEmpty() &&
            version.length <= 128 &&
            version.none { character -> character.isWhitespace() || character.isISOControl() },
    )
    require(
        description.length <= 512 &&
            description.none { character -> character == '\u0000' || character == '\r' },
    )
    require(
        installedVersion.length <= 128 &&
            installedVersion.none { character ->
                character.isWhitespace() || character.isISOControl()
            },
    )
    require(
        installState == "available" ||
            installState == "installed" ||
            installState == "update" ||
            installState == "different",
    )
    require((installState == "available") == installedVersion.isEmpty())
    require(installState != "installed" || installedVersion == version)

    var retained = 0
    var source = 0
    while (source < previous.names.size && retained < AVAILABLE_PACKAGE_LIMIT - 1) {
        if (previous.repositories[source] != "aur") {
            retained += 1
        }
        source += 1
    }
    val size = retained + 1
    val repositories = Array(size) { "" }
    val names = Array(size) { "" }
    val versions = Array(size) { "" }
    val descriptions = Array(size) { "" }
    val installStates = Array(size) { "" }
    val installedVersions = Array(size) { "" }
    val installedCapabilities = IntArray(size)
    val installedCapabilitiesAnalyzed = BooleanArray(size)
    source = 0
    var destination = 0
    while (source < previous.names.size && destination < retained) {
        if (previous.repositories[source] != "aur") {
            repositories[destination] = previous.repositories[source]
            names[destination] = previous.names[source]
            versions[destination] = previous.versions[source]
            descriptions[destination] = previous.descriptions[source]
            installStates[destination] = previous.installStates[source]
            installedVersions[destination] = previous.installedVersions[source]
            installedCapabilities[destination] = previous.installedCapabilities[source]
            installedCapabilitiesAnalyzed[destination] =
                previous.installedCapabilitiesAnalyzed[source]
            destination += 1
        }
        source += 1
    }
    repositories[retained] = "aur"
    names[retained] = packageName
    versions[retained] = version
    descriptions[retained] = description
    installStates[retained] = installState
    installedVersions[retained] = installedVersion
    if (installedVersion.isNotEmpty()) {
        val installedIndex = installed.names.binarySearch(packageName)
        if (installedIndex >= 0) {
            installedCapabilities[retained] = installed.capabilities[installedIndex]
            installedCapabilitiesAnalyzed[retained] =
                installed.capabilitiesAnalyzed[installedIndex]
        }
    }
    val status =
        if (retained == 0) {
            "1 reviewed AUR result"
        } else {
            "$retained official package" +
                (if (retained == 1) "" else "s") +
                " · 1 reviewed AUR result"
        }
    return AvailablePackageSnapshot(
        repositories,
        names,
        versions,
        descriptions,
        installStates,
        installedVersions,
        installedCapabilities,
        installedCapabilitiesAnalyzed,
        status,
        previous.revision + 1,
    )
}

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
