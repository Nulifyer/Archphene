# Publishing Archphene APK releases

GitHub Actions builds and attaches the signed manager APK whenever a GitHub Release is published.

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
   - `Archphene-<version>.apk`
   - `Archphene-<version>.apk.sha256`

The Android `versionName` comes from the release tag. The `versionCode` uses a high CI range plus the monotonic GitHub workflow run number, allowing a stable release after a prerelease with the same semantic version.

The workflow can be rerun manually with **Run workflow** and an existing release tag. Uploads use `--clobber`, so a rerun replaces assets for that release.