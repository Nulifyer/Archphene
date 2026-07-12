# Linux app manager emulator results

Date: 2026-07-11

## Result

The manager now treats generated Linux applications as separately installed Android APKs. It no longer launches the toy writable-data ELF path from its main UI.

KCalc publishes signed Android manifest metadata for Linux-app identity, pacman source `extra/kcalc`, source version `26.04.3-1`, its official Arch metadata URL, and runtime ABI `glibc-x86_64`.

The manager queries visible launcher activities, filters on `org.archphene.linux_app`, and displays the installed app icon, source, version, and runtime. Its Launch command starts KCalc through Android's package manager, preserving KCalc's separate UID, permissions, storage, lifecycle, and native-library code path.

## Emulator evidence

`scripts/test-linux-manager-kcalc.ps1` verifies:

- KCalc appears in the manager catalog.
- The source is `pacman: extra/kcalc 26.04.3-1`.
- The runtime is `glibc-x86_64`.
- Launch resumes `org.archphene.linux.kcalc/.MainActivity`.
- The real `libarchphene_kcalc.so` child is running.

KCalc's generated APK has package versions tied to the Arch package:

```text
versionCode=26040301
versionName=26.04.3-1
```

`scripts/test-linux-manager-update.ps1` invokes an explicit update check against Arch's official HTTPS package JSON endpoint. The emulator currently reports:

```text
Available: 26.04.3-1
Up to date
```

The checker rejects non-HTTPS URLs and hosts other than `archlinux.org`, caps metadata responses at 1 MiB, and does not install anything automatically.

## Architecture boundary

The manager can now discover, launch, and check source versions for generated app APKs. It cannot yet turn a newer pacman package into a signed replacement APK.

The next transaction path is:

```text
official Arch metadata and signed package
  -> resolver/build service
  -> dependency closure and bridge capability validation
  -> reproducible APK generation under the app's persistent signing identity
  -> APK hash/signature verification
  -> Android PackageInstaller update session
  -> post-update launch/health check
  -> retain prior artifact for rollback/reinstall
```

Pacman must not write into Android's host filesystem, and the manager must not execute downloaded ELF files from writable app data. Android remains the installer and sandbox authority.
## Signed update transaction

`scripts/test-kcalc-update-transaction.ps1` now verifies the complete builder-side transaction:

- downloads current metadata from Arch's official endpoint;
- verifies compressed package size and filename;
- verifies the detached package signature against `archlinux-keyring`;
- confirms the trusted signature belongs to Arch packager Antonio Rojas;
- extracts and hashes `/usr/bin/kcalc` from the signed package;
- requires the APK payload to be byte-identical to that stock ELF;
- builds the generated APK under KCalc's persistent signing key;
- verifies Android package name and version;
- requires the generated signing certificate to match the installed KCalc package;
- performs an Android replacement install;
- runs the bidirectional clipboard health check;
- writes `tooling/build/kcalc-update-transaction/transaction.json`.

## Android PackageInstaller

The manager now has a verified APK installer path. Before creating a session it checks HTTPS for production artifact URLs, expected APK SHA-256, expected Android package name, signing certificate equality, non-decreasing version code, and a 512 MiB artifact limit.

`scripts/test-linux-manager-package-installer.ps1` stages the already verified APK in manager-private cache, detects Android's system-owned KCalc update dialog, accepts the Update action, verifies the manager receives success, and reruns KCalc's clipboard health check. The manager never bypasses Android's confirmation or install authority.

## Descriptor-driven generation

`prototypes/kcalc-android-app/archphene-app.json` is the first `org.archphene.app.v1` descriptor. It owns Android identity/version, official Arch source/signature URLs, payload path/hash, runtime ABI, bridge profile, capabilities, and Android permissions.

The KCalc builder consumes this descriptor and rejects schema, package, manifest metadata, or payload-hash drift before generating an APK. A deliberate payload-hash tampering test is rejected as expected. The wrapper source is still KCalc-specific; extracting it into a reusable template remains the next generator milestone.