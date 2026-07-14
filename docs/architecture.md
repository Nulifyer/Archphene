# Architecture

Archphene runs a Linux desktop application as a child process of a normal Android application. Android remains responsible for package identity, installation, UID isolation, SELinux confinement, storage, lifecycle, and runtime permissions.

## Components

### Archphene manager

The manager discovers wrapped Linux applications through Android package metadata. It provides package search, update checks, version selection and pinning, prerelease policy, repository settings, verified APK installation, and GitHub Releases self-update discovery. Release assets are checksum-validated, package/signer-validated, and installed through Android confirmation.

The target product flow is:

```text
Arch repository metadata
  -> dependency resolution and signature verification
  -> on-device wrapper generation
  -> persistent per-device APK signing
  -> Android PackageInstaller
```

The full on-device transaction is proven for x86_64 KCalc: Arch resolution, signature verification, staging, closure reduction, wrapper assembly, persistent Android Keystore signing, and PackageInstaller installation. Mousepad and broad arbitrary-package support still require additional toolkit templates, ABI support, and capability policy.

### Wrapper application

Each Linux application is installed as a distinct APK with:

- a stable Android package name and signing identity;
- its Linux executable and ABI-specific runtime closure;
- an Android Activity and launcher entry;
- bridge capability and package-source metadata;
- private Android storage for background state;
- Android document brokers for user-visible files.

Separate APK identities preserve Android's per-app UID and lifecycle boundaries, but currently duplicate substantial runtime data.

### Wayland bridge

The Linux process connects to an app-local Wayland socket. The bridge maps Wayland surfaces, input, popups, dialogs, clipboard, IME, output changes, and Android window geometry into the Activity.

KCalc and Mousepad use the same Android Activity, input, clipboard, window host, and Rust native compositor. Application Activities contain only package metadata and inherit the shared bridge behavior.

### Runtime compatibility

Arch glibc and application libraries run inside the Android app sandbox. Source-level glibc compatibility patches replace optional or blocked startup syscall forms. They do not change the Android UID, grant permissions, bypass SELinux, or modify the kernel.

Official Arch Linux supplies x86_64 packages. AArch64 experiments use the separate Arch Linux ARM project and trust roots.

### Shared runtime modules

The manager can expose an exact content-hash runtime module through a non-exported, read-only `ContentProvider`. It launches a wrapper with an explicit temporary URI read grant. The wrapper validates the ELF descriptor and the shared native launcher executes `/proc/self/fd/<n>` after clearing close-on-exec. The process therefore runs under the wrapper's Android UID without copying that module into the wrapper sandbox.

This is currently a validated static-ELF mechanism, not a complete shared glibc/Qt runtime. A production runtime broker still needs a signed module catalog, atomic versioned packs, descriptor-based dynamic-library resolution, grant/revocation state across process death and reboot, and garbage collection that respects running processes.

### Storage

Private Linux state remains inside each wrapper's Android sandbox. User-visible files use Android's Storage Access Framework and document-provider APIs. See [Storage](storage.md).

## Trust boundaries

1. Repository metadata and packages must be retrieved over trusted HTTPS endpoints.
2. Package signatures and dependency closures must be verified before extraction.
3. Generated APK contents must be deterministic and bounded.
4. Android package names, versions, hashes, and signing identities must be verified.
5. Android's `PackageInstaller` remains the final installation authority.
6. Linux capabilities requiring Android permissions must cross explicit broker APIs.

See [Security](security.md) and [Package repositories](package-repositories.md).