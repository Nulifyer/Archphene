#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
fixture="$root/tests/fixtures/package-replacement/PKGBUILD"
for command in makepkg pacman fakeroot; do
  command -v "$command" >/dev/null ||
    {
      echo "missing required system command: $command" >&2
      exit 1
    }
done

work="$(mktemp -d "${TMPDIR:-/tmp}/archphene-package-replacement.XXXXXX")"
case "$work" in
  "${TMPDIR:-/tmp}"/archphene-package-replacement.*) ;;
  *) echo "unsafe temporary path: $work" >&2; exit 1 ;;
esac
trap 'rm -rf -- "$work"' EXIT

cp "$fixture" "$work/PKGBUILD"
mkdir -p "$work/root" "$work/database" "$work/cache"
(
  cd "$work"
  makepkg --cleanbuild --clean --force --nodeps --noconfirm >/dev/null
)
printf '%s\n' \
  '[options]' \
  'Architecture = auto' \
  'SigLevel = Never' \
  'LocalFileSigLevel = Never' >"$work/pacman.conf"

old="$work/archphene-replaced-fixture-1.0-1-any.pkg.tar.zst"
new="$work/archphene-replacement-fixture-1.0-1-any.pkg.tar.zst"
fake_state="$work/fakeroot.state"
pacman_args=(
  --config "$work/pacman.conf"
  --root "$work/root"
  --cachedir "$work/cache"
)
transaction_args=(--noconfirm --noprogressbar --nodeps)

fakeroot -s "$fake_state" pacman "${pacman_args[@]}" "${transaction_args[@]}" \
  --dbpath "$work/database" -U "$old" >/dev/null

printed="$(
  fakeroot -i "$fake_state" pacman "${pacman_args[@]}" "${transaction_args[@]}" \
    --dbpath "$work/database" \
    -U --print --print-format $'%n\t%v' "$new" 2>&1
)"
[[ "$printed" == $'archphene-replacement-fixture\t1.0-1' ]] ||
  {
    echo "unexpected archive-only print plan: $printed" >&2
    exit 1
  }

set +e
rejected="$(
  fakeroot -i "$fake_state" -s "$fake_state" pacman \
    "${pacman_args[@]}" "${transaction_args[@]}" \
    --dbpath "$work/database" -U "$new" 2>&1
)"
rejected_status=$?
set -e
((rejected_status != 0)) ||
  {
    echo "ordinary noninteractive local transaction silently accepted a conflict" >&2
    exit 1
  }
[[ "$rejected" == *"unresolvable package conflicts detected"* ]] ||
  {
    echo "ordinary transaction did not report the expected conflict" >&2
    exit 1
  }
[[ "$(
  fakeroot -i "$fake_state" pacman "${pacman_args[@]}" \
    --dbpath "$work/database" -Q
)" == "archphene-replaced-fixture 1.0-1" ]] ||
  {
    echo "rejected transaction changed the live package database" >&2
    exit 1
  }

mkdir "$work/preview-database"
cp -a "$work/database/." "$work/preview-database/"
fakeroot -i "$fake_state" -s "$fake_state" pacman \
  "${pacman_args[@]}" "${transaction_args[@]}" \
  --dbpath "$work/preview-database" --dbonly --noscriptlet --ask 4 \
  -U "$new" >/dev/null
[[ "$(
  fakeroot -i "$fake_state" pacman "${pacman_args[@]}" \
    --dbpath "$work/preview-database" -Q
)" == "archphene-replacement-fixture 1.0-1" ]] ||
  {
    echo "database-only replacement simulation did not expose the exact result" >&2
    exit 1
  }
[[ "$(
  fakeroot -i "$fake_state" pacman "${pacman_args[@]}" \
    --dbpath "$work/database" -Q
)" == "archphene-replaced-fixture 1.0-1" ]] ||
  {
    echo "replacement simulation changed the live package database" >&2
    exit 1
  }

echo "Package replacement preflight contract passed."
echo "  Archive print plans omit removals; ordinary --noconfirm fails closed."
echo "  A copied-database --dbonly plan exposes the exact accepted replacement."
