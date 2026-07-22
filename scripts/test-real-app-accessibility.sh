#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=
target=
profile=KCalc
probe_apk=
adb_path=
timeout=30
artifact_dir=
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --target-package) target="${2:?}"; shift 2 ;;
    --profile) profile="${2:?}"; shift 2 ;;
    --probe-apk) probe_apk="${2:?}"; shift 2 ;;
    --adb-path) adb_path="${2:?}"; shift 2 ;;
    --timeout-seconds) timeout="${2:?}"; shift 2 ;;
    --artifact-dir) artifact_dir="${2:?}"; shift 2 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" && -n "$target" ]] \
  || archphene_die '--serial and --target-package are required'
archphene_validate_choice "$profile" profile KCalc Mousepad
[[ -z "$adb_path" ]] || ARCHPHENE_ADB="$adb_path"
archphene_test_init "$serial"

probe=org.archphene.accessibilityprobe
service="$probe/org.archphene.bridge.ProbeAccessibilityService"
if [[ -n "$probe_apk" ]]; then
  archphene_require_file "$probe_apk"
  archphene_adb_run install -r "$probe_apk" >/dev/null
else
  archphene_adb_run shell pm path "$probe" >/dev/null 2>&1 \
    || archphene_die 'the test AccessibilityService is not installed; pass --probe-apk only when installation is explicitly approved'
fi
archphene_adb_run shell pm path "$target" >/dev/null

safe_target="$(sed 's/[^A-Za-z0-9._-]/_/g' <<<"$target")"
tree_name="framework-accessibility-tree-$safe_target.txt"
safe_serial="${serial//[^A-Za-z0-9._-]/_}"
artifact_dir="${artifact_dir:-$ARCHPHENE_ROOT/tooling/artifacts/visual-audit/$safe_serial/${profile,,}-accessibility}"
mkdir -p "$artifact_dir"
old_services="$(archphene_adb_run shell settings get secure enabled_accessibility_services | tr -d '\r')"
old_enabled="$(archphene_adb_run shell settings get secure accessibility_enabled | tr -d '\r')"
restore() {
  if [[ "$old_services" == null || -z "$old_services" ]]; then
    archphene_adb_run shell settings delete secure enabled_accessibility_services >/dev/null 2>&1 || true
  else
    archphene_adb_run shell settings put secure enabled_accessibility_services "$old_services" >/dev/null 2>&1 || true
  fi
  archphene_adb_run shell settings put secure accessibility_enabled "$old_enabled" >/dev/null 2>&1 || true
}
trap restore EXIT

archphene_adb_run shell run-as "$probe" rm -f "files/$tree_name"
archphene_adb_run shell settings put secure enabled_accessibility_services "$service"
archphene_adb_run shell settings put secure accessibility_enabled 1
archphene_adb_run logcat -c
activity="$(archphene_launcher "$target")"
archphene_adb_run shell am force-stop "$target"
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'mapped=true' "$timeout" 'ArchpheneInput:V AndroidRuntime:E *:S' >/dev/null

deadline=$((SECONDS + timeout))
tree=
node_count=0
while ((SECONDS < deadline)); do
  tree="$(archphene_adb_run shell run-as "$probe" cat "files/$tree_name" 2>/dev/null || true)"
  node_count="$(grep -c '^NODE|' <<<"$tree" || true)"
  if ((node_count >= 6)) && [[ "$tree" == *"$profile"* ]]; then
    break
  fi
  sleep .25
done
((node_count >= 6)) && [[ "$tree" == *"$profile"* ]] \
  || archphene_die "real $profile semantic tree did not settle (nodes=$node_count)"
printf '%s\n' "$tree" >"$artifact_dir/accessibility-tree.txt"
read -r width height <<<"$(archphene_adb_run shell wm size \
  | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -n1)"
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/accessibility-tree-check.py" \
  "$artifact_dir/accessibility-tree.txt" --expected-text "$profile" \
  --display-width "$width" --display-height "$height"

log="$(archphene_adb_run logcat -d -s ArchpheneAccessibilityProbe:I ArchpheneInput:V AndroidRuntime:E '*:S')"
printf '%s\n' "$log" >"$artifact_dir/logcat.txt"
[[ "$log" != *'FATAL EXCEPTION'* ]] || archphene_die 'target or accessibility probe crashed'
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/visual-manifest.py" \
  "$artifact_dir/manifest.json" \
  --field "serial=$serial" --field "package=$target" --field "app=$profile" \
  --field 'state=real Android semantic tree' --field 'toolkit=AT-SPI' \
  --field "display=${width}x${height}" \
  --artifact "$artifact_dir/accessibility-tree.txt" \
  --artifact "$artifact_dir/logcat.txt"
archphene_note "Real $profile accessibility passed with exported roles, states, actions, and bounded nodes. Evidence: $artifact_dir"
