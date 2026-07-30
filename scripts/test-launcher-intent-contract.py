#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def require(path: Path, *values: str) -> None:
    text = path.read_text(encoding="utf-8")
    for value in values:
        if value not in text:
            raise SystemExit(f"launcher intent contract missing from {path}: {value}")


require(
    ROOT / "android/launcher-template/src/main/AndroidManifest.xml",
    'android:name="org.archphene.launcher.MIME_TYPES"',
    '<action android:name="android.intent.action.VIEW" />',
    '<action android:name="android.intent.action.SEND" />',
    '<data android:scheme="content" />',
)
require(
    ROOT
    / "android/launcher-template/src/main/kotlin/org/archphene/launcher"
    / "LauncherActivity.kt",
    "private const val PROTOCOL_VERSION = 10",
    'uri?.scheme != "content"',
    "LauncherIntentMimePolicy.matches(declared, mimeType)",
    'contentResolver.openFileDescriptor(request.uri, "r", cancellation)',
    "incoming.descriptor.writeToParcel(data, 0)",
)
require(
    ROOT
    / "android/app/src/main/kotlin/org/archphene/app/launcher"
    / "LauncherSessionService.kt",
    "private const val PROTOCOL_VERSION = 10",
    "identity.mimeTypes != authorization.mimeTypes",
    "portalBridge.importLaunchDocument(pendingLaunchDocument)",
    "session.launchDocumentPath",
)
require(
    ROOT
    / "android/app/src/main/kotlin/org/archphene/app/launcher"
    / "LauncherPortalBridge.kt",
    '"home/archphene/Documents/Android"',
    'check(running) { "Portal session closed during document import" }',
)
require(
    ROOT / "crates/archphene-android/src/lib.rs",
    '"A3\\t{}\\t{}\\t{}\\t{}\\n"',
    '"G6"',
    '"W4\\t{}\\t{}\\t{}\\t{}\\t{}\\t{}\\t{}\\t{}\\n"',
)
require(
    ROOT / "crates/archphene-runtime/src/lib.rs",
    "pub launch_document_path: Option<&'a str>",
    'const PREFIX: &str = "/home/archphene/Documents/Android/";',
    "ExecArgument::SingleFile | ExecArgument::MultipleFiles",
    "ExecArgument::SingleUrl | ExecArgument::MultipleUrls",
)

print(
    "Launcher intent contract passed: signed MIME declarations, content-only "
    "Android intake, SAF import, and desktop-entry argument delivery are enforced."
)
