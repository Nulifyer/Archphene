# Publishing Archphene APK releases

GitHub Actions builds and attaches the signed manager APK whenever a GitHub Release is published. The build also signs an isolated Terminal companion with the same release key and embeds it in the manager APK, so users need only the manager release asset. The release is produced entirely on Ubuntu: a signature-verifying Arch container builds the package runtime and patched glibc, then Linux Android SDK tools build, align, sign, and verify the APK.

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
2. Create a tag using `vMAJOR.MINOR.PATCH`, such as `v1.0.0`. Suffixes such as `v1.1.0-beta.1` are accepted.
3. Create and publish a GitHub Release for that tag.
4. The **Publish Archphene APK** workflow builds and verifies the APK.
5. The workflow attaches:
   - `Archphene-x86_64-<version>.apk`
   - `Archphene-x86_64-<version>.apk.sha256`
   - `Archphene-arm64-v8a-<version>.apk`
   - `Archphene-arm64-v8a-<version>.apk.sha256`

Each APK contains only its matching package runtime, Terminal PTY library, compositor, GPU helper, trust data, and wrapper-template native libraries. The self-updater selects the first/native Android ABI and accepts the legacy ABI-neutral name only for older releases. AArch64 runtime artifacts are aligned for both 4 KB and 16 KB Android pages. Current upstream Arch x86_64 packages are 4 KB-only, so the x86_64 artifact fails closed on a 16 KB x86_64 system until those packages are rebuilt.

The Android `versionName` comes from the release tag. The `versionCode` uses a high CI range plus the monotonic GitHub workflow run number, allowing a stable release after a prerelease with the same semantic version.

The workflow can be rerun manually with **Run workflow** and an existing release tag. Uploads use `--clobber`, so a rerun replaces assets for that release.

## Validate self-update

The emulator regression installs an older manager signed by the production key,
discovers and downloads the requested public GitHub Release through the manager,
and accepts Android's system-owned update confirmation:

```powershell
./scripts/test-linux-manager-github-self-update.ps1
```

Use `-RebuildBaseline` after manager source changes. This test uninstalls the
manager package and therefore clears manager-private state, but it does not remove
separately installed Linux application packages.
