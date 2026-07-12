# Android permission bridge emulator results

## Question

Can a generated Linux-app APK make a Linux payload ask for an Android runtime permission without bypassing Android's permission model?

## Result

Yes, for permissions the generated APK declares in its manifest.

The emulator test proved this control path:

1. A Linux ELF payload was packaged into the APK native library directory.
2. The Android wrapper launched that Linux payload as an app child process.
3. The Linux payload printed a bridge protocol request:

```text
ARCHPHENE_BRIDGE_REQUEST android_permission android.permission.POST_NOTIFICATIONS
```

4. The Android wrapper parsed that request.
5. The wrapper called Android's normal `requestPermissions()` API.
6. Android displayed the real system permission dialog.
7. The emulator UI was tapped on `Allow`.
8. Android delivered the normal permission callback.
9. `dumpsys package` reported the permission as granted.

This does not grant Linux code extra authority directly. The Linux payload can only trigger a request through the wrapper, and the wrapper can only request permissions declared by the generated APK manifest.

## Files Added or Changed

- Linux permission payload: `prototypes/linux-payloads/permission-request/main.go`
- Packaged payload: `prototypes/lapk-wrapper-exec-test/lib/x86_64/libarchphene_permission_request.so`
- Wrapper permission bridge: `prototypes/lapk-wrapper-exec-test/src/org/archpheneos/wrapper/MainActivity.java`
- APK manifest permission declaration: `prototypes/lapk-wrapper-exec-test/AndroidManifest.xml`

The APK now declares:

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

## Emulator Evidence

Dialog capture:

```text
Allow Linux Hello to send you notifications?
Allow
Don't allow
```

Wrapper output after granting:

```text
Linux bridge permission request
Exit code: 0
Stdout:
linux payload needs notification access
ARCHPHENE_BRIDGE_REQUEST android_permission android.permission.POST_NOTIFICATIONS
linux payload is waiting for the Android bridge to request the permission
Bridge parsed Android permission request: true
Bridge action: requestPermissions(android.permission.POST_NOTIFICATIONS)
Android current permission state: granted
Android permission callback: POST_NOTIFICATIONS granted.
```

Package manager state:

```text
runtime permissions:
  android.permission.POST_NOTIFICATIONS: granted=true
```

Artifacts:

- `artifacts/archphene-wrapper-permission-dialog-window.xml`
- `artifacts/archphene-wrapper-permission-dialog.png`
- `artifacts/archphene-wrapper-permission-after-allow-window.xml`
- `artifacts/archphene-wrapper-permission-after-allow.png`
- `artifacts/archphene-wrapper-permission-after-allow-dumpsys-package.txt`

## Design Implication

This confirms the right direction for permissions:

- Linux app packages need a manifest-derived Android permission profile.
- The generated APK declares the Android permissions up front.
- Linux-side code asks the bridge for capabilities.
- The bridge calls Android framework APIs and surfaces the normal Android prompt.
- The Linux process receives an allow/deny result from the bridge instead of directly obtaining Android framework privileges.

For real apps, the bridge should replace stdout parsing with a structured local protocol, likely a Unix domain socket or inherited pipe, and define request/response messages such as:

```json
{"type":"permission.request","permission":"android.permission.POST_NOTIFICATIONS","reason":"desktop notifications"}
{"type":"permission.result","permission":"android.permission.POST_NOTIFICATIONS","granted":true}
```

## Limits

This test validates runtime permission prompting, not a full Linux desktop app permission surface.

Remaining work:

1. Add a bidirectional bridge protocol so the Linux process receives the final grant or denial.
2. Test the denial path.
3. Test storage through Android's Storage Access Framework.
4. Test microphone/camera/location permissions with real brokered Android APIs.
5. Ensure generated APK permissions are minimal and derived from package metadata, not broad bridge defaults.
