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
  if [[ "${stale_builder_pid:-}" =~ ^[1-9][0-9]*$ ]]; then
    archphene_adb_run shell run-as "$builder" kill -9 \
      "$stale_builder_pid" >/dev/null 2>&1 || true
  fi
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

stale_builder_pid="$(
  archphene_adb_run shell run-as "$builder" sh -c \
    "'sleep 900 >/dev/null 2>&1 & echo \$!'" |
    tr -d '\r[:space:]'
)"
[[ "$stale_builder_pid" =~ ^[1-9][0-9]*$ ]] ||
  archphene_die "could not create a stale same-UID Builder process"
stale_builder_uid="$(
  archphene_adb_run shell cat "/proc/$stale_builder_pid/status" |
    sed -nE 's/^Uid:[[:space:]]+([0-9]+).*/\1/p' |
    tr -d '\r'
)"
[[ "$stale_builder_uid" == "$builder_uid" ]] ||
  archphene_die "stale Builder process did not use the Builder UID"

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
reviewed_log="$(
  archphene_wait_log \
  "Reviewed AUR $package .* commit=[0-9a-f]{40}" 45 \
  'ArchpheneRuntime:I *:S'
)"
reviewed_version="$(
  sed -nE "s/.*Reviewed AUR $package ([^ ]+) commit=.*/\\1/p" \
    <<<"$reviewed_log" |
    tail -1
)"
[[ -n "$reviewed_version" ]] ||
  archphene_die "could not parse the reviewed AUR version"
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
    "AUR builder boundary ready: package=$builder uid=$builder_uid context=.*untrusted_app.*staged=[1-9][0-9]+ manifest=[0-9a-f]{64} closure=[1-9][0-9]*/[1-9][0-9]*\\+[1-9][0-9]* [0-9a-f]{64} root=[1-9][0-9]*/[1-9][0-9]* tool=[^\\r\\n]*makepkg.* recipe=[1-9][0-9]*/[1-9][0-9]*\\+[1-9][0-9]*" 120 \
    'ArchpheneRuntime:I *:S'
)"
builder_closure_count="$(
  sed -nE 's/.* closure=([1-9][0-9]*)\/.*/\1/p' <<<"$builder_log" |
    tail -1
)"
builder_closure_archive_bytes="$(
  sed -nE 's/.* closure=[1-9][0-9]*\/([1-9][0-9]*)\+.*/\1/p' <<<"$builder_log" |
    tail -1
)"
builder_closure_signature_bytes="$(
  sed -nE 's/.* closure=[1-9][0-9]*\/[1-9][0-9]*\+([1-9][0-9]*) .*/\1/p' \
    <<<"$builder_log" |
    tail -1
)"
builder_closure_sha256="$(
  sed -nE 's/.* closure=[1-9][0-9]*\/[1-9][0-9]*\+[1-9][0-9]* ([0-9a-f]{64}).*/\1/p' \
    <<<"$builder_log" |
    tail -1
)"
builder_root_entries="$(
  sed -nE 's/.* root=([1-9][0-9]*)\/[1-9][0-9]*.*/\1/p' <<<"$builder_log" |
    tail -1
)"
builder_root_bytes="$(
  sed -nE 's/.* root=[1-9][0-9]*\/([1-9][0-9]*).*/\1/p' <<<"$builder_log" |
    tail -1
)"
builder_recipe_entries="$(
  sed -nE 's/.* recipe=([1-9][0-9]*)\/[1-9][0-9]*\+[1-9][0-9]*/\1/p' \
    <<<"$builder_log" |
    tail -1
)"
builder_recipe_bytes="$(
  sed -nE 's/.* recipe=[1-9][0-9]*\/([1-9][0-9]*)\+[1-9][0-9]*/\1/p' \
    <<<"$builder_log" |
    tail -1
)"
builder_recipe_source_bytes="$(
  sed -nE 's/.* recipe=[1-9][0-9]*\/[1-9][0-9]*\+([1-9][0-9]*).*/\1/p' \
    <<<"$builder_log" |
    tail -1
)"
[[ "$builder_closure_count" =~ ^[1-9][0-9]*$ &&
   "$builder_closure_archive_bytes" =~ ^[1-9][0-9]*$ &&
   "$builder_closure_signature_bytes" =~ ^[1-9][0-9]*$ &&
   "$builder_closure_sha256" =~ ^[0-9a-f]{64}$ &&
   "$builder_root_entries" =~ ^[1-9][0-9]*$ &&
   "$builder_root_bytes" =~ ^[1-9][0-9]*$ &&
   "$builder_recipe_entries" =~ ^[1-9][0-9]*$ &&
   "$builder_recipe_bytes" =~ ^[1-9][0-9]*$ &&
   "$builder_recipe_source_bytes" == "$verified_bytes" ]] ||
  archphene_die "could not parse the independently published Builder closure/root/recipe"
