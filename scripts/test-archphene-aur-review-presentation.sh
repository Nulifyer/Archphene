#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL [--apk PATH]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"

archphene_test_init "$serial"
package=org.archphene.app.debug
activity="$package/org.archphene.app.MainActivity"
receiver="$package/org.archphene.app.AurReviewTestReceiver"
publish_action=org.archphene.app.debug.action.PUBLISH_AUR_REVIEW
clear_action=org.archphene.app.debug.action.CLEAR_AUR_REVIEW
show_action=org.archphene.app.debug.action.SHOW_AUR_REVIEW
fixture=archphene-aur-ux-fixture
token="aur-ux-${serial,,}"
token="${token//[^a-z0-9-]/-}"
output_dir="$ARCHPHENE_ROOT/tooling/build/aur-review-presentation"
mkdir -p "$output_dir"
display_size="$(
  archphene_adb_run shell wm size |
    python3 -c '
import re, sys
matches = re.findall(r"(\d+)x(\d+)", sys.stdin.read())
if not matches:
    raise SystemExit("could not determine device display size")
print(*matches[-1])
'
)"
read -r display_width display_height <<<"$display_size"
scroll_x=$((display_width / 2))
scroll_start_y=$((display_height * 80 / 100))
scroll_end_y=$((display_height * 48 / 100))

initial_running=false
if archphene_android_pid "$package" >/dev/null 2>&1; then
  initial_running=true
fi
original_section=
before_count=

local_package_count() {
  archphene_adb_run shell run-as "$package" find \
    files/arch-root/var/lib/pacman/local \
    -mindepth 1 -maxdepth 1 -type d 2>/dev/null |
    wc -l
}

restore_section() {
  [[ -n "$original_section" ]] || return 0
  local ui
  ui="$(archphene_capture_ui "aur-review-restore-$serial" 2>/dev/null || true)"
  if [[ -n "$ui" ]] &&
    archphene_regex_contains \
      "$ui" \
      "text=\"$original_section\"[^>]*class=\"android\\.widget\\.Button\""; then
    archphene_tap_ui_pattern \
      "$ui" \
      "text=\"$original_section\"[^>]*class=\"android\\.widget\\.Button\"" \
      "$original_section" || true
  fi
}

cleanup() {
  archphene_adb_run shell am broadcast \
    -f 0x20 -n "$receiver" -a "$clear_action" \
    --es token "$token" --es package "$fixture" >/dev/null 2>&1 || true
  restore_section || true
  if [[ "$initial_running" == false ]]; then
    archphene_adb_run shell am force-stop "$package" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

if [[ -n "$apk" ]]; then
  archphene_require_file "$apk"
  archphene_adb_run install -r "$apk" >/dev/null
fi
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'Package runtime ready:.*Pacman v[0-9]' 25 >/dev/null
archphene_wait_ui_exact_text \
  "Archphene is ready" "aur-review-presentation-ready-$serial" 20
original_section="$(
  python3 -c '
import re, sys
text = sys.stdin.read()
for name in ("Packages", "Files", "Terminal"):
    if re.search(
        rf"text=\"{name}\"[^>]*class=\"android\.widget\.Button\"[^>]*selected=\"true\"",
        text,
    ):
        print(name)
        break
' <<<"$ARCHPHENE_UI"
)"
[[ -n "$original_section" ]] ||
  archphene_die "could not determine the original manager section"
before_count="$(local_package_count)"

archphene_adb_run shell am broadcast \
  -f 0x20 -n "$receiver" -a "$publish_action" \
  --es token "$token" --es package "$fixture" >/dev/null
archphene_wait_log \
  "Published AUR review=true token=$token" 15 \
  "ArchpheneAurReviewProbe:I *:S" >/dev/null
archphene_adb_run shell am start -W \
  -n "$activity" -a "$show_action" --es package "$fixture" >/dev/null

archphene_wait_ui_exact_text \
  "Verified fixture evidence · ready to install" \
  "aur-review-presentation-status-$serial" 20
archphene_wait_ui_text \
  "Community AUR package" "aur-review-presentation-summary-$serial" 15
ui="$ARCHPHENE_UI"
for section in Sources Trust "Build environment" Digests Recipe "Build logs"; do
  archphene_regex_contains \
    "$ui" \
    "text=\"$section\"[^>]*class=\"android\\.widget\\.Button\"[^>]*content-desc=\"$section, collapsed\\. Tap to expand\"" ||
    archphene_die "$section is not a collapsed accessible AUR section"
done
archphene_regex_contains \
  "$ui" 'text="Built"[^>]*class="android\.widget\.Button"[^>]*enabled="false"' ||
  archphene_die "AUR presentation allowed a second build after verification"
archphene_regex_contains \
  "$ui" 'text="Install"[^>]*class="android\.widget\.Button"[^>]*enabled="true"' ||
  archphene_die "verified AUR presentation did not expose the reviewed Install action"
[[ "$ui" != *"post_install()"* ]] ||
  archphene_die "collapsed AUR review exposed the raw install recipe"
archphene_adb_run exec-out screencap -p \
  >"$output_dir/$serial-summary.png"

