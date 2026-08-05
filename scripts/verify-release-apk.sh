#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 4 || $# -gt 5 ]]; then
  echo "usage: $0 MANAGER_APK BUILDER_APK ABI VERSION [--allow-unsigned]" >&2
  exit 2
fi

manager_apk="$1"
builder_apk="$2"
abi="$3"
version="$4"
allow_unsigned=false
if [[ ${5:-} == --allow-unsigned ]]; then
  allow_unsigned=true
elif [[ -n ${5:-} ]]; then
  echo "unknown release verification option: $5" >&2
  exit 2
fi
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
build_tools_version="${ANDROID_BUILD_TOOLS_VERSION:-36.0.0}"
bt="$sdk/build-tools/$build_tools_version"

[[ -f "$manager_apk" ]] || { echo "manager APK is missing" >&2; exit 1; }
[[ -f "$builder_apk" ]] || { echo "Builder APK is missing" >&2; exit 1; }
case "$abi" in
  x86_64|arm64-v8a) ;;
  *) echo "release ABI must be x86_64 or arm64-v8a" >&2; exit 2 ;;
esac
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-+][0-9A-Za-z.-]+)?$ ]] || {
  echo "release version is invalid" >&2; exit 2;
}
sdk_tool() {
  local name="$1"
  local candidate
  for candidate in "$bt/$name"; do
    if [[ -f "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  echo "$bt/$name is missing" >&2
  return 1
}
aapt2="$(sdk_tool aapt2)"
apksigner="$(sdk_tool apksigner)"
zipalign="$(sdk_tool zipalign)"
command -v unzip >/dev/null || { echo "unzip is required" >&2; exit 1; }
command -v cmp >/dev/null || { echo "cmp is required" >&2; exit 1; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
entries="$work/entries"
unzip -Z1 "$manager_apk" > "$entries"
root="$(cd "$(dirname "$0")/.." && pwd)"
verify_asset() {
  local apk="$1" entry="$2" source="$3" label="$4"
  local extracted="$work/release-license"
  unzip -p "$apk" "$entry" > "$extracted"
  [[ -s "$extracted" ]] && cmp -s "$extracted" "$source" || {
    echo "$label is missing or differs from its reviewed source" >&2; exit 1;
  }
}

manager_badging="$("$aapt2" dump badging "$manager_apk")"
grep -F "package: name='org.archpheneos.manager'" <<<"$manager_badging" >/dev/null || {
  echo "manager package name is invalid" >&2; exit 1;
}
grep -F "versionName='$version'" <<<"$manager_badging" >/dev/null || {
  echo "manager versionName does not equal $version" >&2; exit 1;
}
manager_version_code="$(sed -n "s/^package: .* versionCode='\([0-9][0-9]*\)'.*/\1/p" <<<"$manager_badging")"
[[ "$manager_version_code" =~ ^[1-9][0-9]*$ ]] || {
  echo "manager versionCode is missing or invalid" >&2; exit 1;
}
[[ "$(grep '^native-code:' <<<"$manager_badging")" == "native-code: '$abi'" ]] || {
  echo "manager native ABI set does not equal $abi" >&2; exit 1;
}
manager_manifest="$("$aapt2" dump xmltree "$manager_apk" --file AndroidManifest.xml)"
if grep -Fq 'application-debuggable' <<<"$manager_badging"; then
  echo "manager APK is debuggable" >&2; exit 1
fi
grep -F 'android:pageSizeCompat' <<<"$manager_manifest" | grep -F '=32' >/dev/null || {
  echo "manager does not enable Android page-size compatibility mode" >&2; exit 1;
}
grep -F 'android:extractNativeLibs' <<<"$manager_manifest" | grep -F '=true' >/dev/null || {
  echo "manager does not enable native-library extraction" >&2; exit 1;
}
architecture="$abi"
[[ "$architecture" == arm64-v8a ]] && architecture=aarch64
manager_catalog="assets/package-runtime-$architecture.tsv"
grep -Fx "$manager_catalog" "$entries" >/dev/null || {
  echo "manager native soname catalog is missing" >&2; exit 1;
}
catalog_file="$work/manager-native.tsv"
unzip -p "$manager_apk" "$manager_catalog" > "$catalog_file"
declare -A catalog_logical=() packaged_sizes=()
catalog_count=0
has_libc=false
has_libalpm=false
has_loader=false
has_pacman=false
has_mesa=false
while IFS=$'\t' read -r role logical packaged expected_size extra; do
  [[ -n "$role" && "$role" != \#* ]] || continue
  [[ -z "$extra" && "$role" =~ ^(tool|keyring|library|loader|ownertrust)$ \
      && "$logical" =~ ^[A-Za-z0-9@._+-]{1,128}$ \
      && "$packaged" =~ ^lib[A-Za-z0-9_]+\.so$ \
      && "$expected_size" =~ ^[1-9][0-9]*$ \
      && -z "${catalog_logical[$logical]:-}" ]] || {
    echo "manager native soname catalog row is invalid" >&2; exit 1;
  }
  catalog_logical[$logical]=1
  if [[ -n "${packaged_sizes[$packaged]:-}" ]]; then
    [[ "${packaged_sizes[$packaged]}" == "$expected_size" ]] || {
      echo "manager native alias sizes disagree: $packaged" >&2; exit 1;
    }
  else
    packaged_sizes[$packaged]="$expected_size"
    payload="$work/$packaged"
    unzip -p "$manager_apk" "lib/$abi/$packaged" > "$payload"
    [[ "$(wc -c < "$payload")" == "$expected_size" ]] || {
      echo "manager native soname payload failed verification: $packaged" >&2; exit 1;
    }
  fi
  if [[ "$logical" == libc.so.6 ]]; then has_libc=true; fi
  if [[ "$logical" == libalpm.so.16 ]]; then has_libalpm=true; fi
  if [[ "$logical" == @loader ]]; then has_loader=true; fi
  if [[ "$logical" == @pacman ]]; then has_pacman=true; fi
  if [[ "$logical" == libgallium-26.1.5.so ]]; then has_mesa=true; fi
  catalog_count=$((catalog_count + 1))
done < "$catalog_file"
[[ "$catalog_count" -le 256 && "$has_libc" == true && "$has_libalpm" == true \
    && "$has_loader" == true && "$has_pacman" == true && "$has_mesa" == true ]] || {
  echo "manager native soname catalog lacks required entries" >&2; exit 1;
}
for required in \
  libarchphene_android.so libarchphene_compositor.so \
  libarchphene_dbus_daemon.so libarchphene_portal_service.so \
  libarchphene_virgl_server.so libarchphene_pulseaudio.so \
  libarchphene_pipewire_camera.so; do
  grep -Fx "lib/$abi/$required" "$entries" >/dev/null || {
    echo "manager APK lacks required native component: $required" >&2; exit 1;
  }
done
invalid_native="$(grep "^lib/$abi/" "$entries" \
  | grep -Ev "^lib/$abi/lib[A-Za-z0-9_.+-]+\\.so$" || true)"
[[ -z "$invalid_native" ]] || {
  echo "manager APK contains non-extractable native library names:" >&2
  printf '%s\n' "$invalid_native" >&2
  exit 1
}
"$zipalign" -c -P 16 -v 4 "$manager_apk" >/dev/null
verify_asset "$manager_apk" assets/licenses/Archphene-MIT.txt \
  "$root/LICENSE" "manager MIT license"
verify_asset "$manager_apk" assets/licenses/Apache-2.0.txt \
  "$root/third_party/termux-terminal/LICENSE-APACHE-2.0.txt" \
  "manager Apache license"
verify_asset "$manager_apk" assets/licenses/AndroidApkSig-NOTICE.txt \
  "$root/third_party/android-apksig/NOTICE.txt" "manager apksig notice"
verify_asset "$manager_apk" assets/licenses/JetBrainsMonoNerdFont-OFL.txt \
  "$root/third_party/jetbrains-mono-nerd-font/OFL.txt" "manager font license"

builder_badging="$("$aapt2" dump badging "$builder_apk")"
grep -F "package: name='org.archphene.builder'" <<<"$builder_badging" >/dev/null || {
  echo "Builder package name is invalid" >&2; exit 1;
}
grep -F "versionName='$version'" <<<"$builder_badging" >/dev/null || {
  echo "Builder versionName does not equal $version" >&2; exit 1;
}
builder_version_code="$(sed -n "s/^package: .* versionCode='\([0-9][0-9]*\)'.*/\1/p" <<<"$builder_badging")"
[[ "$builder_version_code" == "$manager_version_code" ]] || {
  echo "manager and Builder versionCode values differ" >&2; exit 1;
}
[[ "$(grep '^native-code:' <<<"$builder_badging")" == "native-code: '$abi'" ]] || {
  echo "Builder native ABI set does not equal $abi" >&2; exit 1;
}
if grep -Eq "^(application-debuggable|launchable-activity):" <<<"$builder_badging"; then
  echo "Builder APK is debuggable or exposes a launcher Activity" >&2; exit 1
fi
if grep -Fq "uses-permission: name='android.permission.INTERNET'" <<<"$builder_badging"; then
  echo "Builder APK requests network access" >&2; exit 1
fi
builder_manifest="$("$aapt2" dump xmltree "$builder_apk" --file AndroidManifest.xml)"
grep -F 'android:pageSizeCompat' <<<"$builder_manifest" | grep -F '=32' >/dev/null || {
  echo "Builder does not enable Android page-size compatibility mode" >&2; exit 1;
}
grep -F 'org.archphene.permission.BIND_BUILDER' <<<"$builder_manifest" >/dev/null || {
  echo "Builder does not require the manager signature permission" >&2; exit 1;
}
"$zipalign" -c -P 16 -v 4 "$builder_apk" >/dev/null
verify_asset "$builder_apk" assets/licenses/Archphene-MIT.txt \
  "$root/LICENSE" "Builder MIT license"

launcher_apk="$work/launcher-template.apk"
unzip -p "$manager_apk" assets/launcher/launcher-template.apk > "$launcher_apk"
launcher_badging="$("$aapt2" dump badging "$launcher_apk")"
grep -F "package: name='org.archphene.linux.p00000000000000000000000000000000'" \
  <<<"$launcher_badging" >/dev/null || {
  echo "generated app-shell template package is invalid" >&2; exit 1;
}
if grep -Fq 'application-debuggable' <<<"$launcher_badging"; then
  echo "generated app-shell template is debuggable" >&2; exit 1
fi
verify_asset "$launcher_apk" assets/licenses/Archphene-MIT.txt \
  "$root/LICENSE" "generated app-shell MIT license"

manager_signer=unsigned
if [[ "$allow_unsigned" == false ]]; then
  manager_signer="$(
    "$apksigner" verify --print-certs "$manager_apk" 2>/dev/null \
      | sed -n 's/^Signer #1 certificate SHA-256 digest: //p'
  )"
  expected_signer=fb89debcc1d5057ba81959928ad8bb73aa6bf7be932e145e890224fdbec2928f
  builder_signer="$(
    "$apksigner" verify --print-certs "$builder_apk" 2>/dev/null \
      | sed -n 's/^Signer #1 certificate SHA-256 digest: //p'
  )"
  [[ "$manager_signer" == "$expected_signer" && "$builder_signer" == "$manager_signer" ]] || {
    echo "manager and Builder release signing identities are invalid" >&2; exit 1;
  }
fi

echo "Release APK contract passed: $abi $version signer $manager_signer"
