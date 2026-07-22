#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"
android_sdk=
output_directory=
while (($#)); do
  case "$1" in
    --android-sdk) android_sdk="${2:?}"; shift 2;; --output-directory) output_directory="${2:?}"; shift 2;;
    -h|--help) echo "usage: $0 [--android-sdk PATH] [--output-directory PATH]"; exit 0;; *) archphene_die "unknown argument: $1";;
  esac
done
sdk="${android_sdk:-$(archphene_android_sdk)}"
aapt2="$(archphene_android_tool "$sdk" build-tools/36.0.0/aapt2)"
platform="$sdk/platforms/android-36/android.jar"
archphene_require_file "$platform"
archphene_require_command python3
app="$ARCHPHENE_ROOT/prototypes/kcalc-android-app"
out="${output_directory:-$ARCHPHENE_ROOT/tooling/build/wrapper-templates/qt}"
[[ "$out" == /* ]] || out="$ARCHPHENE_ROOT/$out"
work="$out/manifest-variant-work"
rm -rf -- "$work"
mkdir -p "$out" "$work/compiled"
placeholder=org.archphene.linux.p00000000000000000000000000000000
placeholder_authority="$placeholder.documents"
base_manifest="$work/base-manifest.xml"
sed \
  -e "s/package=\"org.archphene.linux.kcalc\"/package=\"$placeholder\"/" \
  -e "s/org.archphene.linux.kcalc.documents/$placeholder_authority/g" \
  -e 's#@drawable/kcalc_icon#@drawable/linux_app_icon_png#g' \
  "$app/AndroidManifest.xml" > "$base_manifest"
grep -F "$placeholder_authority" "$base_manifest" >/dev/null || archphene_die "wrapper authority placeholder was not applied"
"$aapt2" compile --dir "$app/res" -o "$work/compiled/res.zip"
for profile in generic document; do
  for permission_profile in none audio-input camera audio-input-camera; do
    permission_args=()
    [[ "$permission_profile" == *audio-input* ]] && permission_args+=(--permission audio-input)
    [[ "$permission_profile" == *camera* ]] && permission_args+=(--permission camera)
    rendered="$work/$profile-$permission_profile.xml"
    python3 "$ARCHPHENE_ROOT/scripts/render-wrapper-manifest.py" --profile "$profile" \
      "${permission_args[@]}" "$base_manifest" "$rendered"
    apk="$work/$profile-$permission_profile.apk"
    "$aapt2" link -o "$apk" -I "$platform" --manifest "$rendered" "$work/compiled/res.zip"
    python3 - "$apk" "$out/qt-$profile-manifest-$permission_profile.bin" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as archive:
    data = archive.read("AndroidManifest.xml")
if not data:
    raise SystemExit("compiled wrapper manifest is empty")
open(sys.argv[2], "wb").write(data)
PY
    dump="$("$aapt2" dump xmltree "$apk" --file AndroidManifest.xml)"
    has_audio=false; has_camera=false; has_documents=false
    [[ "$dump" == *android.permission.RECORD_AUDIO* ]] && has_audio=true
    [[ "$dump" == *android.permission.CAMERA* ]] && has_camera=true
    [[ "$dump" == *application/x-archphene-mime-00* ]] && has_documents=true
    expected_audio=false; expected_camera=false; expected_documents=false
    [[ "$permission_profile" == *audio-input* ]] && expected_audio=true
    [[ "$permission_profile" == *camera* ]] && expected_camera=true
    [[ "$profile" == document ]] && expected_documents=true
    [[ "$has_audio" == "$expected_audio" && "$has_camera" == "$expected_camera" ]] || \
      archphene_die "dangerous permission mismatch in $profile/$permission_profile manifest"
    [[ "$has_documents" == "$expected_documents" ]] || archphene_die "document intent mismatch in $profile/$permission_profile manifest"
  done
done
archphene_note "Wrapper manifest variants: $out"

