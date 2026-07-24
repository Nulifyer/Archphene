#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"

readonly required_java=26.0.1
readonly required_gradle=9.6.1
readonly required_wrapper_sha=497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7
readonly required_distribution_sha=9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14
readonly native_image=localhost/archphene-android-native:ndk29-rust1.88

archphene_require_command java
archphene_require_command gradle
archphene_require_command podman
archphene_require_command sha256sum

java_version="$(
  java -XshowSettings:properties -version 2>&1 |
    awk '$1 == "java.version" && $2 == "=" { print $3; exit }'
)"
[[ "$java_version" == "$required_java" ]] ||
  archphene_die "JDK $required_java is required; found ${java_version:-unknown}"

gradle_version="$(
  gradle --version |
    awk '$1 == "Gradle" { print $2; exit }'
)"
[[ "$gradle_version" == "$required_gradle" ]] ||
  archphene_die "Gradle $required_gradle is required; found ${gradle_version:-unknown}"

wrapper="$ARCHPHENE_ROOT/gradle/wrapper/gradle-wrapper.jar"
properties="$ARCHPHENE_ROOT/gradle/wrapper/gradle-wrapper.properties"
archphene_require_file "$wrapper"
archphene_require_file "$properties"
[[ "$(archphene_sha256_file "$wrapper")" == "$required_wrapper_sha" ]] ||
  archphene_die "Gradle wrapper JAR checksum does not match the pinned release"
grep -Fqx "distributionSha256Sum=$required_distribution_sha" "$properties" ||
  archphene_die "Gradle distribution checksum is not pinned"

sdk="$(archphene_android_sdk)"
grep -Fqx "AndroidVersion.ApiLevel=36" "$sdk/platforms/android-36/source.properties" ||
  archphene_die "Android SDK platform 36 revision is missing"
grep -Fqx "Pkg.Revision=36.0.0" "$sdk/build-tools/36.0.0/source.properties" ||
  archphene_die "Android Build Tools 36.0.0 are missing"

archphene_podman_image_exists "$native_image" ||
  archphene_die "missing existing build image $native_image; this check will not download it"
podman run --rm --network=none "$native_image" bash -lc '
  set -euo pipefail
  [[ "$(rustc --version)" == "rustc 1.88.0 (6b00bc388 2025-06-23)" ]]
  [[ "$(cargo --version)" == "cargo 1.88.0 (873a06493 2025-05-10)" ]]
  grep -Fqx "Pkg.Revision = 29.0.14206865" \
    "$ANDROID_SDK_ROOT/ndk/29.0.14206865/source.properties"
'

archphene_note "Production toolchain contract passed"
archphene_note "  JDK $required_java; Gradle $required_gradle; Rust/Cargo 1.88.0"
archphene_note "  SDK/Build Tools 36; NDK 29.0.14206865"
