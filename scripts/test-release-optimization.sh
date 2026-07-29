#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"

manager_apk="$ARCHPHENE_ROOT/android/app/build/outputs/apk/release/app-release-unsigned.apk"
builder_apk="$ARCHPHENE_ROOT/android/builder/build/outputs/apk/release/builder-release-unsigned.apk"
launcher_apk="$ARCHPHENE_ROOT/android/launcher-template/build/outputs/apk/release/launcher-template-release-unsigned.apk"

while (($#)); do
  case "$1" in
    --manager-apk) manager_apk="${2:?missing value for --manager-apk}"; shift 2 ;;
    --builder-apk) builder_apk="${2:?missing value for --builder-apk}"; shift 2 ;;
    --launcher-apk) launcher_apk="${2:?missing value for --launcher-apk}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 [--manager-apk PATH] [--builder-apk PATH] [--launcher-apk PATH]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

for command in file readelf unzip; do
  archphene_require_command "$command"
done
for apk in "$manager_apk" "$builder_apk" "$launcher_apk"; do
  archphene_require_file "$apk"
done

require_release_setting() {
  local setting="$1"
  grep -Fqx "$setting" "$ARCHPHENE_ROOT/Cargo.toml" ||
    archphene_die "Cargo release profile is missing: $setting"
}
require_release_setting 'codegen-units = 1'
require_release_setting 'lto = "thin"'
require_release_setting 'panic = "abort"'
require_release_setting 'strip = "symbols"'

require_profile_rule() {
  local file="$1" rule="$2"
  grep -Fqx "$rule" "$file" ||
    archphene_die "baseline profile is missing: $rule"
}
require_profile_rule \
  "$ARCHPHENE_ROOT/android/app/src/main/baseline-prof.txt" \
  'HSPLorg/archphene/app/MainActivity;->onCreate(Landroid/os/Bundle;)V'
require_profile_rule \
  "$ARCHPHENE_ROOT/android/app/src/main/baseline-prof.txt" \
  'HSPLorg/archphene/app/runtime/ArchpheneRuntimeService;->onCreate()V'
require_profile_rule \
  "$ARCHPHENE_ROOT/android/builder/src/main/baseline-prof.txt" \
  'HSPLorg/archphene/builder/AurBuilderService;->onBind(Landroid/content/Intent;)Landroid/os/IBinder;'
require_profile_rule \
  "$ARCHPHENE_ROOT/android/launcher-template/src/main/baseline-prof.txt" \
  'HSPLorg/archphene/launcher/LauncherActivity;->onCreate(Landroid/os/Bundle;)V'

for apk in "$manager_apk" "$builder_apk" "$launcher_apk"; do
  for profile in assets/dexopt/baseline.prof assets/dexopt/baseline.profm; do
    size="$(
      unzip -l "$apk" "$profile" |
        awk -v path="$profile" '$4 == path { print $1 }'
    )"
    [[ "$size" =~ ^[1-9][0-9]*$ ]] ||
      archphene_die "$(basename "$apk") has no compiled $profile"
  done
done

audit_directory="$(mktemp -d /tmp/archphene-release-audit.XXXXXX)"
cleanup() {
  [[ "$audit_directory" == /tmp/archphene-release-audit.* ]] ||
    archphene_die "refusing to remove an unexpected release-audit directory"
  rm -r -- "$audit_directory"
}
trap cleanup EXIT

inspect_library() {
  local apk="$1" entry="$2"
  unzip -q "$apk" "$entry" -d "$audit_directory"
  local library="$audit_directory/$entry"
  file "$library" | grep -Fq stripped ||
    archphene_die "$entry is not stripped"
  if readelf -SW "$library" |
      awk '$2 ~ /^\\.(debug|symtab|strtab)/ { found = 1 } END { exit !found }'; then
    archphene_die "$entry retains symbol or debug sections"
  fi
}

for abi in x86_64 arm64-v8a; do
  inspect_library "$manager_apk" "lib/$abi/libarchphene_android.so"
  inspect_library "$manager_apk" "lib/$abi/libarchphene_compositor.so"
  inspect_library "$builder_apk" "lib/$abi/libarchphene_builder.so"
done

archphene_note \
  "Release optimization passed: R8-built manager/Builder/launcher APKs carry compiled baseline profiles and stripped dual-ABI Rust libraries; Cargo release uses one codegen unit, ThinLTO, panic-abort, and symbol stripping."
