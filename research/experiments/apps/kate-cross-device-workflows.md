# Kate cross-device workflows

Date: 2026-07-23

## Result

The unmodified Arch Kate 26.04.3-1 package passes the focused daily-use bridge
workflows on the maintained API 36 x86_64 emulator and Android 15 AArch64
Samsung.

The Samsung originally held a wrapper signed by an older development identity.
Before replacement, its installed APK and private state were archived. The
normal manager signer-migration flow rejected an in-place signer mismatch,
obtained Android's uninstall confirmation, resolved the current AArch64
closure, and installed the manager-owned wrapper. The exact original `katerc`
and large test document were then restored.

## Automated coverage

`test-mousepad-android-document-workflow.sh` is now label/package generic. With
Kate it proves:

1. Android SAF selection from Downloads.
2. Exact import into the wrapper-private Linux home.
3. In-editor modification and save writeback to the granted Android document.
4. Force-stop and collision-safe cold reopen with the edited bytes intact.
5. Browse through the Archphene DocumentsProvider in DocumentsUI.

`test-real-app-accessibility.sh --profile Kate` exercises the real File, View,
and Sessions menus, the session manager, and the KDE file-open dialog through
the Android framework semantic tree. Every state is checked for bounded,
actionable nodes.

`test-kate-workflows.sh` snapshots Kate's existing config, session directory,
and feedback state before creating temporary fixtures. It then proves:

- two distinct actionable document tabs;
- two non-overlapping editor panes after KDE's vertical-split shortcut;
- named-session persistence and discovery in Manage Sessions;
- a second mapped Linux Wayland top-level in the same Android/Linux process
  pair;
- Android Back closes only the secondary and reactivates the original window.

The exit trap force-stops the fixture and restores the exact saved state. On
the Samsung, the pre/post SHA-256 values matched for `katerc`,
`anonymous.katesession`, and `UserFeedback.org.kde.kate`.

The existing live-theme gate passes light/dark/light changes without warm
process replacement on both devices. The emulator large-display gate preserves
the same Kate process across tablet portrait/landscape changes and also maps,
renders, and accepts targeted input on a temporary 1920x1080 Android display.

## Commands

```bash
./scripts/test-mousepad-android-document-workflow.sh \
  --serial emulator-5554 \
  --package org.archphene.linux.pb1623042aeee4267eb8c86dead4b2dd7 \
  --label Kate
./scripts/test-real-app-accessibility.sh \
  --serial emulator-5554 \
  --target-package org.archphene.linux.pb1623042aeee4267eb8c86dead4b2dd7 \
  --profile Kate
./scripts/test-kate-workflows.sh --serial emulator-5554
./scripts/test-kate-live-theme.sh --serial emulator-5554
./scripts/test-kate-large-display.sh --serial emulator-5554
```

Repeat the first four commands with `--serial RFCT90AEEFA` for the physical
AArch64 lane. The secondary Android display test intentionally remains
emulator-only.

## Scope

These are bridge and integration tests, not Kate patches. No application code,
package payload, or per-Kate visual workaround is modified. The generic
Wayland, Qt/KDE, Android document, accessibility, appearance, and lifecycle
paths carry the behavior.
