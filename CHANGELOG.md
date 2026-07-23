# Changelog

Notable user-facing changes will be recorded here.

## Unreleased

### Fixed

- Hardened the persistent wrapper-signing regression before exercising it on maintained devices. It now requires the tested manager APK to match the installed bytes, preserves version/app-ID/first-install identity across a same-build reinstall, verifies a nonempty v2/v3-signed output, accepts Android's `appId` or legacy `userId` package field, fails explicitly on UI timeouts, and removes its approximately 192 MiB of public/private signing fixtures while restoring prior running state.
- Repaired the standalone PipeWire producer contract, which built successfully but waited forever for an Android camera broker inside its host container. The gate now enables the synthetic frame source, fails with captured producer/core diagnostics if registration does not occur, and directly asserts a numeric standard `Video/Source` node. The AArch64 native-readiness entry point now falls back to the pinned Android-native Podman image when the host SDK lacks NDK 29 instead of requiring an undocumented host install, and manager rejection fixtures now clean their staged APKs and restore prior running state.
- Reworked transient GPU-helper recovery to replace the same-UID virgl helper and restart the Linux runtime once through virpipe before considering software fallback. This preserves the Android host and acceleration on both x86_64 and AArch64 and avoids Samsung's physical-only llvmpipe `SIGILL`; current manager-generated GLMark2 wrappers pass sustained moving-frame and fault-injection gates on both devices.
- Strengthened package ranking and KCalc FD-lifecycle regressions: ranking now proves result order and restores the user's list filter, while descriptor testing warms one-time rotation allocation before enforcing total, SHM, fence, and ashmem bounds with stable processes, rendered output, clean logs, and prior-state restoration.
- Made the remaining repository search, KCalc version selector, physical freeform resize, and Terminal Vulkan standalone gates state-safe and assertion-complete. Catalog tests restore exact manager preferences, freeform no longer clears KCalc data, ADB command delivery is unambiguous, and Vulkan dependencies are provisioned through the verified manager pipeline.
- Made the manager pre-release and pull-to-refresh regressions accessibility-driven and state-safe. Both gates now preserve the user's original manager preferences while validating restart persistence, gesture thresholds, whole-list update completion, and settled list position on x86_64 and physical AArch64.
- Replaced KCalc's compressed-PNG byte comparison with exact semantic calculation and app-region pixel assertions, restored Terminal's nested prompt-collapse check with physical notification-permission preservation, and restored `wev`'s nonzero-runtime rejection. The generic candidate gate now selects the manager's existing direct-Wayland assembly mode; current manager-generated official `wev` wrappers pass the complete input protocol suite on x86_64 and AArch64.
- Restored the local current-source manager self-update fixture with exact APK identity/signature checks, Android-owned confirmation, stable install identity, current app-list reconciliation, and fail-safe restoration of the emulator's original manager APK and install-source policy.
- Restored the runtime-pack gate's catalog parser and unbound legacy-module rejection checks, alongside its existing cross-UID, descriptor-view, lease, and cleanup assertions. The complete trust-and-execution gate passes on x86_64 and physical AArch64.
- Restored the display/theme regressions discarded by the Bash migration: the release matrix again validates bounded manager controls, KCalc viewport fill, process continuity, and compositor logs across phone, tablet, landscape, and docked profiles; the shared toolkit gate now proves stable rendered light/dark/light transitions and live/cold equivalence; and rotation again validates geometry plus visible calculation input. KCalc and Mousepad pass the strengthened gates on x86_64 and physical AArch64.
- Restored the production GitHub self-update regression discarded by the Bash migration. The gate now installs and verifies its requested exact-ABI production baseline, validates published checksums and APK signatures, selects the requested release, requires Android-owned confirmation, proves installed version/signer/UID/first-install continuity, and checks restart reconciliation. The real published x86_64 `v1.0.0` to `v1.0.1` migration passes in an isolated temporary AVD.
- Hardened the real microphone-capture regression and refreshed its current manager-generated `pavucontrol` fixtures on x86_64 and physical AArch64. The gate now checks permission declaration/grant, private Pulse and AAudio startup, bounded capture size, nonzero samples, crash absence, fixture cleanup, and privacy restoration; it also exposed and removed a stale pre-manager-signer Samsung wrapper after preserving its home.
- Retired the obsolete hand-built KCalc update test path in favor of the supported manager-generated wrapper transaction. The replacement gate now verifies Android-owned confirmation, exact installed APK bytes and signer, stable version/UID/first-install identity, manager completion, and post-update KCalc execution on x86_64 and physical AArch64.
- Restored the complete official `wl-clipboard` regression on x86_64 and physical AArch64. Manager-generated `wl-paste` and `wl-copy` wrappers now directly assert exact bidirectional text, live clipboard ownership, focused selection/source protocol, demand-driven Android reads, Android installer updates, and clean runtime output.
- Restored the complete Terminal managed-shell regression on x86_64 and physical AArch64 and fixed the defects it exposed: verified commands can safely re-enter through an immutable APK-owned loader, AArch64 preload entry points carry the `GLIBC_2.17` symbol versions imported by Bash, package queries use Bash-native matching, manager requests no longer depend on blocked Android `date`/`rm`/`mv`/`grep`, and only bounded `sleep` joins the existing internal `cat` bridge. GNU architecture, package inventory, writable/persistent Home, cold restart, signed manager routing, isolation, and SAF transfers now pass.
- Restored the full Android capability-broker regression on current generated KCalc wrappers for x86_64 and physical AArch64 using disposable pinned-NDK probes: permission gating/retry, notification post/withdraw, HTTPS dispatch, unsafe-URI rejection, cross-UID denial, cleanup, and permission restoration now pass.
- Restored the Terminal document-transfer regression on emulator and physical AArch64 with unique self-cleaning fixtures, exact SAF import/export content checks, hidden-home export denial, traversal import denial, and permission-state restoration.
- Restored the Terminal companion isolation regression on emulator and physical AArch64: embedded artifact parity, installed-version parity, distinct UIDs, PTY ownership, signed package-search/result routing, forged-intent denial, and untrusted runtime-provider denial now have direct assertions.
- Restored the package-runtime trust regression on x86_64 and physical AArch64: bundled pacman execution, libalpm closure resolution, live signed download, exact detached-signer continuity, and appended-byte/GPG `BADSIG` rejection now have direct assertions.
- Restored the full manager list/detail Bash regression, including persisted package checks, search clearing, update-only filtering, Android app-settings return, background JobScheduler registration, and state cleanup on emulator and physical AArch64. Pacman update checks now resolve current versions through the architecture-selected repository database, fixing Arch Linux ARM wrappers that previously queried Arch Linux's x86 metadata endpoint and returned HTTP 404.
- Hardened the Bash regression harness after the host-script migration: deterministic emulator provisioning, exact UI matching, safe `set -u` initialization, SIGPIPE-safe image comparison, real non-installing physical runs, cold-launch synchronization, and robust keyboard/menu handling.
- Delivered all debug test intents consistently on both cold and warm manager Activity launches, restoring the Android PackageInstaller update regression when an existing manager task is reused.
- Corrected manager package-detail and Mousepad secondary-window regressions so their success messages are backed by the actual Android settings, popup, and child-window behavior they claim.
- Applied Material You accents to complete semantic GTK selected states without overriding Adwaita base surfaces, and canceled stale delayed IME requests when a popup takes interaction ownership.
- Split the GUI document restart probe into foreground-authorized stages so Android 15 background-activity-launch enforcement remains enabled during the regression.
- Restored the Bash secrets regression's packaged desktop-client coverage with a rebuilt official Arch libsecret/KWallet fixture, direct KWallet D-Bus operations, `kwallet-query`, restart persistence, cleanup, and log-redaction assertions.
- Restored the migrated Mousepad Android-document regression so it again proves the real SAF picker, edit/save writeback, cold reopen, and Archphene DocumentsUI provider on x86_64 and physical AArch64.
- Restored the Mousepad Open-dialog regression with exact InputConnection search text, Android IME retention/dismissal, bounded child-window geometry, accepted result routing, and rendered selection evidence on x86_64 and physical AArch64.
- Fixed GTK file-chooser accessibility by hydrating cache topology with live control geometry, actions, state, and transient children; carrying selected state into Android; and routing generic list-item activation through scaled component centers. Mousepad search results can now be selected and opened entirely through Android framework actions on x86_64 and AArch64.
- Restored the direct camera bridge regression's exact JPEG, I420 stream, private Camera portal, invalid-input, grant, denial, and no-reprompt assertions on x86_64 and physical AArch64.
- Fixed solid-magenta GTK4 camera previews on physical Android GPU drivers by selecting Cairo only for wrappers that declare camera capability. Unmodified Snapshot now passes timestamped PipeWire consumption and foreground pixel inspection on x86_64 and AArch64, and the destructive permission fixture preserves existing Linux homes, preferences, and camera grants.
- Replaced the migrated printing startup smoke with a full XDG-to-Android regression covering PreparePrint, rendered PDF preview, Save as PDF discovery, cancellation cleanup, invalid documents, non-regular descriptors, and runtime-pack binding on x86_64 and AArch64.

