# Bidirectional permission bridge emulator results

## Question

Can a Linux payload running inside the generated APK receive the final Android runtime permission decision without bypassing Android permissions?

## Result

Yes.

The emulator now validates a bidirectional bridge over inherited process streams:

1. Android wrapper starts the packaged Linux payload from `nativeLibraryDir`.
2. Linux payload prints a permission request on stdout.
3. Android wrapper parses the request and calls Android's normal `requestPermissions()` API.
4. Android displays the real system permission dialog.
5. User chooses `Allow` or `Don't allow`.
6. Android wrapper receives `onRequestPermissionsResult()`.
7. Android wrapper writes a structured response to the Linux process stdin.
8. Linux payload reads the response and prints its final permission decision.

No permission is granted directly to Linux code. The Android package owns the permission request, the Android framework owns the prompt and persisted permission state, and Linux only receives the bridge result.

## Bridge Messages

Linux to Android:

```text
ARCHPHENE_BRIDGE_REQUEST android_permission android.permission.POST_NOTIFICATIONS
```

Android to Linux:

```json
{"type":"permission.result","permission":"android.permission.POST_NOTIFICATIONS","granted":true,"reason":"android_callback"}
```

or:

```json
{"type":"permission.result","permission":"android.permission.POST_NOTIFICATIONS","granted":false,"reason":"android_callback"}
```

## Deny Path Evidence

Package manager state:

```text
runtime permissions:
  android.permission.POST_NOTIFICATIONS: granted=false
```

Wrapper and Linux payload log:

```text
Android permission callback: POST_NOTIFICATIONS denied.
Bridge wrote Linux response: {"type":"permission.result","permission":"android.permission.POST_NOTIFICATIONS","granted":false,"reason":"android_callback"}
linux stdout: linux payload received bridge response: {"type":"permission.result","permission":"android.permission.POST_NOTIFICATIONS","granted":false,"reason":"android_callback"}
linux stdout: linux payload permission decision: denied
Linux bridge process exit code: 0
```

Artifacts:

- `artifacts/archphene-wrapper-bridge-deny-dialog-window.xml`
- `artifacts/archphene-wrapper-bridge-deny-dialog.png`
- `artifacts/archphene-wrapper-bridge-deny-after-window.xml`
- `artifacts/archphene-wrapper-bridge-deny-after.png`
- `artifacts/archphene-wrapper-bridge-deny-dumpsys-package.txt`

## Allow Path Evidence

Package manager state:

```text
runtime permissions:
  android.permission.POST_NOTIFICATIONS: granted=true
```

Wrapper and Linux payload log:

```text
Android permission callback: POST_NOTIFICATIONS granted.
Bridge wrote Linux response: {"type":"permission.result","permission":"android.permission.POST_NOTIFICATIONS","granted":true,"reason":"android_callback"}
linux stdout: linux payload received bridge response: {"type":"permission.result","permission":"android.permission.POST_NOTIFICATIONS","granted":true,"reason":"android_callback"}
linux stdout: linux payload permission decision: granted
Linux bridge process exit code: 0
```

Artifacts:

- `artifacts/archphene-wrapper-bridge-allow-dialog-window.xml`
- `artifacts/archphene-wrapper-bridge-allow-dialog.png`
- `artifacts/archphene-wrapper-bridge-allow-after-window.xml`
- `artifacts/archphene-wrapper-bridge-allow-after.png`
- `artifacts/archphene-wrapper-bridge-allow-dumpsys-package.txt`

## Files

- Linux payload: `prototypes/linux-payloads/permission-request/main.go`
- Packaged payload: `prototypes/lapk-wrapper-exec-test/lib/x86_64/libarchphene_permission_request.so`
- Android wrapper: `prototypes/lapk-wrapper-exec-test/src/org/archpheneos/wrapper/MainActivity.java`
- APK: `prototypes/lapk-wrapper-exec-test/out/archpheneos-lapk-wrapper-exec-test.apk`

## Design Implication

The permission bridge can preserve Android semantics:

- Linux code requests a capability.
- Android wrapper maps that request to a declared Android permission.
- Android shows the normal system UI.
- Android persists the actual package permission state.
- Linux receives only a scoped bridge result.

This is the correct shape for Linux apps behaving like Android apps.

The inherited stdin/stdout transport is good enough for the milestone. The production bridge should move to a framed protocol over a Unix domain socket or dedicated pipes so it can support concurrent requests, request IDs, cancellation, lifecycle shutdown, and richer portal calls.

## Next Milestones

1. Add a reusable bridge protocol type system with request IDs.
2. Move notification requests from test strings to a small Linux bridge client library.
3. Test Android Storage Access Framework as the first file portal.
4. Split the large wrapper harness into reusable generated-APK template code and per-test payloads.
