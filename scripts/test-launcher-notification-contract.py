#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def require(path: Path, *values: str) -> None:
    text = path.read_text(encoding="utf-8")
    for value in values:
        if value not in text:
            raise SystemExit(f"launcher notification contract missing from {path}: {value}")


capabilities = "wayland,input,ime,clipboard,documents,open-uri,notifications"
require(
    ROOT / "crates/archphene-launcher/src/lib.rs",
    "pub const LAUNCHER_CAPABILITIES_V4",
    capabilities,
)
require(
    ROOT / "android/launcher-template/src/main/AndroidManifest.xml",
    '<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />',
)
require(
    ROOT
    / "android/launcher-template/src/main/kotlin/org/archphene/launcher"
    / "LauncherActivity.kt",
    f'"c:{capabilities}"',
    "private const val PROTOCOL_VERSION = 21",
    "CALLBACK_NOTIFICATION",
    "arrayOfNulls<PendingNotification>(LauncherNotificationPolicy.MAX_PENDING)",
    "requestPermissions(",
    "manager.notify(pending.id, LINUX_NOTIFICATION_ID, notification)",
)
require(
    ROOT
    / "android/app/src/main/kotlin/org/archphene/app/launcher"
    / "LauncherPortalBridge.kt",
    '"NOTIFY" -> handleNotificationRequest(client, fields)',
    '"WITHDRAW_NOTIFICATION"',
    "MAX_NOTIFICATION_BODY_BYTES = 8_192",
)
require(
    ROOT
    / "android/app/src/main/kotlin/org/archphene/app/launcher"
    / "LauncherSessionService.kt",
    "requestNotification = { id, title, body ->",
    "CALLBACK_NOTIFICATION",
    "private const val PROTOCOL_VERSION = 21",
)
require(
    ROOT / "docs/android-capabilities.md",
    "## Current production app-shell contract",
    "first notification",
    "wrapper APK owns the resulting Android notification",
)

print(
    "Launcher notification contract passed: V4 declaration, bounded broker, "
    "runtime consent, wrapper attribution, and withdrawal are enforced."
)
