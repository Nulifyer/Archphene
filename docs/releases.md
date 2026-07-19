# Publishing Archphene APK releases

GitHub Actions builds, verifies, and attaches the signed manager APK whenever a version tag is pushed. The build also signs an isolated Terminal companion with the same release key and embeds it in the manager APK, so users need only the manager release asset. The release is produced entirely on Ubuntu: a signature-verifying Arch container builds the package runtime and patched glibc, then Linux Android SDK tools build, align, sign, and verify the APK.

## One-time signing setup

Run:

```powershell
./scripts/setup-github-release-signing.ps1
```

The script creates a dedicated production keystore and configures these repository Actions secrets:

- `ARCHPHENE_RELEASE_KEYSTORE_BASE64`
- `ARCHPHENE_RELEASE_STORE_PASSWORD`
- `ARCHPHENE_RELEASE_KEY_ALIAS`
- `ARCHPHENE_RELEASE_KEY_PASSWORD`

The local keystore and credentials backup are stored under ignored `tooling/signing/`. Back up both files offline. Losing this signing identity prevents future APKs from updating existing installations. Never commit either file.

## Publish a release

1. Ensure the release commit is on `main`.
2. Create and push a tag using `vMAJOR.MINOR.PATCH`, such as `v1.0.1`. Suffixes such as `v1.1.0-beta.1` are accepted.
3. The **Publish Archphene APK** workflow creates or reuses a draft release, builds and verifies both APKs, attaches every asset, and only then publishes the release.
4. The workflow attaches:
   - `Archphene-x86_64-<version>.apk`
   - `Archphene-x86_64-<version>.apk.sha256`
   - `Archphene-arm64-v8a-<version>.apk`
   - `Archphene-arm64-v8a-<version>.apk.sha256`

Each APK contains only its matching package runtime, Terminal PTY library, compositor, GPU helper, trust data, and wrapper-template native libraries. Current managers accept only the asset for their exact supported Android ABI; an ABI-neutral filename is never treated as universal.

The published `v1.0.0` manager was x86_64-only but used the old `Archphene-<version>.apk` name and can discover only that naming form. The `v1.0.1` workflow therefore emits one extra, byte-identical x86_64 compatibility alias named `Archphene-1.0.1.apk`. This is a one-time updater bridge for existing `v1.0.0` x86_64 installations, not a universal APK. Android rejects it on devices without x86_64 support. After `v1.0.1`, only explicit ABI assets are published. AArch64 runtime artifacts are aligned for both 4 KB and 16 KB Android pages.

Current upstream Arch x86_64 packages are 4 KB-only. On a 16 KB x86_64 Android system, Archphene declares Android's page-size compatibility mode so the generic system warning is not shown, presents its own precise compatibility notice, and blocks package search/install before any incompatible ELF executes. Manager and self-update functions remain available. This is a controlled unsupported-runtime state, not 16 KB compatibility for Arch x86_64 packages; those packages still need to be rebuilt with larger ELF load-segment alignment.

The Android `versionName` comes from the release tag. The `versionCode` uses a high CI range plus the monotonic GitHub workflow run number, allowing a stable release after a prerelease with the same semantic version.

The workflow can be rerun manually with **Run workflow** for an existing tag while its release remains a draft. Reruns may replace draft assets, but a published release is rejected before building. Published APKs and checksums are immutable: create a new version instead of mutating assets users may already have verified or installed. This draft-first sequence is also compatible with GitHub release immutability.

## Validate release contracts

Run the fast source contract before tagging:

```powershell
python scripts/test-release-workflow-contract.py
```

It rejects publish-before-upload workflows, mutable action references, missing ABI assets, migration aliases outside `v1.0.1`, and any manager fallback to ABI-neutral assets.

## Validate self-update

The device regression installs an older exact-ABI manager signed by the production key, discovers and downloads the requested public GitHub Release through the manager, and accepts Android's system-owned update confirmation:

```powershell
# Current exact-ABI updater
./scripts/test-linux-manager-github-self-update.ps1 -ToVersion 1.0.1 -RebuildBaseline

# Exact-ABI updater on an attached ARM64 device
./scripts/test-linux-manager-github-self-update.ps1 -Serial <adb-serial> -ToVersion 1.0.1 -RebuildBaseline

# One-time migration from the real published x86_64 v1.0.0 APK
./scripts/test-linux-manager-github-self-update.ps1 -PublishedV100Migration -RebuildBaseline
```

Use the exact-ABI command for each target. The migration mode downloads the real `v1.0.0` APK and checksum, rejects a non-x86_64 target, and verifies the old updater can consume the compatibility alias. These tests uninstall the manager package and therefore clear manager-private state, but they do not remove separately installed Linux application packages.

The published `v1.0.1` release passed independent checksum, exact-ABI, embedded Terminal, alignment, and production-signer verification. Live `0.9.0 -> 1.0.1` updates pass on the x86_64 emulator and physical AArch64 Samsung. The real published x86_64 `v1.0.0 -> v1.0.1` migration also passes on a wiped 16 KB emulator, including the old release compatibility warning, GitHub discovery/download, Android confirmation, replacement, and reconciled restart.
