# LinuxAppManager stub VM results

Date: 2026-07-09

Result: passed for the app-level ArchpheneOS control-plane stub.

## What was tested

Prototype APK:

```text
prototypes/linux-app-manager-stub
```

Package identity:

```text
org.archpheneos.manager
```

Android components:

```text
activity: org.archpheneos.manager.MainActivity
service:  org.archpheneos.manager.LinuxAppManagerService
```

The APK is intentionally a normal app-level stand-in for the future platform service. It gives us a concrete package identity, UID, data directory, activity, and background service to build around before moving the control plane into the OS image.

## VM state

The existing x86_64 Android GUI VM was running and reachable over adb:

```text
emulator-5554 device product:sdk_gphone64_x86_64 model:sdk_gphone64_x86_64 device:emu64xa
sys.boot_completed=1
```

## Install and launch evidence

Package installed:

```text
package:org.archpheneos.manager
```

Foreground activity:

```text
mCurrentFocus=Window{... org.archpheneos.manager/org.archpheneos.manager.MainActivity}
mFocusedApp=ActivityRecord{... org.archpheneos.manager/.MainActivity ...}
```

Package manager state:

```text
Package [org.archpheneos.manager]
codePath=/data/app/.../org.archpheneos.manager-.../
resourcePath=/data/app/.../org.archpheneos.manager-.../
versionCode=0 minSdk=23 targetSdk=36
dataDir=/data/user/0/org.archpheneos.manager
```

Service manager state:

```text
ServiceRecord{... u0 org.archpheneos.manager/.LinuxAppManagerService c:org.archpheneos.manager}
packageName=org.archpheneos.manager
processName=org.archpheneos.manager
targetSdkVersion=36
baseDir=/data/app/.../org.archpheneos.manager-.../base.apk
dataDir=/data/user/0/org.archpheneos.manager
app=ProcessRecord{... 2714:org.archpheneos.manager/u0a217}
startRequested=true
callStart=true
startCommandResult=1
```

This proves Android assigned the manager a normal app UID and data directory, started its activity, and started its service.

## UI evidence

Captured UI hierarchy:

```text
artifacts/archphene-manager-window.xml
```

Captured screenshot:

```text
artifacts/archphene-manager.png
```

UI text verified:

```text
ArchpheneOS LinuxAppManager
Phase 2 VM control-plane stub
Package: org.archpheneos.manager
Planned responsibilities:
- Parse LAPK/LRPK manifests
- Resolve shared Linux runtimes
- Prepare per-app launch namespaces
- Broker files, clipboard, audio, network, and notifications
- Report install/update state to Android UI
```

## Interpretation

This milestone proves the control-plane package shape:

```text
Android package identity
Android app UID
Android app-private data directory
launchable manager UI
started manager service
adb-verifiable package/service state
```

It does not yet prove privileged OS integration. The manager is still a normal APK. The next step is to make it manage a first Linux payload inside its app sandbox, then split the model into:

```text
manager/control plane
runtime payload
Linux app payload
per-app launch wrapper
```

## Next milestone

Run a real Linux payload under the manager flow:

```text
org.archpheneos.manager
  -> installs/owns a sample LAPK manifest
  -> launches a bundled static x86_64 Linux binary
  -> captures stdout/stderr
  -> shows launch result in Android UI
```

The first payload should be a static Linux `hello` binary. After that, test a dynamic glibc binary with a bundled mini runtime.
