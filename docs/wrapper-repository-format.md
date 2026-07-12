# Archphene wrapper repository format

The manager uses Arch Linux package metadata for discovery and update comparison. Installation is a separate trust boundary: an Android-installable Linux app must be published as a signed wrapper artifact.

A wrapper catalog is an HTTPS JSON document with schema `org.archphene.wrapper-repository.v1`:

```json
{
  "schema": "org.archphene.wrapper-repository.v1",
  "packages": [
    {
      "packageName": "org.archphene.linux.kcalc",
      "sourcePackage": "kcalc",
      "architecture": "x86_64",
      "signerSha256": "64_HEX_CHAR_ANDROID_SIGNING_CERTIFICATE_DIGEST",
      "versions": [
        {
          "version": "26.04.3-1",
          "versionCode": 26040301,
          "apkUrl": "https://repo.example/apps/kcalc-26.04.3-1.apk",
          "sha256": "64_HEX_CHAR_APK_DIGEST",
          "health": "good",
          "prerelease": false
        }
      ]
    }
  ]
}
```

`architecture` accepts `x86_64`, `aarch64`, or `any`. Incompatible wrapper entries are hidden on the current device.

`health` accepts repository-defined values. The manager treats `bad` as a blocking warning and displays it beside selected or pinned versions. Other values are informational.

`prerelease` is a boolean identifying alpha, beta, release-candidate, preview, development, snapshot, or nightly artifacts. It defaults to a conservative version-name classification for older catalogs. Pre-release discovery and installation are disabled unless the user enables **Allow pre-release versions** in manager settings.

For an update, Android requires the candidate APK signer to match the installed app. For a first install, the manager requires the candidate signer to match `signerSha256`. The APK digest, package name, signer, and monotonically increasing Android `versionCode` are all verified before a PackageInstaller session is committed.

The upstream Arch version and Android wrapper build identity are separate concepts. `version` identifies the pacman payload. `versionCode` identifies publication order for Android replacement installs. Rebuilding an older Arch version must receive a newer Android version code if it is intended to replace an already-installed newer payload.

A repository can publish the manager itself using package name `org.archpheneos.manager`. It is then checked and installed through the same verified update path as Linux app wrappers.