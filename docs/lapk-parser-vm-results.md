# LAPK parser VM results

Date: 2026-07-09

Result: passed for a first app-level LAPK manifest parser inside the ArchpheneOS manager stub.

## What changed

Added a sample Linux app package manifest:

```text
prototypes/linux-app-manager-stub/assets/sample-lapk.json
```

Added parser:

```text
prototypes/linux-app-manager-stub/src/org/archpheneos/manager/LinuxPackageManifest.java
```

Updated manager UI/service:

```text
prototypes/linux-app-manager-stub/src/org/archpheneos/manager/MainActivity.java
prototypes/linux-app-manager-stub/src/org/archpheneos/manager/LinuxAppManagerService.java
```

Updated build script to package APK assets and use a persistent signing key:

```text
scripts/build-install-linux-manager-stub.ps1
tooling/signing/archpheneos-manager-debug.keystore
```

The persistent key matters because Android package updates require signing continuity. A previous build regenerated a new key every time and triggered:

```text
INSTALL_FAILED_UPDATE_INCOMPATIBLE
```

After switching to a stable key, repeat `adb install -r` updates succeeded.

## Sample manifest

The sample manifest represents a first static Linux payload:

```json
{
  "schema": "org.archpheneos.lapk.v0",
  "package": "org.archpheneos.samples.hello",
  "name": "Linux Hello",
  "version": "0.1.0",
  "arch": "x86_64",
  "linuxAbi": "static-linux-elf",
  "entrypoint": "/app/bin/hello",
  "requires": [
    "org.archpheneos.bridge.exec >= 0.1",
    "org.archpheneos.runtime.none"
  ],
  "capabilities": [
    "process.exec",
    "stdout.capture"
  ]
}
```

## VM verification

The Android emulator was reachable:

```text
emulator-5554 device product:sdk_gphone64_x86_64 model:sdk_gphone64_x86_64 device:emu64xa
```

The updated APK built, signed, verified, installed, and launched:

```text
Verified using v1 scheme (JAR signing): true
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Performing Streamed Install
Success
```

A repeat build/install also succeeded with `adb install -r`, proving signing continuity for updates.

## UI evidence

Captured UI hierarchy:

```text
artifacts/archphene-manager-lapk-window.xml
```

Captured screenshot:

```text
artifacts/archphene-manager-lapk.png
```

UI text verified:

```text
Sample LAPK parsed
Package: org.archpheneos.samples.hello
Name: Linux Hello
Version: 0.1.0
Arch: x86_64
Linux ABI: static-linux-elf
Entrypoint: /app/bin/hello
Requires:
- org.archpheneos.bridge.exec >= 0.1
- org.archpheneos.runtime.none
Capabilities:
- process.exec
- stdout.capture
```

Service still runs under the manager package identity:

```text
ServiceRecord{... u0 org.archpheneos.manager/.LinuxAppManagerService c:org.archpheneos.manager}
packageName=org.archpheneos.manager
processName=org.archpheneos.manager
dataDir=/data/user/0/org.archpheneos.manager
app=ProcessRecord{... org.archpheneos.manager/u0a218}
startRequested=true
startCommandResult=1
```

## Interpretation

This milestone proves the first control-plane data path:

```text
APK asset payload
  -> LAPK manifest parser
  -> manager UI state
  -> Android package update flow
  -> adb-verifiable installed/running state
```

This is still not executing a Linux payload. The next milestone should add payload staging and execution result capture.

## Next milestone

Add a sample payload launcher:

```text
sample-lapk.json
  -> points to assets/payloads/hello.txt or assets/payloads/hello-bin
  -> manager copies payload into app-private storage
  -> manager records staged path, hash, mode, and launch status
  -> UI shows staged payload state
```

After staging works, test actual process execution in increasing difficulty:

```text
1. Android-native helper process via /system/bin/sh for execution plumbing
2. packaged native Android binary for app-private subprocess behavior
3. static Linux x86_64 ELF from /data/local/tmp under adb
4. static Linux x86_64 ELF launched by app if Android exec policy allows it
5. dynamic glibc payload with bundled mini runtime
```
