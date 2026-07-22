#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"
version=26.04.0-1; version_code=26040001; output_path=tooling/build/kcalc-version-fixtures/kcalc-26.04.0-1.apk
while (($#)); do case "$1" in --version) version="${2:?}"; shift 2;; --version-code) version_code="${2:?}"; shift 2;; --output-path) output_path="${2:?}"; shift 2;; -h|--help) echo "usage: $0 [options]"; exit 0;; *) archphene_die "unknown argument: $1";; esac; done
package_name="kcalc-$version-x86_64.pkg.tar.zst"; package="$ARCHPHENE_ROOT/tooling/downloads/arch-curated-kcalc-x86_64/packages/$package_name"; signature="$package.sig"
archphene_require_file "$package"; archphene_require_file "$signature"; archphene_require_command podman; archphene_require_command jq
work="$ARCHPHENE_ROOT/tooling/build/kcalc-version-fixtures/$version"; mkdir -p "$work"
extracted="$work/libarchphene_kcalc.so"; descriptor="$work/archphene-app.json"; current="$ARCHPHENE_ROOT/prototypes/kcalc-android-app/archphene-app.json"; payload="$ARCHPHENE_ROOT/prototypes/kcalc-android-app/lib/x86_64/libarchphene_kcalc.so"; backup="$work/current-libarchphene_kcalc.so"
package_relative="tooling/downloads/arch-curated-kcalc-x86_64/packages/$package_name"; extracted_relative="tooling/build/kcalc-version-fixtures/$version/libarchphene_kcalc.so"
podman run --rm -v "$ARCHPHENE_ROOT:/workspace" -w /workspace localhost/archphene-qt-probe-builder:6.11 bash -lc "pacman-key --init >/dev/null && pacman-key --populate archlinux >/dev/null && pacman-key --verify '$package_relative.sig' '$package_relative' && bsdtar -xOf '$package_relative' usr/bin/kcalc > '$extracted_relative'"
hash="$(archphene_sha256_file "$extracted")"
jq --arg version "$version" --argjson code "$version_code" --arg package "$package_name" --arg hash "$hash" '.android.versionName=$version | .android.versionCode=$code | .source.packageFilename=$package | .source.signatureUrl=("https://archive.archlinux.org/packages/k/kcalc/"+$package+".sig") | .payload.sha256=$hash' "$current" >"$descriptor"
cp "$payload" "$backup"
restore() { cp "$backup" "$payload"; }
trap restore EXIT
cp "$extracted" "$payload"
"$ARCHPHENE_SCRIPTS_DIR/build-install-kcalc-app.sh" --skip-install --descriptor-path "$descriptor"
resolved="$ARCHPHENE_ROOT/$output_path"; mkdir -p "$(dirname "$resolved")"; cp "$ARCHPHENE_ROOT/prototypes/kcalc-android-app/out/archpheneos-kcalc.apk" "$resolved"
archphene_note "Built signed KCalc $version wrapper fixture: $resolved"
