# Payload staging VM results

Date: 2026-07-09

Result: passed for app-private payload staging.

## What changed

The sample LAPK manifest now includes payload metadata:

```text
prototypes/linux-app-manager-stub/assets/sample-lapk.json
```

```json
"payload": {
  "asset": "payload-hello-static-x86_64.txt",
  "installPath": "bin/hello"
}
```

The manager APK now includes a payload asset:

```text
prototypes/linux-app-manager-stub/assets/payload-hello-static-x86_64.txt
```

Added staging implementation:

```text
prototypes/linux-app-manager-stub/src/org/archpheneos/manager/PayloadStager.java
```

The APK is marked debuggable for VM prototype inspection:

```text
android:debuggable="true"
```

This allows:

```text
adb shell run-as org.archpheneos.manager ...
```

## VM verification

The updated manager APK built, signed, verified, installed, and launched.

The UI confirmed:

```text
Payload staged
Path: /data/user/0/org.archpheneos.manager/files/archphene/payloads/org.archpheneos.samples.hello/bin/hello
Size: 174 bytes
SHA-256: 19df570de77aadd713757573e8cb41152a68b940f239b966c2592b36740ad2f2
Readable: true
Writable: true
Executable: true
Launch status: staged-only
```

`run-as` confirmed the file exists in app-private storage:

```text
total 8
-rwx------ 1 u0_a218 u0_a218 174 2026-07-09 10:46 hello
```

`run-as` confirmed the hash:

```text
19df570de77aadd713757573e8cb41152a68b940f239b966c2592b36740ad2f2  files/archphene/payloads/org.archpheneos.samples.hello/bin/hello
```

Service log confirmed staging:

```text
LinuxAppManagerService started
Parsed sample LAPK org.archpheneos.samples.hello entrypoint=/app/bin/hello
Staged payload path=/data/user/0/org.archpheneos.manager/files/archphene/payloads/org.archpheneos.samples.hello/bin/hello sha256=19df570de77aadd713757573e8cb41152a68b940f239b966c2592b36740ad2f2
```

Artifacts:

```text
artifacts/archphene-manager-staging-window.xml
artifacts/archphene-manager-staging.png
```

## Important discovery

When the payload asset was nested under `assets/payloads/` on Windows, `aapt2` packaged it with a backslash in the APK entry:

```text
assets/payloads\hello-static-x86_64.txt
```

Android `AssetManager` could not open it with:

```text
payloads/hello-static-x86_64.txt
```

Flattening the asset to:

```text
assets/payload-hello-static-x86_64.txt
```

fixed the issue. For this Windows-based manual `aapt2` build path, keep prototype assets flat or add an explicit zip normalization step before packaging nested assets.

## Interpretation

This proves the first payload lifecycle step:

```text
LAPK manifest
  -> payload asset metadata
  -> manager staging code
  -> app-private payload directory
  -> mode/hash/state reporting
  -> adb run-as verification
```

This is still not Linux ELF execution. The next milestone is command execution plumbing:

```text
manager stages payload
manager invokes a controlled helper command
manager captures stdout/stderr/exit code
manager renders launch result
```

After that, replace the placeholder payload with a real static x86_64 Linux ELF and test whether Android's app sandbox allows direct execution from app-private storage.
