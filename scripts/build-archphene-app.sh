#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"

archphene_require_command gradle

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

gradle_arguments=(--no-daemon --stacktrace)
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
if [[ -n "$abi" ]]; then
  abi_apk="$ARCHPHENE_ROOT/tooling/build/apk/app-debug-$abi.apk"
  mkdir -p "$(dirname "$abi_apk")"
  cp -- "$apk" "$abi_apk"
  apk="$abi_apk"
fi
archphene_note "Archphene debug APK: $apk"