open_section() {
  local section="$1" evidence="$2" slug="$3"
  local collapse="${4:-true}" ui attempt
  archphene_wait_ui \
    "text=\"$section\"[^>]*class=\"android\\.widget\\.Button\"" \
    "aur-review-$slug-button-$serial" 15
  archphene_tap_ui_pattern \
    "$ARCHPHENE_UI" \
    "text=\"$section\"[^>]*class=\"android\\.widget\\.Button\"" \
    "$section"
  archphene_wait_ui \
    "text=\"$section\"[^>]*content-desc=\"$section, expanded\\. Tap to collapse\"" \
    "aur-review-$slug-expanded-$serial" 15
  ui=
  for attempt in {0..6}; do
    ui="$(
      archphene_capture_ui \
        "aur-review-$slug-evidence-$attempt-$serial" 2>/dev/null || true
    )"
    if archphene_regex_contains "$ui" "$evidence"; then
      break
    fi
    archphene_adb_run shell input swipe \
      "$scroll_x" "$scroll_start_y" "$scroll_x" "$scroll_end_y" 300 >/dev/null
    sleep 0.5
  done
  archphene_regex_contains "$ui" "$evidence" ||
    archphene_die "timed out waiting for $section evidence: $evidence"
  archphene_regex_contains \
    "$ui" \
    "text=\"[^\"]*$evidence[^\"]*\"[^>]*class=\"android\\.widget\\.TextView\"[^>]*focusable=\"true\"" ||
    archphene_die "$section evidence is not selectable/copyable"
  archphene_adb_run exec-out screencap -p \
    >"$output_dir/$serial-$slug.png"
  [[ "$collapse" == true ]] || return 0
  archphene_tap_ui_pattern \
    "$ui" \
    "text=\"$section\"[^>]*class=\"android\\.widget\\.Button\"" \
    "$section"
}

open_section Sources \
  'Licenses.*• MIT.*File: archphene-aur-ux-fixture-1\.2\.3\.tar\.gz.*Origin: https://example\.invalid/source' \
  sources
open_section Trust \
  'Community PKGBUILD; not an official signed Arch package\..*joins the shared Archphene Linux environment.*Maintainer: Archphene test maintainer.*Unverified sources: none.*Insecure source transports: none.*Android permissions: none requested at this review stage\.' \
  trust
open_section "Build environment" \
  'Verified source downloads: 2 MiB.*Verified official build environment: 1 packages · 16 MiB archives · 1 cached · 0 downloaded.*Build sandbox: signed companion UID 12345; no network permission or direct manager-data access\..*Reviewed inputs: 2 MiB.*Signed build packages: 4 · 16 MiB archives.*Isolated build root: 32 MiB across 128 verified entries.*Verified package: archphene-aur-ux-fixture-1\.2\.3-1-any\.pkg\.tar\.zst · 8 MiB archive · 64 MiB installed' \
  build
open_section Digests \
  'AUR commit: 1{40}.*Snapshot SHA-256: 3{64}.*archphene-aur-ux-fixture-1\.2\.3\.tar\.gz SHA-256: 2{64}.*Build closure SHA-256: 5{64}.*Builder input SHA-256: 4{64}.*Builder closure SHA-256: 5{64}.*Built package SHA-256: 6{64}' \
  digests
open_section Recipe \
  'Runtime dependencies.*• glibc.*• zlib.*Build dependencies.*• rust.*• cargo.*Check dependencies.*• bats.*Valid PGP keys.*0123456789ABCDEF0123456789ABCDEF01234567.*Visible build functions.*prepare\(\).*build\(\).*check\(\).*package\(\).*Install script: archphene-aur-ux-fixture\.install.*post_install\(\).*PKGBUILD.*pkgname=archphene-aur-ux-fixture' \
  recipe
open_section "Build logs" \
  'Finished making: archphene-aur-ux-fixture 1\.2\.3-1' logs false

after_count="$(local_package_count)"
[[ "$after_count" == "$before_count" ]] ||
  archphene_die "AUR presentation fixture changed the pacman database"
fatal_log="$(
  archphene_adb_run logcat -d -v brief \
    -s AndroidRuntime:E libc:F '*:S' 2>/dev/null || true
)"
[[ "$fatal_log" != *"FATAL EXCEPTION"* && "$fatal_log" != *"Fatal signal"* ]] ||
  archphene_die "AUR presentation emitted a fatal runtime error: $fatal_log"

archphene_adb_run shell am broadcast \
  -f 0x20 -n "$receiver" -a "$clear_action" \
  --es token "$token" --es package "$fixture" >/dev/null
archphene_wait_log \
  "Cleared AUR review=true token=$token" 15 \
  "ArchpheneAurReviewProbe:I *:S" >/dev/null
restore_section
if [[ "$initial_running" == false ]]; then
  archphene_adb_run shell am force-stop "$package" >/dev/null
fi

trap - EXIT
archphene_note "Archphene compact AUR review presentation passed on $serial"
archphene_note "  Summary and six mutually compact selectable evidence sections passed"
archphene_note "  Pacman state remained at $after_count packages; no network was used"
archphene_note "  Full-device screenshots: $output_dir/$serial-{summary,sources,trust,build,digests,recipe,logs}.png"
