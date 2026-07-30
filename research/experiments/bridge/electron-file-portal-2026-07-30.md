# Electron file portal validation — 2026-07-30

Current unmodified Code - OSS on the Android 16 x86_64 emulator and Visual
Studio Code on the Samsung Android 15 AArch64 device completed the same
state-preserving gate:

- The first `Ctrl+O` selected one file from a multiple-capable request.
- A second `Ctrl+O` selected two files in one operation.
- All three files were imported under `~/Documents/Android` with byte-exact
  content.
- `Ctrl+Shift+S` opened Android DocumentsUI and wrote a byte-exact destination
  in Android Downloads.
- The Code process group remained live and scoped logs contained no fatal event.
- The exact Code configuration, any pre-existing `code-flags.conf`, manager
  lifecycle, and all Android/Linux fixtures were restored.

The gate temporarily supplies Code's supported `--disable-workspace-trust`
switch so an application-specific trust prompt does not make generic portal
automation stateful. The flag is verified in the supervised process command and
removed or restored byte-exactly after the run.

Full-device evidence is under:

- `tooling/build/code-file-portal/emulator-5554/`
- `tooling/build/code-file-portal/RFCT90AEEFA/`

The captures expose one remaining UX issue. The Android destination uses the
name entered by the user, but Code continues to show the opaque local portal
staging basename. The private session directory already provides uniqueness;
the returned local URI should use a sanitized provider display name without an
additional session prefix.
