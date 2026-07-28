#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=
package=
manager=org.archphene.app.debug
artifact_dir=
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --package) package="${2:?missing value for --package}"; shift 2 ;;
    --manager) manager="${2:?missing value for --manager}"; shift 2 ;;
    --artifact-dir) artifact_dir="${2:?missing value for --artifact-dir}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL --package GENERATED_LAUNCHER [--manager PACKAGE] [--artifact-dir PATH]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

[[ -n "$serial" ]] || archphene_die "--serial is required"
[[ "$package" =~ ^org\.archphene\.linux\.p[0-9a-f]{32}$ ]] ||
  archphene_die "--package must be a generated Archphene launcher"
[[ "$manager" =~ ^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$ ]] ||
  archphene_die "--manager is invalid"
archphene_test_init "$serial"

activity="$(archphene_launcher "$package")"
serial_slug="${serial//[^A-Za-z0-9._-]/_}"
artifact_dir="${artifact_dir:-$ARCHPHENE_ROOT/tooling/build/folder-portal-timeout/$serial_slug}"
mkdir -p "$artifact_dir"

folder=Documents
source_path="files/arch-root/home/archphene/$folder"
target_path="files/arch-root/home/archphene/Projects/$folder"
delay_file=cache/portal-folder-provider-delay-ms
probe_output=cache/portal-folder-timeout-probe.out

cleanup() {
  archphene_adb_run shell run-as "$manager" rm -f "$delay_file" "$probe_output" \
    >/dev/null 2>&1 || true
  archphene_adb_run shell run-as "$manager" rm -rf "$target_path" \
    >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop com.google.android.documentsui \
    >/dev/null 2>&1 || true
  archphene_adb_run shell am force-stop com.android.documentsui \
    >/dev/null 2>&1 || true
}
trap cleanup EXIT

archphene_adb_run shell run-as "$manager" test -d "$source_path" ||
  archphene_die "shared timeout source is unavailable: $source_path"
archphene_adb_run shell run-as "$manager" test ! -e "$target_path" ||
  archphene_die "refusing to overwrite timeout target: $target_path"
printf '60000\n' |
  archphene_adb_run shell run-as "$manager" sh -c \
    "'tee \"$delay_file\" >/dev/null'"

archphene_adb_run logcat -c
archphene_adb_run shell am force-stop "$package"
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log \
  'Private desktop portal ready session=' 30 \
  'ArchphenePortal:I ArchpheneLauncherSession:I AndroidRuntime:E *:S' >/dev/null

bus=
deadline=$((SECONDS + 10))
while ((SECONDS < deadline)); do
  bus="$(
    archphene_adb_run shell run-as "$manager" find cache -name bus -print |
      tr -d '\r'
  )"
  [[ "$bus" =~ ^cache/p[1-9][0-9]*-[0-9a-f]{16}/bus$ ]] && break
  sleep 0.3
done
[[ "$bus" =~ ^cache/p[1-9][0-9]*-[0-9a-f]{16}/bus$ ]] ||
  archphene_die "expected exactly one private launcher bus, received: $bus"

package_dump="$(archphene_adb_run shell dumpsys package "$manager")"
native_dir="$(
  sed -n 's/.*legacyNativeLibraryDir=//p' <<<"$package_dump" |
    head -n 1 |
    tr -d '\r'
)"
abi="$(
  sed -n 's/.*primaryCpuAbi=//p' <<<"$package_dump" |
    head -n 1 |
    tr -d '\r'
)"
case "$abi" in
  arm64-v8a) abi_directory=arm64 ;;
  x86_64) abi_directory=x86_64 ;;
  *) archphene_die "unsupported manager ABI: $abi" ;;
esac
address="unix:path=/data/user/0/$manager/$bus"
probe="$native_dir/$abi_directory/libarchphene_portal_probe.so"
command="run-as $manager sh -c 'DBUS_SESSION_BUS_ADDRESS=$address \"$probe\" open-directory > \"$probe_output\" 2>&1 &'"
archphene_adb_run shell "$command"

