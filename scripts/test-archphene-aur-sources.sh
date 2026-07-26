#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
builder_apk=
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --builder-apk) builder_apk="${2:?missing value for --builder-apk}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL --apk PATH --builder-apk PATH"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"
[[ -n "$apk" ]] || archphene_die "--apk is required"
[[ -n "$builder_apk" ]] || archphene_die "--builder-apk is required"

archphene_test_init "$serial"
archphene_require_file "$apk"
archphene_require_file "$builder_apk"
manager=org.archphene.app.debug
builder=org.archphene.builder.debug
package=visual-studio-code-bin
output_dir="$ARCHPHENE_ROOT/tooling/build/aur-sources"
mkdir -p "$output_dir"

cleanup() {
  archphene_adb_run shell am force-stop "$manager" >/dev/null 2>&1 || true
}
trap cleanup EXIT

local_package_count() {
  archphene_adb_run shell run-as "$manager" \
    ls files/arch-root/var/lib/pacman/local |
    tr -d '\r' |
    awk 'NF { count++ } END { print count + 0 }'
}

archphene_adb_run install -r "$apk" >/dev/null
archphene_adb_run install -r "$builder_apk" >/dev/null
package_uids="$(
  archphene_adb_run shell cmd package list packages -U |
    tr -d '\r'
)"
manager_uid="$(
  sed -nE "s/^package:$manager uid:([0-9]+)$/\\1/p" <<<"$package_uids"
)"
builder_uid="$(
  sed -nE "s/^package:$builder uid:([0-9]+)$/\\1/p" <<<"$package_uids"
)"
[[ "$manager_uid" =~ ^[0-9]+$ && "$builder_uid" =~ ^[0-9]+$ ]] ||
  archphene_die "could not resolve manager and builder UIDs"
[[ "$manager_uid" != "$builder_uid" ]] ||
  archphene_die "AUR builder unexpectedly shares the manager UID"

manager_dump="$(archphene_adb_run shell dumpsys package "$manager")"
builder_dump="$(archphene_adb_run shell dumpsys package "$builder")"
manager_signature="$(
  sed -nE 's/.*signatures:\[([^]]+)\].*/\1/p' <<<"$manager_dump" |
    head -1
)"
builder_signature="$(
  sed -nE 's/.*signatures:\[([^]]+)\].*/\1/p' <<<"$builder_dump" |
    head -1
)"
[[ -n "$manager_signature" && "$manager_signature" == "$builder_signature" ]] ||
  archphene_die "AUR builder signer does not match the manager"
[[ "$builder_dump" != *"android.permission.INTERNET"* ]] ||
  archphene_die "AUR builder unexpectedly requests Android network permission"
builder_activity="$(
  archphene_adb_run shell cmd package resolve-activity --brief "$builder" \
    2>&1 || true
)"
[[ "$builder_activity" == *"No activity found"* ]] ||
  archphene_die "AUR builder unexpectedly publishes an Android launcher activity"

archphene_adb_run shell am force-stop "$manager" >/dev/null
archphene_adb_run logcat -c
archphene_adb_run shell monkey -p "$manager" \
  -c android.intent.category.LAUNCHER 1 >/dev/null
archphene_wait_ui 'text="Archphene is ready"' aur-sources-ready 30
before_count="$(local_package_count)"
[[ "$before_count" =~ ^[1-9][0-9]*$ ]] ||
  archphene_die "could not read the installed-package count"

ui="$(archphene_capture_ui aur-sources-input)"
archphene_tap_ui_pattern \
  "$ui" 'class="android\.widget\.EditText"' "package input"
archphene_adb_run shell input text "$package"
archphene_adb_run shell input keyevent KEYCODE_BACK >/dev/null
archphene_wait_ui 'text="AUR"[^>]*enabled="true"' aur-sources-review-action 10
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" 'text="AUR"[^>]*enabled="true"' "AUR"
archphene_wait_log \
  "Reviewed AUR $package .* commit=[0-9a-f]{40}" 45 \
  'ArchpheneRuntime:I *:S' >/dev/null
archphene_wait_ui 'text="Verify"[^>]*enabled="true"' aur-sources-verify-action 20
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" 'text="Verify"[^>]*enabled="true"' "Verify"

verified_log="$(
  archphene_wait_log \
    "Verified 1 AUR source\\(s\\) for $package: [1-9][0-9]+ bytes" 900 \
    'ArchpheneRuntime:I *:S'
)"
verified_bytes="$(
  sed -nE \
    "s/.*Verified 1 AUR source\\(s\\) for $package: ([1-9][0-9]+) bytes.*/\\1/p" \
    <<<"$verified_log" |
    tail -1
)"
[[ "$verified_bytes" =~ ^[1-9][0-9]+$ ]] ||
  archphene_die "could not parse the verified source size"
builder_log="$(
  archphene_wait_log \
    "AUR builder boundary ready: package=$builder uid=$builder_uid context=.*untrusted_app.*staged=[1-9][0-9]+ manifest=[0-9a-f]{64}" 60 \
    'ArchpheneRuntime:I *:S'
)"
archphene_wait_ui 'Verified source downloads:' aur-sources-result 30
ui="$ARCHPHENE_UI"
for pattern in \
  'Verified source downloads:' \
  'HTTPS endpoint[^:]*: https://' \
  'Installed/build disk impact: pending the isolated package build\.' \
  'Verified official build environment: [1-9][0-9]* official packages · [1-9][0-9]* MiB archives · [0-9]+ cached · [0-9]+ downloaded\.' \
  'Build closure SHA-256: [0-9a-f]{64}' \
  "Build sandbox: signed companion UID $builder_uid; no network permission or direct manager-data access; [1-9][0-9]* MiB reviewed inputs staged\\." \
  'code[^<]*\.deb' \
  'direct HTTPS download' \
  'SHA-256: [0-9a-f]{64}'
