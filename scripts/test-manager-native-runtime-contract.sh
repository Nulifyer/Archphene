#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"
apk= abi= android_sdk=
while (($#)); do
  case "$1" in
    --apk) apk="${2:?}"; shift 2;; --abi) abi="${2:?}"; shift 2;; --android-sdk) android_sdk="${2:?}"; shift 2;;
    -h|--help) echo "usage: $0 --apk PATH --abi x86_64|arm64-v8a [--android-sdk PATH]"; exit 0;; *) archphene_die "unknown argument: $1";;
  esac
done
[[ -n "$apk" && -n "$abi" ]] || archphene_die "--apk and --abi are required"
archphene_validate_choice "$abi" ABI x86_64 arm64-v8a
apk="$(realpath "$apk")"; sdk="${android_sdk:-$(archphene_android_sdk)}"; aapt2="$(archphene_android_tool "$sdk" build-tools/36.0.0/aapt2)"
architecture=x86_64; [[ "$abi" == arm64-v8a ]] && architecture=aarch64
count="$(python3 - "$apk" "$abi" "$architecture" <<'PY'
import hashlib, re, sys, zipfile
apk, abi, architecture = sys.argv[1:]
prefix = f"lib/{abi}/"
with zipfile.ZipFile(apk) as archive:
    infos = {info.filename: info for info in archive.infolist()}
    native = [i for i in infos.values() if i.filename.startswith(prefix) and not i.is_dir()]
    if not native: raise SystemExit(f"APK has no {abi} native libraries")
    invalid = [i.filename for i in native if not re.fullmatch(r"lib[A-Za-z0-9_.+-]+\.so", i.filename[len(prefix):])]
    if invalid: raise SystemExit(f"invalid Android native names: {invalid}")
    catalog_name = f"assets/package-runtime/manager-native-{architecture}.tsv"
    try: lines = archive.read(catalog_name).decode().splitlines()
    except KeyError: raise SystemExit(f"manager native catalog is missing: {catalog_name}")
    logical_names, packaged_names, count = set(), set(), 0
    for line in lines:
        if not line or line.startswith("#"): continue
        fields = line.split("\t")
        if len(fields) != 4: raise SystemExit(f"invalid catalog row: {line}")
        logical, packaged, digest, size_text = fields
        if not re.fullmatch(r"[A-Za-z0-9@._+-]{1,128}", logical) or not re.fullmatch(r"lib[A-Za-z0-9_.+-]+\.so", packaged) or not re.fullmatch(r"[0-9a-f]{64}", digest): raise SystemExit(f"invalid catalog row: {line}")
        size = int(size_text)
        if size <= 0 or logical in logical_names or packaged in packaged_names: raise SystemExit(f"duplicate/invalid catalog row: {line}")
        logical_names.add(logical); packaged_names.add(packaged)
        try: data = archive.read(prefix + packaged)
        except KeyError: raise SystemExit(f"catalog payload missing: {packaged}")
        if len(data) != size or hashlib.sha256(data).hexdigest() != digest: raise SystemExit(f"catalog payload mismatch: {packaged}")
        count += 1
    if not {"libc.so.6", "libalpm.so.16"} <= logical_names: raise SystemExit("catalog lacks glibc or libalpm")
    if prefix + "libarchphene_pacman.so" not in infos: raise SystemExit("manager pacman executable is missing")
    print(f"{count} {len(native)}")
PY
)"
read -r catalog_count native_count <<<"$count"
manifest="$("$aapt2" dump xmltree "$apk" --file AndroidManifest.xml)"
[[ "$manifest" =~ android:extractNativeLibs.*=true ]] || archphene_die "manager APK does not enable native extraction"
archphene_note "Manager native runtime contract passed: $abi, $catalog_count soname aliases, $native_count extractable libraries."