### Validation

- Passed persistent Android Keystore wrapper signing across byte-identical same-build manager reinstalls on both maintained devices on July 23, 2026. The emulator retained exact manager `1.0.0` bytes, version, app ID, first-install time, and signer `510bace5…b0475`; Samsung retained exact `1.0.2-gpu-recovery-debug` state and signer `a4f902d5…a5c90`. All signing inputs/outputs were removed afterward.
- Passed the host glibc path/exec broker, both manager-native ABI catalogs, AArch64 native readiness through the container fallback, the synthetic PipeWire producer, and the complete unmodified-GStreamer private runtime/cleanup gate on July 23, 2026. Current wrappers also pass audio output, nonzero microphone capture, runtime command isolation, signed-provider authorization, Foot config migration, Terminal prompt collapse, and manager downgrade/checksum rejection on both maintained devices; Samsung microphone privacy and both manager versions were restored.
- Replaced the archived stale Samsung GLMark2 wrapper after preserving its 16 MiB private sandbox, then passed current manager-generated GLMark2 install/launch, sustained distinct virpipe frames, stable process/geometry checks, and rendered same-UID helper replacement on the API 36 emulator and Android 15 Samsung on July 23, 2026. Samsung remains on manager `1.0.2-gpu-recovery-debug`; the emulator's original manager APK was restored byte-for-byte.
- Passed executable-owner repository ranking, multi-term installed-app filtering, warmed KCalc descriptor lifecycle, and Qt/GTK incomplete descriptor-library rejection plus healthy complete-cache launches on the API 36 emulator and Android 15 Samsung on July 23, 2026.
- Passed official package search/add/remove and architecture-adaptive KCalc version pinning on x86_64 and AArch64, non-destructive KCalc physical freeform resize on Samsung, and manager-provisioned unmodified `vulkaninfo` llvmpipe enumeration on both devices on July 23, 2026.
- Passed the state-preserving manager pre-release and pull-to-refresh gates on the API 36 emulator and Android 15 Samsung on July 23, 2026.
- Passed the restored five-profile release display matrix on the API 36 emulator and the strengthened KCalc/Mousepad live-theme plus KCalc rotation gates on both the emulator and Android 15 Samsung on July 23, 2026.
- Passed the full current-source API 36 x86_64 emulator suite and the non-destructive Android 15 AArch64 Samsung suite on July 22, 2026.
- Archived, deliberately migrated, and restored the Samsung KCalc and Mousepad sandboxes under the maintained manager signer. Current-source physical Qt/GTK appearance, interaction, lifecycle, and document-broker core gates pass on July 23, 2026.
- Updated both installed managers and regenerated Mousepad and KCalc through normal manager transactions on x86_64 and AArch64. The focused Open-dialog/IME/accessibility workflow plus the broader Mousepad and KCalc semantic regressions pass on both devices on July 23, 2026.

