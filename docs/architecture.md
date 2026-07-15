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

The full on-device transaction is proven for supported x86_64 Qt, GTK, and CLI packages: Arch resolution, signature verification, staging, closure reduction, package classification, wrapper assembly, persistent Android Keystore signing, and PackageInstaller installation. Generated wrappers carry the selected desktop label, executable, icon, MIME intents, toolkit, ABI, source URL, and an enforced bridge-capability contract. Broader toolkit coverage and AArch64 package runtime support remain incomplete.

Package conversions run as durable per-package jobs. Two package preparation jobs may overlap, while wrapper mutation/signing and Android installation confirmation use fair single-slot gates. State is committed before phase transitions, failures and cancellation are isolated by canonical package identity, and startup reconciles an Android install that completed after the manager process stopped.

### Wrapper application

Each Linux application is installed as a distinct APK with:

- a stable Android package name and signing identity;
- a thin Android code/resource shell without Linux runtime payloads;
- an Android Activity and launcher entry;
- bridge capability and package-source metadata;
- private Android storage for background state;
- Android document brokers for user-visible files.

Separate APK identities preserve Android's per-app UID and lifecycle boundaries. Linux executables, the patched glibc loader, and shared libraries remain in manager-owned immutable packs rather than being duplicated in each generated APK.

### Wayland bridge

The Linux process connects to an app-local Wayland socket. The bridge maps Wayland surfaces, input, popups, dialogs, clipboard, IME, output changes, and Android window geometry into the Activity.

KCalc and Mousepad use the same Android Activity, input, clipboard, window host, and Rust native compositor. Application Activities contain only package metadata and inherit the shared bridge behavior.

### Graphics bridge

For Wayland applications, the wrapper starts a Bionic virglrenderer helper under the same Android UID and exposes a private Unix socket inside the app cache directory. The glibc Mesa client selects `virpipe` and sends Gallium commands over that socket. Virglrenderer executes those commands through Android EGL/OpenGL ES. If the helper cannot initialize, the launcher selects `llvmpipe` without changing Android permissions or sandbox identity.

The current compositor accepts `wl_shm` output, so rendered frames still return through a CPU-visible shared-memory presentation path. This accelerates GL command execution but is not a zero-copy pipeline. Android HardwareBuffer/dmabuf import, Vulkan, and robust helper-loss recovery remain future work. See [GPU acceleration](gpu-acceleration.md).

### Runtime compatibility

Arch glibc and application libraries run inside the Android app sandbox. Source-level glibc compatibility patches replace optional or blocked startup syscall forms. They do not change the Android UID, grant permissions, bypass SELinux, or modify the kernel.

Official Arch Linux supplies x86_64 packages. AArch64 experiments use the separate Arch Linux ARM project and trust roots.

### Shared runtime packs

After package signature and extraction checks, the manager reduces the dependency closure and publishes an immutable pack under the SHA-256 of its bounded manifest. Publication uses a staging directory plus atomic rename. A separate atomic binding selects the active pack for one deterministic Android package identity.

Generated wrappers do not contain the Linux closure. On launch they call an exported manager provider with their Android package identity. The provider authenticates the Binder calling UID against the installed package, verifies the active binding and every manifest/file hash, and returns only exact read-only module descriptors with explicit URI grants. Untrusted shell access and package-name impersonation are rejected. The wrapper builds a private descriptor-backed symlink view and invokes the manager-owned patched glibc loader while the Linux process remains under the wrapper UID.

The emulator validates a cold app-drawer KCalc launch from the manager-owned pack, a separately supplied dependency, provider rejection for an untrusted caller, and reduction of the generated KCalc wrapper from 57,205,287 bytes to 628,675 bytes. Successful updates immediately remove superseded unreferenced packs, uninstall removes the released pack, and the manager cache action collects other unbound packs while package operations are idle. Remaining lifecycle work is running-process leases, uninstall reconciliation on every external path, process-tree cleanup, extraction reuse, and 4 KB/16 KB page-size validation.

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