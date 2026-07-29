#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/common.sh"

preferences="$ARCHPHENE_ROOT/android/app/src/main/kotlin/org/archphene/app/ArchphenePreferences.kt"
settings="$ARCHPHENE_ROOT/android/app/src/main/kotlin/org/archphene/app/appearance/LinuxAppearanceSettingsView.kt"
session="$ARCHPHENE_ROOT/android/app/src/main/kotlin/org/archphene/app/launcher/LauncherSessionService.kt"
service="$ARCHPHENE_ROOT/android/app/src/main/kotlin/org/archphene/app/runtime/ArchpheneRuntimeService.kt"
android_bridge="$ARCHPHENE_ROOT/crates/archphene-android/src/lib.rs"
runtime="$ARCHPHENE_ROOT/crates/archphene-runtime/src/lib.rs"
strings="$ARCHPHENE_ROOT/android/app/src/main/res/values/strings.xml"

for file in "$preferences" "$settings" "$session" "$service" "$android_bridge" \
    "$runtime" "$strings"; do
  archphene_require_file "$file"
done

grep -Fq 'val reducedIsolationElectron: Boolean = false' "$preferences" \
  || archphene_die 'reduced-isolation Electron policy is not default-off'
grep -Fq '.setPositiveButton(R.string.enable)' "$settings" \
  || archphene_die 'reduced-isolation Electron mode lacks explicit confirmation'
grep -Fq 'setReducedIsolationElectron(true)' "$settings" \
  || archphene_die 'confirmed Electron consent is not persisted'
grep -Fq 'preferences.reducedIsolationElectron' "$session" \
  || archphene_die 'launcher sessions do not capture Electron consent'
grep -Fq '"G4\t$androidPackage' "$service" \
  || archphene_die 'Android launcher wire does not publish Electron consent'
grep -Fq 'parse_launcher_reduced_isolation(version, &mut fields)' "$android_bridge" \
  || archphene_die 'Rust Android launcher wire does not validate Electron consent'
grep -Fq 'integration_topology & TOPOLOGY_CHROMIUM == 0' "$runtime" \
  || archphene_die 'Electron flags are not restricted to verified Chromium topology'
for flag in '--no-sandbox' '--disable-dev-shm-usage'; do
  grep -Fq -- "\"$flag\"" "$runtime" \
    || archphene_die "generic Electron policy is missing $flag"
done
grep -Fq 'renderer processes are not separately isolated' "$strings" \
  || archphene_die 'consent warning does not explain the reduced process isolation'

archphene_note 'Electron compatibility source contract passed: default-off consent, G4 propagation, verified topology, and explicit isolation warning are present.'
