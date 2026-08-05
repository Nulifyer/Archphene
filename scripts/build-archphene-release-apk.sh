#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"

abi=
version_code=
version_name=
prebuilt_native=false
while (($#)); do
  case "$1" in
    --abi) abi="${2:?missing value for --abi}"; shift 2 ;;
    --version-code) version_code="${2:?missing value for --version-code}"; shift 2 ;;
    --version-name) version_name="${2:?missing value for --version-name}"; shift 2 ;;
    --prebuilt-native) prebuilt_native=true; shift ;;
    -h|--help)
      echo "usage: $0 --abi x86_64|arm64-v8a --version-code CODE --version-name VERSION [--prebuilt-native]"
      exit 0
      ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done

archphene_validate_choice "$abi" "release ABI" x86_64 arm64-v8a
[[ "$version_code" =~ ^[1-9][0-9]{0,9}$ ]] ||
  archphene_die "release version code must be a positive integer"
((10#$version_code <= 2100000000)) ||
  archphene_die "release version code exceeds Android's limit"
[[ ${#version_name} -le 64 &&
    "$version_name" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-+][0-9A-Za-z.-]+)?$ ]] ||
  archphene_die "release version must use MAJOR.MINOR.PATCH syntax"

for command in flock sha256sum; do
  archphene_require_command "$command"
done
archphene_require_file "$ARCHPHENE_ROOT/gradlew"
export ANDROID_SDK_ROOT
ANDROID_SDK_ROOT="$(archphene_android_sdk)"
export GRADLE_USER_HOME="$ARCHPHENE_ROOT/tooling/gradle"
aapt2="$(archphene_android_tool "$ANDROID_SDK_ROOT" "build-tools/36.0.0/aapt2")"

mkdir -p "$ARCHPHENE_ROOT/tooling/build/apk"
exec {build_lock_fd}>"$ARCHPHENE_ROOT/tooling/build/archphene-app-build.lock"
flock "$build_lock_fd"

(
  cd "$ARCHPHENE_ROOT"
  gradle_args=(
    --no-daemon --no-build-cache --stacktrace
    "-ParchpheneAbi=$abi"
    -ParchpheneApplicationId=org.archpheneos.manager
    "-ParchpheneVersionCode=$version_code"
    "-ParchpheneVersionName=$version_name"
    :android:app:assembleRelease
    :android:builder:assembleRelease
  )
  if [[ "$prebuilt_native" == true ]]; then
    gradle_args+=(
      -x buildArchpheneRust
      -x buildArchpheneCompositor
      -x buildArchpheneAndroidDbus
      -x buildArchpheneAndroidGpu
      -x buildArchpheneAndroidAudioX86
      -x buildArchpheneAndroidAudioArm
      -x buildArchphenePipeWireCameraX86
      -x buildArchphenePipeWireCameraArm
      -x rebuildArchphenePackageRuntimePathBridges
    )
  fi
  ./gradlew "${gradle_args[@]}"
)

manager_apk="$ARCHPHENE_ROOT/android/app/build/outputs/apk/release/app-release-unsigned.apk"
builder_apk="$ARCHPHENE_ROOT/android/builder/build/outputs/apk/release/builder-release-unsigned.apk"
archphene_require_file "$manager_apk"
archphene_require_file "$builder_apk"
badging="$("$aapt2" dump badging "$manager_apk")"
[[ "$badging" == "package: name='org.archpheneos.manager' "* ]] ||
  archphene_die "release APK package identity is not org.archpheneos.manager"
[[ "$badging" == *"versionCode='$version_code' versionName='$version_name'"* ]] ||
  archphene_die "release APK version does not match the request"
[[ "$(grep '^native-code:' <<<"$badging")" == "native-code: '$abi'" ]] ||
  archphene_die "release APK native ABI does not match $abi"
builder_badging="$("$aapt2" dump badging "$builder_apk")"
[[ "$builder_badging" == "package: name='org.archphene.builder' "* ]] ||
  archphene_die "Builder release APK package identity is not org.archphene.builder"
[[ "$builder_badging" == *"versionCode='$version_code' versionName='$version_name'"* ]] ||
  archphene_die "Builder release APK version does not match the request"
[[ "$(grep '^native-code:' <<<"$builder_badging")" == "native-code: '$abi'" ]] ||
  archphene_die "Builder release APK native ABI does not match $abi"

artifact="$ARCHPHENE_ROOT/tooling/build/apk/Archphene-$abi-$version_name-unsigned.apk"
builder_artifact="$ARCHPHENE_ROOT/tooling/build/apk/Archphene-Builder-$abi-$version_name-unsigned.apk"
cp -- "$manager_apk" "$artifact"
cp -- "$builder_apk" "$builder_artifact"
checksum="$artifact.sha256"
builder_checksum="$builder_artifact.sha256"
(
  cd "$(dirname "$artifact")"
  sha256sum "$(basename "$artifact")" > "$(basename "$checksum")"
  sha256sum "$(basename "$builder_artifact")" > "$(basename "$builder_checksum")"
)
archphene_note "Unsigned Archphene release APK: $artifact"
archphene_note "SHA-256: $checksum"
archphene_note "Unsigned Archphene Builder release APK: $builder_artifact"
archphene_note "SHA-256: $builder_checksum"
