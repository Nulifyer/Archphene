#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"

archphene_require_command gradle
archphene_require_command flock
archphene_require_command strings
archphene_require_command unzip

abi=
while (($#)); do
  case "$1" in
    --abi)
      abi="${2:?missing value for --abi}"
      shift 2
      ;;
    -h|--help)
      echo "usage: $0 [--abi x86_64|arm64-v8a]"
      exit 0
      ;;
    *)
      archphene_die "unknown argument: $1"
      ;;
  esac
done
if [[ -n "$abi" && "$abi" != x86_64 && "$abi" != arm64-v8a ]]; then
  archphene_die "unsupported ABI: $abi"
fi

export ANDROID_SDK_ROOT
ANDROID_SDK_ROOT="$(archphene_android_sdk)"
export GRADLE_USER_HOME="$ARCHPHENE_ROOT/tooling/gradle"

# Every exact-ABI invocation writes through the same Android intermediates and
# app-debug.apk. Serialize the complete build-and-copy transaction so two
# callers cannot combine one ABI's manifest with another invocation's dex.
mkdir -p "$ARCHPHENE_ROOT/tooling/build"
exec {build_lock_fd}>"$ARCHPHENE_ROOT/tooling/build/archphene-app-build.lock"
flock "$build_lock_fd"

# Exact device artifacts must not inherit a stale or partially published local
# task-cache entry from an interrupted build. Dependency caches remain enabled.
gradle_arguments=(--no-daemon --no-build-cache --stacktrace)
if [[ -n "$abi" ]]; then
  gradle_arguments+=("-ParchpheneAbi=$abi")
fi
gradle_arguments+=(:android:app:assembleDebug)

(
  cd "$ARCHPHENE_ROOT"
  gradle "${gradle_arguments[@]}"
)

apk="$ARCHPHENE_ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
archphene_require_file "$apk"
if ! unzip -p "$apk" 'classes*.dex' |
  strings |
  grep -F 'Lorg/archphene/app/ArchpheneDebugApplication;' >/dev/null; then
  archphene_die "debug APK is missing ArchpheneDebugApplication"
fi
if [[ -n "$abi" ]]; then
  if ! unzip -Z1 "$apk" |
    grep -F "lib/$abi/libarchphene_android.so" >/dev/null; then
    archphene_die "debug APK is missing the requested $abi runtime"
  fi
  abi_apk="$ARCHPHENE_ROOT/tooling/build/apk/app-debug-$abi.apk"
  mkdir -p "$(dirname "$abi_apk")"
  cp -- "$apk" "$abi_apk"
  apk="$abi_apk"
fi
archphene_note "Archphene debug APK: $apk"