do
  archphene_regex_contains "$ui" "$pattern" ||
    archphene_die "verified AUR review omits required UI evidence: $pattern"
done
archphene_regex_contains \
  "$ui" 'text="Install"[^>]*enabled="false"' ||
  archphene_die "source verification unexpectedly enabled official install"

cache_listing="$(
  archphene_adb_run shell run-as "$manager" \
    ls -l files/arch-root/var/cache/archphene/aur-sources |
    tr -d '\r'
)"
grep -Eq '[0-9a-f]{64}-code[^ ]*\.deb$' <<<"$cache_listing" ||
  archphene_die "verified AUR source is absent from the bounded private cache"
[[ "$cache_listing" != *".part"* ]] ||
  archphene_die "AUR source verification left a partial file"
cache_filename="$(
  grep -Eo '[0-9a-f]{64}-code[^[:space:]]*\.deb' <<<"$cache_listing" |
    tail -1
)"
[[ "$cache_filename" =~ ^([0-9a-f]{64})- ]] ||
  archphene_die "verified AUR cache filename omits its expected digest"
expected_sha256="${BASH_REMATCH[1]}"
actual_sha256="$(
  archphene_adb_run shell run-as "$manager" sha256sum \
    "files/arch-root/var/cache/archphene/aur-sources/$cache_filename" |
    awk '{ print $1 }' |
    tr -d '\r'
)"
[[ "$actual_sha256" == "$expected_sha256" ]] ||
  archphene_die "independent device SHA-256 does not match the reviewed digest"
builder_marker="$(
  archphene_adb_run shell run-as "$builder" \
    cat files/aur-build-workspace/builder-owned |
    tr -d '\r'
)"
[[ "$builder_marker" == "builder:$builder_uid" ]] ||
  archphene_die "AUR builder did not retain its private workspace marker"
builder_manifest="$(
  archphene_adb_run shell run-as "$builder" \
    cat files/aur-build-workspace/reviewed-inputs/manifest |
    tr -d '\r'
)"
grep -Fqx 'ABIN0001' <<<"$builder_manifest" ||
  archphene_die "AUR builder input manifest has the wrong version"
grep -Fqx "package=$package" <<<"$builder_manifest" ||
  archphene_die "AUR builder input manifest omits the exact package"
grep -Eq $'^snapshot\tvisual-studio-code-bin\\.tar\\.gz\t[1-9][0-9]*\t[0-9a-f]{64}$' \
  <<<"$builder_manifest" ||
  archphene_die "AUR builder input manifest omits the reviewed snapshot"
grep -Eq $'^source\tcode[^\t]*\\.deb\t[1-9][0-9]*\t[0-9a-f]{64}$' \
  <<<"$builder_manifest" ||
  archphene_die "AUR builder input manifest omits the verified remote source"
builder_source="$(
  archphene_adb_run shell run-as "$builder" \
    find files/aur-build-workspace/reviewed-inputs -maxdepth 1 \
      -type f -name "source-$expected_sha256-*.deb" |
    tr -d '\r' |
    tail -1
)"
[[ -n "$builder_source" ]] ||
  archphene_die "AUR builder did not stage the exact verified source"
builder_sha256="$(
  archphene_adb_run shell run-as "$builder" sha256sum "$builder_source" |
    awk '{ print $1 }' |
    tr -d '\r'
)"
[[ "$builder_sha256" == "$expected_sha256" ]] ||
  archphene_die "AUR builder staged source digest does not match the manager"

after_count="$(local_package_count)"
[[ "$after_count" == "$before_count" ]] ||
  archphene_die "AUR source verification mutated the pacman database"

archphene_wait_ui 'Verified source downloads:' aur-sources-final-render 15
resumed_activity="$(
  archphene_adb_run shell dumpsys activity activities |
    tr -d '\r' |
    grep -m1 -E 'topResumedActivity=|mResumedActivity:|Resumed:' || true
)"
[[ "$resumed_activity" == *"$manager"* ]] ||
  archphene_die "Archphene manager is not the resumed Activity before screenshot"
read -r screen_width screen_height < <(
  archphene_adb_run shell wm size |
    sed -n 's/.*: \([0-9][0-9]*\)x\([0-9][0-9]*\).*/\1 \2/p' |
    tail -n1
)
archphene_adb_run shell input swipe \
  "$((screen_width / 2))" "$((screen_height * 3 / 4))" \
  "$((screen_width / 2))" "$((screen_height * 2 / 3))" 600
archphene_wait_ui \
  'Verified official build environment:' aur-sources-plan-render 15
sleep 1
archphene_adb_run exec-out screencap -p >"$output_dir/$serial.png"

fatal_log="$(archphene_adb_run logcat -d -v brief \
  -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true)"
[[ "$fatal_log" != *"$manager"* && "$fatal_log" != *"$builder"* ]] ||
  [[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "AUR source verification emitted a fatal Android error: $fatal_log"

archphene_note "Archphene AUR source verification passed on $serial"
archphene_note "  Rust verified $verified_bytes source bytes"
archphene_note "  Signed builder UID $builder_uid is separate from manager UID $manager_uid"
archphene_note "  Pacman state remained at $after_count local database entries"
archphene_note "  Full-device screenshot: $output_dir/$serial.png"
