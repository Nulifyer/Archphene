# VM GUI test results

Date: 2026-07-09

Result: passed for the Android emulator GUI smoke test.

## What was set up

Workspace-local Android SDK:

```text
tooling/android-sdk
```

Installed SDK packages:

```text
emulator                                    36.6.11
platform-tools                              37.0.0
platforms;android-36                        2
system-images;android-36;google_apis;x86_64 7
build-tools;36.0.0
```

Workspace-local AVD:

```text
tooling/avd/ArchpheneOS_x86_64_api36.avd
```

AVD name:

```text
ArchpheneOS_x86_64_api36
```

## VM verification

The emulator booted as a real GUI VM using Windows Hypervisor Platform acceleration.

Observed boot properties:

```text
adb device: emulator-5554
Android release: 16
CPU ABI: x86_64
kernel: Linux localhost 6.6.66-android15-8-gd0c43a640eab-ab13812146 #1 SMP PREEMPT Mon Jul 21 17:41:13 UTC 2025 x86_64 Toybox
fingerprint: google/sdk_gphone64_x86_64/emu64xa:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys
```

## APK install and GUI launch verification

Created a minimal Android smoke-test app:

```text
prototypes/android-smoke-apk
```

Built APK:

```text
prototypes/android-smoke-apk/out/archpheneos-smoke.apk
```

Install result:

```text
Performing Streamed Install
Success
package:com.archpheneos.smoke
```

Launch/focus result:

```text
mCurrentFocus=Window{... com.archpheneos.smoke/com.archpheneos.smoke.MainActivity}
mFocusedApp=ActivityRecord{... com.archpheneos.smoke/.MainActivity ...}
```

UI hierarchy result:

```text
text="ArchpheneOS VM smoke test&#10;APK install and launch verified"
package="com.archpheneos.smoke"
class="android.widget.TextView"
```

Screenshot artifact:

```text
artifacts/archpheneos-smoke.png
```

## Re-run commands

Start the GUI VM:

```powershell
.\scripts\start-android-vm.ps1
```

In another terminal, build, install, and launch the smoke APK:

```powershell
.\scripts\build-install-smoke-apk.ps1
```

Check boot state:

```powershell
.\tooling\android-sdk\platform-tools\adb.exe devices -l
.\tooling\android-sdk\platform-tools\adb.exe shell getprop sys.boot_completed
```

## Interpretation

This proves the host can run a real x86_64 Android GUI VM, install APKs, launch APK UI, and inspect the running UI through adb.

This does not prove GrapheneOS-equivalent hardware security or generic laptop support. It is the correct local VM base for the next ArchpheneOS platform experiments.