stale_deadline=$((SECONDS + 10))
while ((SECONDS < stale_deadline)); do
  stale_state="$(
    archphene_adb_run shell cat "/proc/$stale_builder_pid/stat" 2>/dev/null |
      sed -nE 's/^[0-9]+ \\(.*\\) ([A-Z]).*/\\1/p' |
      tr -d '\r'
  )"
  if [[ -z "$stale_state" || "$stale_state" == Z ]]; then
    break
  fi
  sleep 0.1
done
[[ -z "$stale_state" || "$stale_state" == Z ]] ||
  archphene_die "Builder did not terminate its stale same-UID process"
archphene_wait_ui 'Verified source downloads:' aur-sources-result 30
ui="$ARCHPHENE_UI"
for pattern in \
  'Verified source downloads:' \
  'HTTPS endpoint[^:]*: https://' \
  'Installed/build disk impact: pending the isolated package build\.' \
  'Verified official build environment: [1-9][0-9]* official packages · [1-9][0-9]* MiB archives · [0-9]+ cached · [0-9]+ downloaded\.' \
  'Build closure SHA-256: [0-9a-f]{64}' \
  "Build sandbox: signed companion UID $builder_uid; no network permission or direct manager-data access; [1-9][0-9]* MiB reviewed inputs and $builder_closure_count signed build packages \\([1-9][0-9]* MiB archives\\) staged\\." \
  "Builder closure SHA-256: $builder_closure_sha256" \
  "Isolated build root: [1-9][0-9]* (?:KiB|MiB|GiB) across $builder_root_entries verified archive entries\\." \
  'Builder toolchain: [^<]*makepkg' \
  "Prepared reviewed recipe: $builder_recipe_entries entries · [1-9][0-9]* (?:B|KiB|MiB) recipe · [1-9][0-9]* MiB verified sources\\." \
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
archphene_adb_run shell run-as "$builder" test ! -e \
  files/aur-build-workspace ||
  archphene_die "AUR builder retained its legacy Kotlin-owned workspace"
builder_manifest="$(
  archphene_adb_run shell run-as "$builder" \
    cat files/aur-build-workspace-v2/reviewed-inputs/manifest |
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
    find files/aur-build-workspace-v2/reviewed-inputs -maxdepth 1 \
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

builder_closure_manifest="$(
  archphene_adb_run shell run-as "$builder" \
    cat files/aur-build-workspace-v2/package-closure/manifest |
    tr -d '\r'
)"
grep -Fqx 'ABPC0001' <<<"$builder_closure_manifest" ||
  archphene_die "AUR Builder closure manifest has the wrong version"
grep -Fqx \
  "summary	$builder_closure_count	$builder_closure_archive_bytes" \
  <<<"$builder_closure_manifest" ||
  archphene_die "AUR Builder closure manifest summary changed"
actual_builder_closure_sha256="$(
  archphene_adb_run shell run-as "$builder" sha256sum \
    files/aur-build-workspace-v2/package-closure/manifest |
    awk '{ print $1 }' |
    tr -d '\r'
)"
[[ "$actual_builder_closure_sha256" == "$builder_closure_sha256" ]] ||
  archphene_die "AUR Builder closure digest does not match the manager"
builder_closure_session="$(
  archphene_adb_run shell run-as "$builder" \
    cat files/aur-build-workspace-v2/package-closure/session |
    tr -d '\r'
)"
grep -Fqx 'ABCS0001' <<<"$builder_closure_session" &&
  grep -Fqx "package=$package" <<<"$builder_closure_session" &&
  grep -Fqx "version=$reviewed_version" <<<"$builder_closure_session" &&
  grep -Fqx "closure=$builder_closure_sha256" <<<"$builder_closure_session" ||
  archphene_die "AUR Builder closure is not bound to the reviewed package/version"
builder_archive_count="$(
  archphene_adb_run shell run-as "$builder" sh -c \
    "'find files/aur-build-workspace-v2/package-closure/archives -type f ! -name \"*.sig\" | wc -l'" |
    tr -d '\r[:space:]'
)"
builder_signature_count="$(
  archphene_adb_run shell run-as "$builder" sh -c \
    "'find files/aur-build-workspace-v2/package-closure/archives -type f -name \"*.sig\" | wc -l'" |
    tr -d '\r[:space:]'
)"
[[ "$builder_archive_count" == "$builder_closure_count" &&
   "$builder_signature_count" == "$builder_closure_count" ]] ||
  archphene_die "AUR Builder did not retain every archive/signature pair"
