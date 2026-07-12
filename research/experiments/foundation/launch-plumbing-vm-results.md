# Launch plumbing VM results

Date: 2026-07-09

Result: passed for first subprocess launch and stdout/stderr capture from the ArchpheneOS manager.

## What changed

Added launch helper:

```text
prototypes/linux-app-manager-stub/src/org/archpheneos/manager/PayloadLauncher.java
```

Updated manager UI and service:

```text
prototypes/linux-app-manager-stub/src/org/archpheneos/manager/MainActivity.java
prototypes/linux-app-manager-stub/src/org/archpheneos/manager/LinuxAppManagerService.java
```

The helper currently runs:

```text
/system/bin/sh -c "/system/bin/cat <staged-payload-path>"
```

This deliberately does not execute a Linux ELF yet. It proves the manager can:

```text
stage payload
spawn a controlled helper command
capture stdout
capture stderr
capture exit code
report launch state in Android UI
log launch state through logcat
```

## VM verification

The updated manager APK built, signed, verified, installed, and launched in the running Android x86_64 GUI VM.

UI hierarchy artifact:

```text
artifacts/archphene-manager-launch-window.xml
```

Screenshot artifact:

```text
artifacts/archphene-manager-launch.png
```

## UI evidence

The UI reported:

```text
Launch plumbing result
Mode: android-shell-helper
Command: /system/bin/cat /data/user/0/org.archpheneos.manager/files/archphene/payloads/org.archpheneos.samples.hello/bin/hello
Exit code: 0
Timed out: false
Stdout:
#!/archphene/static-placeholder
This is a staged payload placeholder for org.archpheneos.samples.hello.
Next milestone replaces this file with a real x86_64 static Linux ELF.
Stderr:
```

## App-private verification

`run-as` confirmed the staged payload:

```text
total 8
-rwx------ 1 u0_a218 u0_a218 174 2026-07-09 10:48 hello
```

`run-as cat` confirmed the payload content:

```text
#!/archphene/static-placeholder
This is a staged payload placeholder for org.archpheneos.samples.hello.
Next milestone replaces this file with a real x86_64 static Linux ELF.
```

## Logcat evidence

```text
LinuxAppManagerService started
Parsed sample LAPK org.archpheneos.samples.hello entrypoint=/app/bin/hello
Staged payload path=/data/user/0/org.archpheneos.manager/files/archphene/payloads/org.archpheneos.samples.hello/bin/hello sha256=19df570de77aadd713757573e8cb41152a68b940f239b966c2592b36740ad2f2
Launch plumbing exit=0 stdoutBytes=174
```

## Interpretation

This proves the first launch-control data path:

```text
LAPK manifest
  -> payload staging
  -> app-private executable file
  -> controlled subprocess launch
  -> stdout/stderr/exit capture
  -> Android UI reporting
```

The next real compatibility test is replacing the text placeholder with a static x86_64 Linux ELF and attempting direct execution from the manager app sandbox.

Expected outcomes for that test:

```text
best case: static Linux ELF runs under Android's kernel/userspace constraints
likely blocker: app data filesystem or Android app sandbox disallows direct exec
if blocked: move execution to a native bridge helper or OS-level LinuxAppManager integration
```

## Next milestone

Build or fetch a tiny static x86_64 Linux ELF:

```c
int main(void) {
    write(1, "hello from linux elf\n", 21);
    return 0;
}
```

Then package it as the LAPK payload and test:

```text
manager stages real ELF
manager attempts exec
manager captures exit/stdout/stderr or kernel/SELinux denial
manager reports whether stock Android app sandbox can execute the payload
```
