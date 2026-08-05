#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
POLICY = ROOT / "docs/trust-policy.md"


def require(path: Path, *values: str) -> None:
    text = path.read_text(encoding="utf-8")
    for value in values:
        if value not in text:
            raise SystemExit(f"trust contract missing from {path}: {value}")


require(
    POLICY,
    "## Scope and shared trust domain",
    "## Official repository packages",
    "## AUR packages",
    "## Lifecycle scripts, hooks, and maintenance",
    "## Executables and runtime content",
    "## Android app-shell and Builder identity",
    "## Fail-closed rule and remaining evidence",
    "at most 32",
    "256 dependency edges",
    "archphene-shared-launcher-signing-v1",
    "RSA-3072",
    "v2/v3",
)
require(
    ROOT / "docs/README.md",
    "[Package and app-shell trust policy](trust-policy.md)",
)
for path in (
    ROOT / "docs/security.md",
    ROOT / "docs/architecture.md",
    ROOT / "docs/platform-compatibility.md",
):
    require(path, "trust-policy.md")

stale_claims = (
    "recursive AUR dependencies are not yet accepted",
    "Official-package scriptlets remain disabled",
    "Package hooks and install scripts are not generally enabled yet",
)
for path in (
    ROOT / "docs/security.md",
    ROOT / "docs/architecture.md",
    ROOT / "docs/platform-compatibility.md",
):
    text = path.read_text(encoding="utf-8")
    for claim in stale_claims:
        if claim in text:
            raise SystemExit(f"stale trust claim remains in {path}: {claim}")

require(
    ROOT / "crates/archphene-packages/src/aur.rs",
    "pub const MAX_AUR_GRAPH_BASES: usize = 32;",
    "pub const MAX_AUR_GRAPH_EDGES: usize = 256;",
)
require(
    ROOT / "crates/archphene-packages/src/lib.rs",
    "AUR_LIFECYCLE_CAPABILITY_HEADER",
    "refresh_package_hook_overrides",
    'output.extend_from_slice(b"HookDir = ");',
    "official_scriptlets",
    '"--noscriptlet"',
    "run_desktop_cache_adapter",
    "attach_desktop_owners",
    "explicitly_installed",
)
require(
    ROOT / "crates/archphene-process/src/lib.rs",
    "resolve_installed_command",
    'root.join("usr/bin").join(command)',
    "metadata.mode() & 0o002 != 0",
)
require(
    ROOT / "crates/archphene-packages/src/desktop.rs",
    "metadata.permissions().mode() & 0o022 != 0",
)
require(
    ROOT
    / "android/app/src/main/kotlin/org/archphene/app/launcher"
    / "LauncherApkSigner.kt",
    'KEY_ALIAS = "archphene-shared-launcher-signing-v1"',
    ".setKeySize(3072)",
    ".setV2SigningEnabled(true)",
    ".setV3SigningEnabled(true)",
    ".setDebuggableApkPermitted(false)",
    "Generated launcher signer identity changed",
)
require(
    ROOT
    / "android/app/src/main/kotlin/org/archphene/app/launcher"
    / "LauncherIdentityVerifier.kt",
    "getPackagesForUid(callingUid)",
    "packages.singleOrNull()",
    "LauncherApkSigner.signerSha256()",
)

print(
    "Trust policy contract passed: documentation matches the bounded AUR, "
    "lifecycle, execution, runtime-content, and app-shell-signing boundaries."
)