first_closure_line="$(
  sed -n '2p' <<<"$builder_closure_manifest"
)"
IFS=$'\t' read -r _ _ _ first_filename _ first_archive_bytes \
  first_archive_sha256 first_signature_bytes first_signature_sha256 \
  <<<"$first_closure_line"
[[ "$first_archive_sha256" =~ ^[0-9a-f]{64}$ &&
   "$first_signature_sha256" =~ ^[0-9a-f]{64}$ ]] ||
  archphene_die "AUR Builder first closure entry is malformed"
first_staged_archive="files/aur-build-workspace-v2/package-closure/archives/000-$first_filename"
first_staged_signature="$first_staged_archive.sig"
first_actual_sha256="$(
  archphene_adb_run shell run-as "$builder" sha256sum "$first_staged_archive" |
    awk '{ print $1 }' |
    tr -d '\r'
)"
first_actual_signature_sha256="$(
  archphene_adb_run shell run-as "$builder" sha256sum "$first_staged_signature" |
    awk '{ print $1 }' |
    tr -d '\r'
)"
[[ "$first_actual_sha256" == "$first_archive_sha256" &&
   "$first_actual_signature_sha256" == "$first_signature_sha256" ]] ||
  archphene_die "AUR Builder staged bytes do not match its signed closure"

builder_root=files/aur-build-workspace-v2/build-root
for tool in usr/bin/bash usr/bin/makepkg usr/bin/fakeroot
do
  archphene_adb_run shell run-as "$builder" test -f "$builder_root/$tool" &&
    archphene_adb_run shell run-as "$builder" test -x "$builder_root/$tool" ||
    archphene_die "isolated Builder root omits executable $tool"
done
archphene_adb_run shell run-as "$builder" test ! -e "$builder_root/.PKGINFO" ||
  archphene_die "isolated Builder root published package metadata at its root"
builder_root_nodes="$(
  archphene_adb_run shell run-as "$builder" sh -c \
    "'find $builder_root -xdev | wc -l'" |
    tr -d '\r[:space:]'
)"
[[ "$builder_root_nodes" =~ ^[1-9][0-9]*$ &&
   "$builder_root_nodes" -gt "$builder_closure_count" ]] ||
  archphene_die "isolated Builder root does not contain a plausible extracted tree"
builder_root_manifest="$(
  archphene_adb_run shell run-as "$builder" \
    cat files/aur-build-workspace-v2/build-root-manifest |
    tr -d '\r'
)"
grep -Fqx 'ABBR0001' <<<"$builder_root_manifest" &&
  grep -Fqx "closure=$builder_closure_sha256" <<<"$builder_root_manifest" &&
  grep -Fqx "packages=$builder_closure_count" <<<"$builder_root_manifest" &&
  grep -Fqx "entries=$builder_root_entries" <<<"$builder_root_manifest" &&
  grep -Fqx "bytes=$builder_root_bytes" <<<"$builder_root_manifest" ||
  archphene_die "isolated Builder root publication manifest changed"
archphene_adb_run shell run-as "$builder" test -L \
  "$builder_root/run/builder-runtime-v1/libarchphene_path_bridge.so" ||
  archphene_die "Builder runtime did not publish its verified bridge alias"
builder_recipe="files/aur-build-workspace-v2/build-root/home/archphene/aur-build/$package"
for recipe_file in PKGBUILD visual-studio-code-bin.sh
do
  archphene_adb_run shell run-as "$builder" test -f \
    "$builder_recipe/$recipe_file" ||
    archphene_die "prepared reviewed recipe omits $recipe_file"
done
prepared_source_filename="$(
  awk -F '\t' '$1 == "source" { print $2; exit }' <<<"$builder_manifest"
)"
[[ -n "$prepared_source_filename" ]] ||
  archphene_die "could not resolve the prepared source filename"
prepared_source="$builder_recipe/$prepared_source_filename"
prepared_source_sha256="$(
  archphene_adb_run shell run-as "$builder" sha256sum "$prepared_source" |
    awk '{ print $1 }' |
    tr -d '\r'
)"
[[ "$prepared_source_sha256" == "$expected_sha256" ]] ||
  archphene_die "prepared recipe source digest changed"