archphene_wait_ui \
  'package="com\.(google\.)?android\.documentsui"' \
  "folder-timeout-picker-$serial_slug" 20
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" 'content-desc="Show roots"' "DocumentsUI roots"
archphene_wait_ui_exact_text \
  "Linux home files" "folder-timeout-root-$serial_slug" 20
read -r root_x root_y < <(
  python3 -c '
import re, sys
import xml.etree.ElementTree as ET
root = ET.fromstring(sys.stdin.read())
candidates = []
for parent in root.iter("node"):
    descendants = list(parent.iter("node"))
    if (
        any(node.attrib.get("text") == "Archphene Home" for node in descendants)
        and any(node.attrib.get("text") == "Linux home files" for node in descendants)
    ):
        candidates.append((len(descendants), descendants))
if not candidates:
    raise SystemExit("manager Archphene Home root is unavailable")
descendants = min(candidates, key=lambda candidate: candidate[0])[1]
title = next(
    node for node in descendants
    if node.attrib.get("text") == "Archphene Home"
)
values = list(map(int, re.findall(r"\d+", title.attrib["bounds"])))
print((values[0] + values[2]) // 2, (values[1] + values[3]) // 2)
' <<<"$ARCHPHENE_UI"
)
archphene_adb_run shell input tap "$root_x" "$root_y"
deadline=$((SECONDS + 20))
while ((SECONDS < deadline)); do
  ARCHPHENE_UI="$(
    archphene_capture_ui "folder-timeout-source-$serial_slug" 2>/dev/null || true
  )"
  [[ "$ARCHPHENE_UI" == *"text=\"$folder\""* ]] && break
  archphene_adb_run shell input swipe 540 1700 540 600 400
  sleep 0.4
done
[[ "$ARCHPHENE_UI" == *"text=\"$folder\""* ]] ||
  archphene_die "timed out waiting for Android folder: $folder"
archphene_tap_text "$ARCHPHENE_UI" "$folder"
archphene_wait_ui \
  'text="USE THIS FOLDER"[^>]*enabled="true"' \
  "folder-timeout-use-$serial_slug" 20
archphene_adb_run exec-out screencap -p >"$artifact_dir/picker-ready.png"
archphene_tap_text "$ARCHPHENE_UI" "USE THIS FOLDER"
archphene_wait_ui_exact_text \
  "ALLOW" "folder-timeout-allow-$serial_slug" 20

started=$SECONDS
archphene_tap_text "$ARCHPHENE_UI" "ALLOW"
timeout_log="$(
  archphene_wait_log \
    'Android directory provider remained blocked while attempting to list Android folder' \
    45 'ArchpheneLauncher:E AndroidRuntime:E *:S'
)"
elapsed=$((SECONDS - started))
((elapsed >= 30 && elapsed <= 40)) ||
  archphene_die "provider watchdog fired outside its 30-second deadline: ${elapsed}s"
[[ "$timeout_log" != *'FATAL EXCEPTION'* ]] ||
  archphene_die "provider watchdog caused an Android exception"

deadline=$((SECONDS + 10))
while ((SECONDS < deadline)); do
  if ! archphene_adb_run shell pidof "$package" >/dev/null 2>&1; then
    break
  fi
  sleep 0.2
done
if archphene_adb_run shell pidof "$package" >/dev/null 2>&1; then
  archphene_die "launcher survived a provider that ignored cancellation"
fi
archphene_adb_run shell pidof "$manager" >/dev/null ||
  archphene_die "provider timeout killed the manager"
archphene_adb_run shell run-as "$manager" test ! -e "$target_path" ||
  archphene_die "provider timeout published a partial project"
archphene_adb_run exec-out screencap -p >"$artifact_dir/rolled-back.png"

trap - EXIT
cleanup
archphene_note "Folder provider timeout passed on $serial"
archphene_note "  The 30-second watchdog stopped only the blocked launcher; the manager survived"
archphene_note "  Rust published no partial project"
archphene_note "  Full-device screenshots: $artifact_dir/{picker-ready,rolled-back}.png"
