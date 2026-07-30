#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
skip_install=true
target=git
target_file_relative=usr/bin/git
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --install-apk) skip_install=false; shift ;;
    --package) target="${2:?missing value for --package}"; shift 2 ;;
    --file) target_file_relative="${2:?missing value for --file}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL [--apk PATH --install-apk] [--package NAME --file ROOT_RELATIVE_FILE]"
      exit 0 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"
if [[ "$skip_install" == false ]]; then
  [[ -n "$apk" ]] || archphene_die "--apk is required with --install-apk"
fi
[[ "$target" =~ ^[a-zA-Z0-9@._+-]{1,128}$ ]] ||
  archphene_die "invalid target package"
[[ "$target_file_relative" =~ ^[a-zA-Z0-9@._+/-]{1,1024}$ &&
  "$target_file_relative" != /* && "$target_file_relative" != *".."* ]] ||
  archphene_die "invalid target file"

archphene_test_init "$serial"
package=org.archphene.app.debug
activity="$package/org.archphene.app.MainActivity"
receiver="$package/org.archphene.app.PackagePhaseTestReceiver"
action=org.archphene.app.debug.action.START_INTERRUPTED_PACKAGE_REMOVAL
local_entry=
intent=files/arch-root/run/package-mutation-v1
removal_repair=files/arch-root/run/package-removal-repair-v1
removal_repair_temp=files/arch-root/run/package-removal-repair-v1.tmp
local_repair_temp=files/arch-root/var/lib/pacman/local/.archphene-removal-repair.tmp
target_file="files/arch-root/$target_file_relative"
output_dir="$ARCHPHENE_ROOT/tooling/build/package-mutation-recovery"
mkdir -p "$output_dir"
digest_root="$(mktemp -d)"
initially_running=false
original_section=

restore_section() {
  [[ -n "$original_section" ]] || return 0
  local center ui x y
  ui="$(archphene_capture_ui "package-recovery-restore-$serial" 2>/dev/null || true)"
  center="$(
    archphene_ui_node_center \
      "$ui" \
      "text=\"$original_section\"[^>]*class=\"android\\.widget\\.Button\"" \
      "$original_section" 2>/dev/null || true
  )"
  if [[ "$center" =~ ^[0-9]+[[:space:]][0-9]+$ ]]; then
    read -r x y <<<"$center"
    archphene_adb_run shell input tap "$x" "$y" >/dev/null 2>&1 || true
  fi
}

cleanup() {
  set +e
  archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  if [[ -n "$local_entry" ]] &&
      archphene_adb_run shell run-as "$package" test -e "$target_file" 2>/dev/null &&
      ! archphene_adb_run shell run-as "$package" test -f "$local_entry/desc" 2>/dev/null &&
      archphene_adb_run shell run-as "$package" test -f "$removal_repair/desc" 2>/dev/null; then
    archphene_adb_run shell run-as "$package" mkdir -p "$local_entry" \
      >/dev/null 2>&1 || true
    for database_file in changelog desc files install mtree; do
      if archphene_adb_run shell run-as "$package" \
        test -f "$removal_repair/$database_file" 2>/dev/null; then
        archphene_adb_run shell run-as "$package" cp -p \
          "$removal_repair/$database_file" "$local_entry/$database_file" \
          >/dev/null 2>&1 || true
      fi
    done
    archphene_adb_run shell run-as "$package" rm -f "$intent" >/dev/null 2>&1 || true
    archphene_adb_run shell run-as "$package" rm -f \
      "$removal_repair/changelog" "$removal_repair/desc" \
      "$removal_repair/files" "$removal_repair/install" \
      "$removal_repair/mtree" >/dev/null 2>&1 || true
    archphene_adb_run shell run-as "$package" rmdir "$removal_repair" \
      >/dev/null 2>&1 || true
  fi
  rm -f -- "$digest_root/changelog" "$digest_root/desc" "$digest_root/files" \
    "$digest_root/install" "$digest_root/mtree"
  rmdir -- "$digest_root"
  archphene_adb_run shell am start -W -n "$activity" >/dev/null 2>&1 || true
  restore_section >/dev/null 2>&1 || true
  if [[ "$initially_running" == false ]]; then
    archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

archphene_require_command python3
if archphene_android_pid "$package" >/dev/null 2>&1; then
  initially_running=true
fi
if [[ "$skip_install" == false ]]; then
  archphene_require_file "$apk"
  archphene_adb_run install -r "$apk" >/dev/null
fi
archphene_adb_run shell pm path "$package" >/dev/null ||
  archphene_die "$package is not installed; pass --install-apk with --apk"
archphene_adb_run shell am force-stop "$package" >/dev/null
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null
initial_ui="$(archphene_capture_ui "package-recovery-initial-$serial")"
original_section="$(
  python3 -c '
import re, sys
text = sys.stdin.read()
for name in ("Packages", "Files", "Terminal", "Settings"):
    if re.search(
        rf"text=\"{name}\"[^>]*class=\"android\.widget\.Button\"[^>]*selected=\"true\"",
        text,
    ):
        print(name)
        break
' <<<"$initial_ui"
)"
[[ -n "$original_section" ]] ||
  archphene_die "could not determine the original manager section"

local_entry="$(
  archphene_adb_run exec-out run-as "$package" find \
    files/arch-root/var/lib/pacman/local -maxdepth 1 -type d -name "$target-*" |
    tr -d '\r' |
    head -n1
)"
[[ -n "$local_entry" ]] || archphene_die "$target must be installed before the recovery gate"
archphene_adb_run shell run-as "$package" test -x "$target_file" ||
  archphene_die "$target_file must exist before the recovery gate"
version="$(
  archphene_adb_run exec-out run-as "$package" cat "$local_entry/desc" |
    tr -d '\r' |
    awk '/^%VERSION%$/{getline; print; exit}'
)"
[[ "$version" =~ ^[^[:space:]]{1,128}$ ]] ||
  archphene_die "could not read the installed $target version"
original_reason="$(
  archphene_adb_run exec-out run-as "$package" cat "$local_entry/desc" |
    tr -d '\r' |
    awk '/^%REASON%$/{getline; print; exit}'
)"
[[ -z "$original_reason" ]] ||
  archphene_die "$target must be explicitly installed before the recovery gate"
archive="$(
  archphene_adb_run exec-out run-as "$package" find \
    files/arch-root/var/cache/pacman/pkg -maxdepth 1 -type f \
    -name "$target-$version-*.pkg.tar.*" ! -name '*.sig' ! -name '*.part' |
    tr -d '\r'
)"
[[ -n "$archive" && "$archive" != *$'\n'* ]] ||
  archphene_die "expected one retained $target $version archive"
archphene_adb_run shell run-as "$package" test -f "$archive.sig" ||
  archphene_die "$target archive has no detached signature"
archphene_adb_run shell run-as "$package" test ! -e "$intent" ||
  archphene_die "an existing package mutation must be repaired first"
archphene_adb_run shell run-as "$package" test ! -e "$removal_repair" ||
  archphene_die "an existing removal repair snapshot must be repaired first"

for database_file in changelog desc files install mtree; do
  if archphene_adb_run shell run-as "$package" \
    test -f "$local_entry/$database_file"; then
    archphene_adb_run exec-out run-as "$package" \
      cat "$local_entry/$database_file" >"$digest_root/$database_file"
  fi
done
database_sha256="$(
  python3 - "$digest_root" <<'PY'
import hashlib
import pathlib
import struct
import sys

root = pathlib.Path(sys.argv[1])
digest = hashlib.sha256()
digest.update(b"org.archphene.package-removal-repair.v1\0")
for name in ("changelog", "desc", "files", "install", "mtree"):
    path = root / name
    if not path.exists():
        continue
    data = path.read_bytes()
    encoded = name.encode()
    digest.update(struct.pack("<Q", len(encoded)))
    digest.update(encoded)
    digest.update(struct.pack("<Q", len(data)))
    digest.update(data)
print(digest.hexdigest())
PY
)"
[[ "$database_sha256" =~ ^[0-9a-f]{64}$ ]] ||
  archphene_die "could not hash the exact removal database baseline"

archphene_adb_run shell run-as "$package" mkdir "$removal_repair"
archphene_adb_run shell run-as "$package" chmod 700 "$removal_repair"
for database_file in changelog desc files install mtree; do
  if archphene_adb_run shell run-as "$package" \
    test -f "$local_entry/$database_file"; then
    archphene_adb_run shell run-as "$package" cp -p \
      "$local_entry/$database_file" "$removal_repair/$database_file"
    archphene_adb_run shell run-as "$package" chmod 600 \
      "$removal_repair/$database_file"
  fi
done

archphene_adb_run shell am broadcast \
  -f 0x20 \
  -n "$receiver" \
  -a "$action" \
  --es token "mutation-recovery" \
  --es package "$target" \
  --ei hold-ms 30000 >/dev/null
archphene_wait_log \
  'Started package phases=true token=mutation-recovery' 15 \
  'ArchphenePackagePhaseProbe:V *:S' >/dev/null
archphene_wait_log \
  'Debug interrupted removal fixture entered mutation' 15 >/dev/null

archphene_adb_run shell run-as "$package" sh -c \
  "'umask 077; printf \"org.archphene.package-mutation.v1\\nremove\\t$target\\t$version\\t$database_sha256\\n\" > $intent.tmp && mv $intent.tmp $intent'"
[[ "$(archphene_adb_run shell run-as "$package" stat -c %a "$intent" | tr -d '\r')" == 600 ]] ||
  archphene_die "package mutation intent mode is not private"
archphene_adb_run shell run-as "$package" rm "$local_entry/desc"
archphene_adb_run shell run-as "$package" test ! -e "$local_entry/desc" ||
  archphene_die "could not establish the partial removal database"

android_pid="$(archphene_android_pid "$package")"
archphene_adb_run shell run-as "$package" kill -9 "$android_pid" >/dev/null
deadline=$((SECONDS + 15))
while archphene_android_pid "$package" >/dev/null 2>&1 && ((SECONDS < deadline)); do
  sleep 0.2
done
if archphene_android_pid "$package" >/dev/null 2>&1; then
  archphene_die "manager process survived package-mutation SIGKILL"
fi

archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 20 >/dev/null
archphene_open_manager_section Packages "package-recovery-packages-$serial"
archphene_wait_ui 'text="Package name"' "package-recovery-field-$serial" 15
archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Package name"' 'package name'
archphene_adb_run shell input keycombination 113 29 >/dev/null
archphene_adb_run shell input keyevent KEYCODE_DEL >/dev/null
archphene_adb_run shell input text "$target" >/dev/null
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_wait_ui 'text="Remove · Failed · 60%"' \
  "package-recovery-failed-$serial" 20
archphene_wait_ui 'text="Repair"' "package-recovery-action-$serial" 15
archphene_wait_ui 'Package mutation was interrupted' \
  "package-recovery-message-$serial" 15
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-interrupted.png"

archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="Repair"' 'Repair'
archphene_wait_ui 'text="Remove · Complete · 100%"' \
  "package-recovery-complete-$serial" 60
archphene_wait_ui "text=\"Repaired package transaction for $target\"" \
  "package-recovery-result-$serial" 15
archphene_adb_run exec-out screencap -p >"$output_dir/$serial-repaired.png"

if archphene_adb_run shell run-as "$package" test -e "$intent"; then
  archphene_die "successful repair retained the mutation intent"
fi
if archphene_adb_run shell run-as "$package" test -e "$removal_repair" ||
    archphene_adb_run shell run-as "$package" test -e "$removal_repair_temp" ||
    archphene_adb_run shell run-as "$package" test -e "$local_repair_temp"; then
  archphene_die "successful repair retained a removal database snapshot"
fi
if archphene_adb_run shell run-as "$package" test -e "$target_file"; then
  archphene_die "repaired removal retained the $target executable"
fi
if archphene_adb_run shell run-as "$package" test -e "$local_entry"; then
  archphene_die "repaired removal retained the $target database entry"
fi

archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="(?:DETAILS|Details)"' 'package details'
archphene_wait_ui \
  "text=\"[^\"]*/$target [^\"]+.*Dependency closure: [1-9][0-9]* packages" \
  "package-recovery-resolution-$serial" 20
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" 'text="(?:INSTALL|Install)"' "reinstall $target"
archphene_wait_ui 'text="Install · Complete · 100%"' \
  "package-recovery-reinstall-$serial" 120
archphene_wait_ui "text=\"Installed $target [^\"]+\"" \
  "package-recovery-reinstalled-$serial" 15
archphene_adb_run shell run-as "$package" test -x "$target_file" ||
  archphene_die "$target was not restored after the recovery gate"
reinstalled_entry="$(
  archphene_adb_run exec-out run-as "$package" find \
    files/arch-root/var/lib/pacman/local -maxdepth 1 -type d -name "$target-*" |
    tr -d '\r' |
    head -n1
)"
[[ -n "$reinstalled_entry" ]] ||
  archphene_die "$target database record was not restored"
reason="$(
  archphene_adb_run exec-out run-as "$package" cat "$reinstalled_entry/desc" |
    tr -d '\r' |
    awk '/^%REASON%$/{getline; print; exit}'
)"
[[ "$reason" == "$original_reason" ]] ||
  archphene_die "reinstalled $target changed its install reason"

fatal_log="$(
  archphene_adb_run logcat -d -v brief \
    -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true
)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "package mutation recovery emitted a fatal runtime error: $fatal_log"

trap - EXIT
cleanup
if [[ "$initially_running" == true ]]; then
  archphene_android_pid "$package" >/dev/null ||
    archphene_die "mutation recovery did not restore the running manager"
else
  ! archphene_android_pid "$package" >/dev/null 2>&1 ||
    archphene_die "mutation recovery left a previously stopped manager running"
fi
archphene_note "Interrupted package mutation repair passed on $serial"
archphene_note "  SIGKILL recovery restored a checksummed partial pacman record before removal"
archphene_note "  Repair removed $target, cleared private state, and explicit reinstall restored it"
archphene_note "  Full-device screenshots: $output_dir/$serial-{interrupted,repaired}.png"