recipe_links="$(
  archphene_adb_run shell run-as "$builder" sh -c \
    "'find $builder_recipe -type l | wc -l'" |
    tr -d '\r[:space:]'
)"
[[ "$recipe_links" == 0 ]] ||
  archphene_die "prepared reviewed recipe contains an unexpected link"

archphene_wait_ui 'text="Build"[^>]*enabled="true"' aur-build-action 15
archphene_tap_ui_pattern \
  "$ARCHPHENE_UI" 'text="Build"[^>]*enabled="true"' "Build"
build_log="$(
  archphene_wait_log \
    "AUR build completed for $package $reviewed_version" 900 \
    'ArchpheneRuntime:I *:S'
)"
[[ -n "$build_log" ]] ||
  archphene_die "isolated AUR build did not report completion"
archphene_wait_ui \
  "Built $package $reviewed_version; verifying output next" \
  aur-build-complete 30
builder_packages="$(
  archphene_adb_run shell run-as "$builder" sh -c \
    "'find $builder_recipe -maxdepth 1 -type f \\( -name \"*.pkg.tar.xz\" -o -name \"*.pkg.tar.zst\" \\) -print'" |
    tr -d '\r'
)"
[[ -n "$builder_packages" ]] ||
  archphene_die "successful isolated AUR build published no package archive"
while IFS= read -r builder_package
do
  [[ -n "$builder_package" ]] || continue
  archphene_adb_run shell run-as "$builder" test ! -L "$builder_package" ||
    archphene_die "isolated AUR build published a symlink package archive"
done <<<"$builder_packages"
builder_package_count="$(awk 'NF { count++ } END { print count + 0 }' <<<"$builder_packages")"
[[ "$builder_package_count" == 1 ]] ||
  archphene_die "Code AUR fixture published an unexpected number of package archives"
builder_package="$(awk 'NF { print; exit }' <<<"$builder_packages")"
package_metadata="$(
  archphene_adb_run exec-out run-as "$builder" cat "$builder_package" |
    bsdtar -xOf - .BUILDINFO .PKGINFO
)"
grep -Fqx "pkgname = $package" <<<"$package_metadata" &&
  grep -Fqx "pkgbase = $package" <<<"$package_metadata" &&
  grep -Fqx "pkgver = $reviewed_version" <<<"$package_metadata" &&
  grep -Fqx 'arch = aarch64' <<<"$package_metadata" &&
  grep -Eq '^size = [1-9][0-9]*$' <<<"$package_metadata" ||
  archphene_die "built Code package metadata does not match the reviewed result"
actual_build_packages="$(
  sed -n 's/^installed = //p' <<<"$package_metadata" |
    sort
)"
expected_build_packages="$(
  awk -F '\t' '
    NR > 1 && $1 != "summary" {
      filename = $4
      sub(/\.pkg\.tar\.(xz|zst)$/, "", filename)
      sub(/^.*-/, "", filename)
      print $2 "-" $3 "-" filename
    }
  ' <<<"$builder_closure_manifest" |
    sort
)"
actual_build_package_count="$(
  awk 'NF { count++ } END { print count + 0 }' <<<"$actual_build_packages"
)"
[[ "$actual_build_package_count" == "$builder_closure_count" &&
   "$actual_build_packages" == "$expected_build_packages" ]] ||
  archphene_die "built Code package does not record its exact verified build closure"

after_count="$(local_package_count)"
[[ "$after_count" == "$before_count" ]] ||
  archphene_die "isolated AUR build mutated the manager pacman database"

archphene_wait_ui \
  "Built $package $reviewed_version; verifying output next" \
  aur-sources-final-render 15
resumed_activity="$(
  archphene_adb_run shell dumpsys activity activities |
    tr -d '\r' |
    grep -m1 -E 'topResumedActivity=|mResumedActivity:|Resumed:' || true
)"
[[ "$resumed_activity" == *"$manager"* ]] ||
  archphene_die "Archphene manager is not the resumed Activity before screenshot"
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
archphene_note "  Builder terminated stale same-UID process $stale_builder_pid before reuse"
archphene_note "  Builder reverified $builder_closure_count archive/signature pairs"
archphene_note "  Builder provisioned $builder_root_entries verified entries ($builder_root_bytes bytes)"
archphene_note "  Builder prepared $builder_recipe_entries reviewed recipe entries and $builder_recipe_source_bytes source bytes"
archphene_note "  Builder completed makepkg and recorded all $builder_closure_count build packages"
archphene_note "  Pacman state remained at $after_count local database entries"
archphene_note "  Full-device screenshot: $output_dir/$serial.png"