## 1.0.1 - 2026-07-18

### Added

- Complete on-device package resolution, signature verification, closure staging, wrapper generation, persistent signing, and Android installation for supported x86_64 and AArch64 packages.
- Exact-ABI manager, Terminal, runtime, compositor, and wrapper artifacts for x86_64 and arm64-v8a, including a one-time update path from the published x86_64 `v1.0.0` manager.
- A first-party tabbed Android Terminal with managed Arch CLI packages, verified Bash, durable package requests, foreground sessions, and SAF project trees.
- Android brokers and private desktop adapters for documents, URLs, notifications, audio, microphone, printing, camera/PipeWire, drag-and-drop, accessibility, and encrypted secrets.
- Private AT-SPI2 translation validated with unmodified KCalc/Qt and Mousepad/GTK on x86_64 and physical AArch64.
- Secret Service, libsecret, and KWallet flows validated on x86_64 and physical AArch64.

### Changed

- Replaced application-specific compositor copies with one shared Rust Wayland compositor and metadata-driven Android wrapper host.
- Moved Linux closures into manager-owned content-addressed runtime packs with authenticated providers, Binder-death leases, deduplicated blobs, and process-tree cleanup.
- Added compact stateful package rows, package search/ranking, version selection and pinning, isolated concurrent preparation, durable diagnostics, and self-update progress.
- Added Android-aware Qt/GTK light/dark appearance, font and density controls, IME handling, rotation, popups, dialogs, and secondary-window restoration.

### Security

- Reject incompatible ABIs and 4 KB ELF objects on 16 KB systems before execution.
- Generate package-specific capabilities, permissions, MIME intents, labels, icons, and source/runtime metadata.
- Keep Android PackageInstaller, app UIDs, SELinux, runtime permissions, SAF grants, and Keystore as the security authorities.

### Known limitations

- Package search is broader than tested application compatibility; Qt, GTK, SDL, Electron, Rust-native, XWayland, multimedia, and portal coverage still need expansion.
- Vulkan, zero-copy dmabuf presentation, robust GPU-helper recovery, and sustained vendor desktop-mode validation remain incomplete.
- GrapheneOS Pixel, physical x86_64 Android, and the complete phone/tablet/docked/freeform release matrix remain unvalidated.
