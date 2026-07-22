# Mousepad Android document workflow

Date: 2026-07-12

## Validated result

The standard Android emulator now completes a bidirectional document workflow between Android shared storage and the unmodified Arch Mousepad 0.7.0 binary without a VM or broad storage permission.

Verified sequence:

1. `DocumentOpenActivity` launches Android `ACTION_OPEN_DOCUMENT` for `text/*`.
2. DocumentsUI grants a Downloads document URI with read, write, and persistable capabilities.
3. The Android wrapper copies the granted document into the app-private Linux view at `~/Documents/Android/<name>`.
4. The wrapper launches the stock Arch Mousepad ELF with that Linux path as its command-line document.
5. Mousepad edits and saves the private working file through normal POSIX file operations.
6. A `FileObserver` detects completed saves and immediately writes changed bytes back through `ContentResolver` to the original URI; lifecycle callbacks provide a fallback sync.
7. A cold relaunch reuses the persisted URI grant and reopens the updated shared document.
8. `LinuxHomeDocumentsProvider` exposes non-private Linux Home documents through Android DocumentsUI for file managers and share targets.

The automated test is:

```bash
./scripts/test-mousepad-android-document-workflow.sh
```

It passed the complete picker, edit, save, Downloads write-back, cold reopen, and DocumentsProvider read sequence.

## Security boundary

Mousepad never receives `/sdcard`, a broad storage permission, or the provider URI. The Linux process only sees an app-private path under its Android UID.

Android retains authority over the external document:

- selection is user-mediated by DocumentsUI;
- read/write access is represented by a scoped URI grant;
- write-back uses `ContentResolver.openOutputStream`;
- persisted access survives process restart but remains revocable by Android;
- dotfiles and private Linux implementation state are excluded from the exported DocumentsProvider.

## Android-facing entry points

`MainActivity` accepts `ACTION_VIEW` and `ACTION_EDIT` for text documents. Android file managers can therefore offer Mousepad for downloaded text files and pass the normal temporary or persistable URI grant.

`DocumentOpenActivity` is the generic explicit picker entry point used by the test and available to a future Linux application manager or portal broker. It, `AndroidDocumentSession`, and `LinuxHomeDocumentsProvider` live in `prototypes/shared-android-bridge` and are compiled into the Mousepad wrapper.

The manifest now contains exactly one `DocumentsProvider` declaration. The build script rejects duplicate provider registrations.

## Evidence

- `artifacts/android-document-opened.png`
- `artifacts/android-document-edited.png`
- `artifacts/android-document-reopened.png`
- `artifacts/mousepad-android-document-workflow.png`
- `artifacts/mousepad-document-picker-recent.xml`
- `artifacts/mousepad-document-picker-roots.xml`
- `artifacts/mousepad-document-picker-downloads.xml`

## Remaining storage work

- Represent long-lived document sessions in manager-owned state instead of only Activity fields.
- Detect external concurrent modifications and resolve conflicts.
- Add atomic replace support when a provider supports it.
- Add `ACTION_CREATE_DOCUMENT` as a first-class Save As portal for toolkit integration.
- Mount persisted document-tree grants behind stable Linux project paths.
- Add grant listing and revocation UI to the Linux application manager.
