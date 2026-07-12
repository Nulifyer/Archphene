# Generated APK wrapper VM results

Date: 2026-07-09

## Question

Can the no-OS-edit path work if each Linux app is packaged as its own APK and the Linux payload is installed as APK code instead of copied into writable app data?

## Result

Yes, for the minimal static Linux ELF test on the Android API 36 x86_64 emulator.

The same Linux ELF payload that failed from app-private writable storage executed successfully when packaged into the APK native library directory.

## Prototype

- Wrapper source: `prototypes/lapk-wrapper-exec-test/`
- Build/install script: `scripts/build-install-lapk-wrapper-exec-test.ps1`
- Output APK: `prototypes/lapk-wrapper-exec-test/out/archpheneos-lapk-wrapper-exec-test.apk`
- Payload source: `prototypes/linux-payloads/hello/main.go`
- Payload in APK: `lib/x86_64/libarchphene_hello.so`
- Android package: `org.archpheneos.wrapper.exec`
- Launcher label: `Linux Hello`

The payload is a static Linux x86_64 ELF built from Go:

```text
hello from linux elf
goos=linux goarch=amd64
```

The wrapper sets:

```xml
android:extractNativeLibs="true"
```

and executes:

```text
ApplicationInfo.nativeLibraryDir/libarchphene_hello.so
```

## VM evidence

The APK installed and launched successfully:

```text
Verified using v1 scheme (JAR signing): true
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Performing Streamed Install
Success
Events injected: 1
```

The package manager state confirms native library extraction:

```text
Package [org.archpheneos.wrapper.exec]
appId=10219
codePath=/data/app/~~HwiKOKymWGj5xlfSsEvy1w==/org.archpheneos.wrapper.exec-PIac_-7-zM0U_V237UtvqA==
extractNativeLibs=true
primaryCpuAbi=x86_64
dataDir=/data/user/0/org.archpheneos.wrapper.exec
```

The UI dump confirms the installed native payload is executable and labeled as APK data, not app-private writable data:

```text
nativeLibraryDir: /data/app/~~HwiKOKymWGj5xlfSsEvy1w==/org.archpheneos.wrapper.exec-PIac_-7-zM0U_V237UtvqA==/lib/x86_64
Payload: /data/app/~~HwiKOKymWGj5xlfSsEvy1w==/org.archpheneos.wrapper.exec-PIac_-7-zM0U_V237UtvqA==/lib/x86_64/libarchphene_hello.so
Exists: true
Length: 1593506
canExecute: true

-rwxr-xr-x 1 system system u:object_r:apk_data_file:s0 1593506 ... libarchphene_hello.so

Direct packaged ELF launch
Exit code: 0
Timed out: false
Stdout:
hello from linux elf
goos=linux goarch=amd64
Stderr:
Start error:
```

Saved evidence:

- `artifacts/archphene-wrapper-exec-window.xml`
- `artifacts/archphene-wrapper-exec.png`

## Comparison with the failed writable-data test

Previous direct execution from the manager's writable private data failed:

```text
error=13, Permission denied
avc: denied { execute_no_trans } ... tcontext=u:object_r:app_data_file:s0 ...
```

This wrapper test succeeded because the payload was installed under `/data/app/.../lib/x86_64` and labeled:

```text
u:object_r:apk_data_file:s0
```

That is the critical distinction for the no-OS-edit route.

## Architecture implication

The viable no-OS-edit design is not "manager downloads Linux ELF and executes it from app data."

The viable design is:

1. The manager resolves pacman/AUR/git inputs off-device or on-device.
2. The manager/build service generates a real Android APK per Linux app.
3. The Linux payload and app-specific runtime files are installed as APK code/native libraries.
4. The wrapper Activity acts as the Android app surface.
5. Android's package manager handles install/update/uninstall.
6. Android's normal UID sandbox and app permission model apply to the wrapper package.

This aligns options 1 and 5:

- Option 1: package Linux apps as real APKs.
- Option 5: include a per-app bridge SDK in each generated APK.

## Remaining unknowns

This result proves only the minimal execution primitive.

Still unproven:

- dynamically linked glibc binaries
- multiple packaged executables
- packaged shared libraries and dynamic linker layout
- filesystem layout for real Arch packages
- GUI bridge for X11, Wayland, Vulkan, OpenGL, audio, portals, D-Bus, and fonts
- update behavior when a package has many dependency APKs or split APKs
- Android/GrapheneOS behavior on a real Pixel device
- ARM64 behavior with `arm64-v8a`
- whether GrapheneOS adds stricter policy than this Android emulator for executable APK native payloads

## Next tests

1. Package two executables in the same APK native library directory and launch both.
2. Package a dynamically linked Linux ELF plus its interpreter and shared libraries.
3. Try an ARM64 static Linux ELF on an ARM64 Android target or real Pixel.
4. Build a wrapper that exposes a simple Android permission bridge to the Linux process over stdin/stdout or a Unix socket.
5. Convert the wrapper prototype into a generated APK template used by the manager.
