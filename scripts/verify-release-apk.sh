#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "usage: $0 MANAGER_APK ABI VERSION" >&2
  exit 2
fi

manager_apk="$1"
abi="$2"
version="$3"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
build_tools_version="${ANDROID_BUILD_TOOLS_VERSION:-36.0.0}"
bt="$sdk/build-tools/$build_tools_version"

[[ -f "$manager_apk" ]] || { echo "manager APK is missing" >&2; exit 1; }
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
  for candidate in "$bt/$name" "$bt/$name.exe" "$bt/$name.bat"; do
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

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
terminal_apk="$work/archphene-terminal.apk"

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
grep -F 'android:pageSizeCompat' <<<"$manager_manifest" | grep -F '=32' >/dev/null || {
  echo "manager does not enable Android page-size compatibility mode" >&2; exit 1;
}
grep -F 'android:extractNativeLibs' <<<"$manager_manifest" | grep -F '=true' >/dev/null || {
  echo "manager does not enable native-library extraction" >&2; exit 1;
}
manager_catalog="assets/package-runtime/manager-native-${abi/arm64-v8a/aarch64}.tsv"
unzip -Z1 "$manager_apk" | grep -Fx "$manager_catalog" >/dev/null || {
  echo "manager native soname catalog is missing" >&2; exit 1;
}
catalog_file="$work/manager-native.tsv"
unzip -p "$manager_apk" "$manager_catalog" > "$catalog_file"
declare -A catalog_logical=() catalog_packaged=()
catalog_count=0
has_libc=false
has_libalpm=false
while IFS=$'\t' read -r logical packaged expected_hash expected_size extra; do
  [[ -n "$logical" && "$logical" != \#* ]] || continue
  [[ -z "$extra" && "$logical" =~ ^[A-Za-z0-9@._+-]{1,128}$ \
      && "$packaged" =~ ^lib[A-Za-z0-9_]+\.so$ \
      && "$expected_hash" =~ ^[0-9a-f]{64}$ \
      && "$expected_size" =~ ^[1-9][0-9]*$ \
      && -z "${catalog_logical[$logical]:-}" \
      && -z "${catalog_packaged[$packaged]:-}" ]] || {
    echo "manager native soname catalog row is invalid" >&2; exit 1;
  }
  catalog_logical[$logical]=1
  catalog_packaged[$packaged]=1
  payload="$work/$packaged"
  unzip -p "$manager_apk" "lib/$abi/$packaged" > "$payload"
  [[ "$(wc -c < "$payload")" == "$expected_size" \
      && "$(sha256sum "$payload" | cut -d ' ' -f 1)" == "$expected_hash" ]] || {
    echo "manager native soname payload failed verification: $packaged" >&2; exit 1;
  }
  if [[ "$logical" == libc.so.6 ]]; then has_libc=true; fi
  if [[ "$logical" == libalpm.so.16 ]]; then has_libalpm=true; fi
  catalog_count=$((catalog_count + 1))
done < "$catalog_file"
[[ "$catalog_count" -gt 0 && "$has_libc" == true && "$has_libalpm" == true ]] || {
  echo "manager native soname catalog lacks required entries" >&2; exit 1;
}
invalid_native="$(unzip -Z1 "$manager_apk" | grep "^lib/$abi/" \
  | grep -Ev "^lib/$abi/lib[A-Za-z0-9_.+-]+\\.so$" || true)"
[[ -z "$invalid_native" ]] || {
  echo "manager APK contains non-extractable native library names:" >&2
  printf '%s\n' "$invalid_native" >&2
  exit 1
}
"$zipalign" -c -P 16 -v 4 "$manager_apk" >/dev/null

unzip -p "$manager_apk" assets/package-runtime/archphene-terminal.apk > "$terminal_apk"
[[ -s "$terminal_apk" ]] || { echo "embedded Terminal APK is missing" >&2; exit 1; }
terminal_badging="$("$aapt2" dump badging "$terminal_apk")"
grep -F "package: name='org.archpheneos.terminal'" <<<"$terminal_badging" >/dev/null || {
  echo "Terminal package name is invalid" >&2; exit 1;
}
grep -F "versionName='$version'" <<<"$terminal_badging" >/dev/null || {
  echo "Terminal versionName does not equal $version" >&2; exit 1;
}
terminal_version_code="$(sed -n "s/^package: .* versionCode='\([0-9][0-9]*\)'.*/\1/p" <<<"$terminal_badging")"
[[ "$terminal_version_code" == "$manager_version_code" ]] || {
  echo "manager and Terminal versionCode values differ" >&2; exit 1;
}
[[ "$(grep '^native-code:' <<<"$terminal_badging")" == "native-code: '$abi'" ]] || {
  echo "Terminal native ABI set does not equal $abi" >&2; exit 1;
}
terminal_manifest="$("$aapt2" dump xmltree "$terminal_apk" --file AndroidManifest.xml)"
grep -F 'android:pageSizeCompat' <<<"$terminal_manifest" | grep -F '=32' >/dev/null || {
  echo "Terminal does not enable Android page-size compatibility mode" >&2; exit 1;
}
"$zipalign" -c -P 16 -v 4 "$terminal_apk" >/dev/null

signer() {
  "$apksigner" verify --print-certs "$1" 2>/dev/null \
    | sed -n 's/^Signer #1 certificate SHA-256 digest: //p'
}
manager_signer="$(signer "$manager_apk")"
terminal_signer="$(signer "$terminal_apk")"
[[ "$manager_signer" =~ ^[0-9a-f]{64}$ && "$manager_signer" == "$terminal_signer" ]] || {
  echo "manager and Terminal signing identities differ" >&2; exit 1;
}

echo "Release APK contract passed: $abi $version signer $manager_signer"
