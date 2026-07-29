#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/android-test.sh"

serial=
apk=
package_name=angle-grinder
dependency=jemalloc
while (($#)); do
  case "$1" in
    --serial) serial="${2:?missing value for --serial}"; shift 2 ;;
    --apk) apk="${2:?missing value for --apk}"; shift 2 ;;
    --package) package_name="${2:?missing value for --package}"; shift 2 ;;
    --dependency) dependency="${2:?missing value for --dependency}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 --serial SERIAL --apk PATH [--package NAME --dependency NAME]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || archphene_die "--serial is required"
[[ -n "$apk" ]] || archphene_die "--apk is required"
for name in "$package_name" "$dependency"; do
  [[ "$name" =~ ^[a-zA-Z0-9@._+-]{1,128}$ ]] ||
    archphene_die "invalid package name: $name"
done

archphene_test_init "$serial"
archphene_require_file "$apk"
manager=org.archphene.app.debug
root=files/arch-root
log="$root/var/log/pacman.log"
started_epoch="$(archphene_adb_run shell date +%s | tr -d '\r')"
[[ "$started_epoch" =~ ^[0-9]{10}$ ]] ||
  archphene_die "device did not return a valid epoch"
log_lines="$(
  archphene_adb_run exec-out run-as "$manager" sh -c \
    "if test -f '$log'; then wc -l < '$log'; else echo 0; fi" |
    tr -d '\r '
)"
[[ "$log_lines" =~ ^[0-9]+$ ]] ||
  archphene_die "could not measure the Pacman log"

"$ARCHPHENE_ROOT/scripts/test-archphene-package-orphan-cleanup.sh" \
  --serial "$serial" \
  --apk "$apk" \
  --package "$package_name" \
  --dependency "$dependency" \
  --decision cleanup

new_log="$(
  archphene_adb_run exec-out run-as "$manager" tail -n "+$((log_lines + 1))" "$log" |
    tr -d '\r'
)"
if grep -Eq '\[ALPM\] running .*\.hook|\[ALPM-SCRIPTLET\]' <<<"$new_log"; then
  archphene_die "Pacman executed an unbounded hook or scriptlet"
fi
grep -q '\[ALPM\] transaction completed' <<<"$new_log" ||
  archphene_die "the lifecycle gate did not complete a Pacman transaction"

override="$root/run/package-hook-overrides-v1"
mode="$(
  archphene_adb_run exec-out run-as "$manager" stat -c %a "$override" |
    tr -d '\r'
)"
[[ "$mode" == 700 ]] || archphene_die "hook override directory mode is $mode"
hook_names="$(
  archphene_adb_run exec-out run-as "$manager" sh -c \
    "for directory in '$root/usr/share/libalpm/hooks' '$root/etc/pacman.d/hooks'; do
       if test -d \"\$directory\"; then
         find \"\$directory\" -maxdepth 1 -name '*.hook' -print
       fi
     done" |
    sed 's|.*/||' | LC_ALL=C sort -u
)"
[[ -n "$hook_names" ]] || archphene_die "fixture has no package hooks to isolate"
while IFS= read -r hook; do
  target="$(
    archphene_adb_run exec-out run-as "$manager" readlink "$override/$hook" |
      tr -d '\r'
  )"
  [[ "$target" == /dev/null ]] ||
    archphene_die "hook override $hook does not target /dev/null"
done <<<"$hook_names"

assert_fresh_cache() {
  local command="$1"
  local prerequisite="$2"
  local cache="$3"
  local source_pattern="${4:-}"
  if ! archphene_adb_run shell run-as "$manager" test -e "$root/usr/bin/$command" ||
      ! archphene_adb_run shell run-as "$manager" test -d "$root/$prerequisite"; then
    return
  fi
  if [[ -n "$source_pattern" ]] &&
      ! archphene_adb_run exec-out run-as "$manager" find \
        "$root/$prerequisite" -type f -name "$source_pattern" -print -quit |
        grep -q .; then
    return
  fi
  archphene_adb_run shell run-as "$manager" test -f "$root/$cache" ||
    archphene_die "$command did not publish $cache"
  local modified
  modified="$(
    archphene_adb_run exec-out run-as "$manager" stat -c %Y "$root/$cache" |
      tr -d '\r'
  )"
  [[ "$modified" =~ ^[0-9]+$ && "$modified" -ge "$started_epoch" ]] ||
    archphene_die "$command did not refresh $cache during the transaction"
}

assert_fresh_cache gio-querymodules \
  usr/lib/gio/modules usr/lib/gio/modules/giomodule.cache '*.so'
assert_fresh_cache glib-compile-schemas \
  usr/share/glib-2.0/schemas usr/share/glib-2.0/schemas/gschemas.compiled '*.xml'
assert_fresh_cache update-desktop-database \
  usr/share/applications usr/share/applications/mimeinfo.cache '*.desktop'
if archphene_adb_run shell run-as "$manager" \
    test -d "$root/usr/share/mime/packages"; then
  assert_fresh_cache update-mime-database \
    usr/share/mime usr/share/mime/mime.cache '*.xml'
fi
assert_fresh_cache fc-cache etc/fonts var/cache/fontconfig/CACHEDIR.TAG
assert_fresh_cache gtk-query-immodules-3.0 \
  usr/lib/gtk-3.0 usr/lib/gtk-3.0/3.0.0/immodules.cache 'im-*.so'

archphene_note "Archphene bounded package-hook lifecycle passed on $serial"
archphene_note "  Pacman hooks isolated: $(wc -l <<<"$hook_names" | tr -d ' ')"
archphene_note "  Applicable desktop caches were regenerated after $started_epoch"
