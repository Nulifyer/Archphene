#!/usr/bin/env python3

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def require(path: str, *markers: str) -> None:
    text = (ROOT / path).read_text(encoding="utf-8")
    missing = [marker for marker in markers if marker not in text]
    if missing:
        raise SystemExit(f"{path}: missing launcher visibility markers: {missing}")


require(
    "android/app/src/main/AndroidManifest.xml",
    'android:name="android.permission.QUERY_ALL_PACKAGES"',
    '<action android:name="android.intent.action.MAIN" />',
    '<category android:name="org.archphene.category.GENERATED_APP_SHELL" />',
)
require(
    "android/launcher-template/src/main/AndroidManifest.xml",
    '<category android:name="org.archphene.category.GENERATED_APP_SHELL" />',
    'android:name="org.archphene.launcher.DESCRIPTOR_ID"',
    'android:name="org.archphene.launcher.GENERATION"',
    'android:name="org.archphene.launcher.MANAGER_PACKAGE"',
    'android:name="org.archphene.launcher.TEMPLATE_SHA256"',
)
require(
    "android/app/src/main/kotlin/org/archphene/app/runtime/ArchpheneRuntimeService.kt",
    "queryIntentActivities(marker, 0)",
    "activity.name != LAUNCHER_ACTIVITY_CLASS",
    "!activity.exported",
    "!LAUNCHER_PACKAGE.matches(activity.packageName)",
    "packageManager.getPackageInfo(row.androidPackage, flags)",
    "PackageManager.GET_META_DATA",
    "PackageManager.GET_SIGNING_CERTIFICATES",
    'metadata.getString("org.archphene.launcher.DESCRIPTOR_ID")',
    'metadata.getString("org.archphene.launcher.MANAGER_PACKAGE")',
    "info.longVersionCode == generationValue",
    "LauncherApkSigner.signerSha256()",
    "installedTemplateDigest != \"h:$templateDigest\"",
    "LauncherApkAssembler.validMetadataCapabilities",
    "packageManager.packageInstaller.mySessions",
)

print(
    "Launcher visibility contract passed: marker discovery is bounded and every "
    "registry candidate still requires exact package, signer, descriptor, "
    "generation, template, capability, and installer-state verification."
)
