# Publishing Archphene APK releases

GitHub Actions builds, signs, verifies, and attaches exact-ABI APKs when a
version tag is pushed. The workflow builds the greenfield `android/app` manager
with production package identity `org.archpheneos.manager` and the hidden
`org.archphene.builder` companion from the same source revision. Both use the
same production signing identity. Terminal functionality is integrated into the
manager; there is no separate Terminal APK in this release path.

## One-time signing setup

Run:

```bash
./scripts/setup-github-release-signing.sh
```

The script creates a dedicated production keystore and configures these
repository Actions secrets:

- `ARCHPHENE_RELEASE_KEYSTORE_BASE64`
- `ARCHPHENE_RELEASE_STORE_PASSWORD`
- `ARCHPHENE_RELEASE_KEY_ALIAS`
- `ARCHPHENE_RELEASE_KEY_PASSWORD`

The local keystore and credentials backup are stored under ignored
`tooling/signing/`. Back up both files offline. Losing this signing identity
prevents updates to existing installations. Never commit either file.

## Publish a release

1. Ensure the release commit is on `main`.
2. Create and push a tag using `vMAJOR.MINOR.PATCH`, such as `v1.1.0`. Suffixes
   such as `v1.1.0-rc.1` are accepted.
3. The **Publish Archphene APK** workflow creates or reuses a draft release,
   builds and verifies all APKs, attaches every asset, and only then publishes
   the release.

The workflow attaches these APKs and a basename-scoped `.sha256` file for each:

- `Archphene-x86_64-<version>.apk`
- `Archphene-arm64-v8a-<version>.apk`
- `Archphene-Builder-x86_64-<version>.apk`
- `Archphene-Builder-arm64-v8a-<version>.apk`

Each APK also has a deterministic `<apk-name>.spdx.json` SPDX 2.3 inventory and
matching `.spdx.json.sha256`. The SBOM binds every signed APK entry and complete
APK digest to the source commit and release version. It also records the exact
target-filtered, checksum-locked Rust runtime closure with Cargo package URLs
and declared license expressions. File licenses and package license conclusions
remain `NOASSERTION`; metadata does not replace the separate license and notice
audit. See [Licensing and release notices](licensing.md) for the notices already
packaged and the remaining release gate.

Install the manager APK matching the device ABI. Install the matching Builder
APK to enable reviewed AUR builds under the separate no-network Android UID.
The manager rejects a Builder with a different signer, package identity, ABI,
or network permission. Official package and integrated Terminal workflows do
not require the Builder.

Each manager APK contains only its matching package runtime and native
components, including the compositor, GPU helper, capability brokers, trust
data, Mesa runtime, and generated app-shell template. ABI-neutral filenames are
not release assets. AArch64 runtime artifacts are aligned for 4 KiB and 16 KiB
Android pages.

Current upstream Arch x86_64 packages are 4 KiB-only. On a 16 KiB x86_64
Android system, Archphene enables Android page-size compatibility mode, reports
the unsupported package-runtime boundary, and blocks incompatible package work
before an ELF executes. This does not make upstream x86_64 packages 16 KiB
compatible.

The Android `versionName` comes from the release tag. The `versionCode` uses a
high CI range plus the monotonic GitHub workflow run number. Manager and Builder
receive the same version.

The workflow can be rerun manually for an existing tag while its release remains
a draft. It rejects an already published release before building. Create a new
version instead of mutating published APKs or checksums.

## Validate release contracts

Run the source contract before tagging:

```bash
python3 scripts/test-release-workflow-contract.py
python3 scripts/test-release-rust-components.py
python3 scripts/test-release-sbom.py
```

Build and inspect unsigned local artifacts for both ABIs:

```bash
bash scripts/build-archphene-release-apk.sh \
  --abi x86_64 --version-code 1000000001 --version-name 1.1.0-rc.1
bash scripts/verify-release-apk.sh \
  tooling/build/apk/Archphene-x86_64-1.1.0-rc.1-unsigned.apk \
  tooling/build/apk/Archphene-Builder-x86_64-1.1.0-rc.1-unsigned.apk \
  x86_64 1.1.0-rc.1 --allow-unsigned
```

The publish workflow omits `--allow-unsigned`. The verifier then requires the
pinned production certificate for both APKs, exact package/version/ABI data,
non-debuggable applications, 16 KiB ZIP alignment and compatibility metadata,
the no-network hidden Builder boundary, the package-runtime catalog, required
native components, and the generated app-shell template.

## Previous prototype release

The published `v1.0.1` assets and their one-time `v1.0.0` x86_64 updater alias
remain historical, immutable prototype artifacts. The greenfield tag workflow
does not rebuild, rename, or preserve that legacy distribution format. A
greenfield manager opened over retained prototype-private state fails closed
before preferences or runtime bootstrap and directs the user to Android app
settings for the required clean installation.
