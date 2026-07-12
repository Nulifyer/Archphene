# Direct Linux ELF VM results

Date: 2026-07-09

Result: direct execution from an untrusted Android app data directory is blocked by SELinux. The Linux ELF itself is valid and runs from `/data/local/tmp` as the shell user.

## What changed

Added Linux payload source:

```text
prototypes/linux-payloads/hello/main.go
```

Built Linux x86_64 payload:

```text
prototypes/linux-payloads/bin/hello-linux-amd64
```

Build settings:

```text
GOOS=linux
GOARCH=amd64
CGO_ENABLED=0
GO111MODULE=off
```

The resulting binary is an ELF:

```text
7F 45 4C 46 02 01 01 ...
```

Size:

```text
1593506 bytes
```

The sample LAPK now points at the real ELF payload:

```text
payload asset: payload-hello-linux-amd64
version: 0.2.0
linuxAbi: static-linux-elf
```

The manager now attempts direct execution:

```text
PayloadLauncher.runDirectElf(...)
```

## App sandbox result

The manager staged the ELF into app-private storage:

```text
/data/user/0/org.archpheneos.manager/files/archphene/payloads/org.archpheneos.samples.hello/bin/hello
```

File state:

```text
-rwx------ 1 u0_a218 u0_a218 1593506 2026-07-09 10:52 hello
sha256=76136d0afafb480c67517dea36450ec28b120ab4b73c29e036c74c6a2c00101c
```

SELinux context:

```text
u:object_r:app_data_file:s0:c218,c256,c512,c768
```

The manager attempted direct execution and got:

```text
Mode: direct-linux-elf
Exit code: -127
Start error: java.io.IOException: Cannot run program "/data/user/0/org.archpheneos.manager/files/archphene/payloads/org.archpheneos.samples.hello/bin/hello": error=13, Permission denied
Stdout:
Stderr:
```

SELinux denial:

```text
avc: denied { execute_no_trans } for path="/data/data/org.archpheneos.manager/files/archphene/payloads/org.archpheneos.samples.hello/bin/hello"
scontext=u:r:untrusted_app:s0:c218,c256,c512,c768
tcontext=u:object_r:app_data_file:s0:c218,c256,c512,c768
tclass=file
permissive=0
```

## Control test

The same ELF was pushed to `/data/local/tmp` and executed as the adb shell user:

```text
adb push prototypes/linux-payloads/bin/hello-linux-amd64 /data/local/tmp/hello-linux-amd64
adb shell chmod 755 /data/local/tmp/hello-linux-amd64
adb shell /data/local/tmp/hello-linux-amd64
```

Output:

```text
hello from linux elf
goos=linux goarch=amd64
```

Shell-side context:

```text
-rwxr-xr-x 1 shell shell u:object_r:shell_data_file:s0 1593506 /data/local/tmp/hello-linux-amd64
```

## Artifacts

UI hierarchy:

```text
artifacts/archphene-manager-elf-window.xml
```

Screenshot:

```text
artifacts/archphene-manager-elf.png
```

## Interpretation

This is a key feasibility result.

The Android x86_64 emulator kernel can run a static Linux x86_64 ELF. The blocker is not CPU architecture, ELF format, or missing glibc. The blocker is Android's app sandbox and SELinux policy:

```text
untrusted_app cannot execute app_data_file without a domain transition
```

That means a normal APK-only Linux bridge cannot directly execute arbitrary Linux payloads from app-private storage on modern Android/GrapheneOS while preserving normal target SDK behavior.

This strongly supports the earlier architecture conclusion:

```text
APK-only manager: can parse, stage, update, display, and maybe broker via approved helpers
OS-integrated ArchpheneOS: needed for first-class Linux app execution
```

## Next options

### Option A: native bridge helper inside APK

Package executable code as Android-supported native libraries or lib/ payloads and launch through Android-approved mechanisms. This is better for bridge components, but it does not solve arbitrary Arch Linux ELF execution.

### Option B: adb/shell prototype path

Continue using `/data/local/tmp` as a development-only executor to test Linux ELF compatibility, stdio capture, and runtime behavior. This is useful for VM research but not a user product.

### Option C: platform integration

Add an ArchpheneOS platform service and SELinux policy:

```text
linux_app_manager domain
linux_app_data_file or linux_payload_file type
controlled exec transition into linux_app domain
per-app UID / mount namespace / seccomp / capability policy
```

This is the real path for "Linux apps as Android apps."

## Next milestone

Prototype the platform design in source form:

```text
docs/platform-execution-design.md
```

Define:

- SELinux domains/types
- package install locations
- exec transition model
- per-app UID/data/runtime layout
- how APK identity maps to Linux payload identity
- which parts require AOSP/GrapheneOS framework changes
