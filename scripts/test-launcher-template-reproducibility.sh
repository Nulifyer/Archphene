#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"

archphene_require_command gradle
archphene_require_command sha256sum
archphene_require_command unzip

export ANDROID_SDK_ROOT
ANDROID_SDK_ROOT="$(archphene_android_sdk)"
export GRADLE_USER_HOME="$ARCHPHENE_ROOT/tooling/gradle"
apk="$ARCHPHENE_ROOT/android/launcher-template/build/outputs/apk/release/launcher-template-release-unsigned.apk"
gradle_arguments=(
  --no-daemon
  --stacktrace
  :android:launcher-template:assembleRelease
)

(
  cd "$ARCHPHENE_ROOT"
  gradle "${gradle_arguments[@]}" >/dev/null
)
archphene_require_file "$apk"
if unzip -Z1 "$apk" | grep -qx 'META-INF/version-control-info.textproto'; then
  archphene_die "launcher template contains repository-wide VCS metadata"
fi
template_icon_sha256=2babc12a8af9fa0f7018a7d20110f4436e128ddac876d6276b519daefeea0a56
template_icon_matches=0
while IFS= read -r entry; do
  [[ "$entry" == *.png ]] || continue
  read -r entry_sha256 _ < <(unzip -p "$apk" "$entry" | sha256sum)
  if [[ "$entry_sha256" == "$template_icon_sha256" ]]; then
    template_icon_matches=$((template_icon_matches + 1))
  fi
done < <(unzip -Z1 "$apk")
[[ "$template_icon_matches" -eq 1 ]] ||
  archphene_die "launcher template does not contain exactly one replaceable icon"
first_hash="$(archphene_sha256_file "$apk")"

(
  cd "$ARCHPHENE_ROOT"
  gradle --rerun-tasks "${gradle_arguments[@]}" >/dev/null
)
second_hash="$(archphene_sha256_file "$apk")"
[[ "$first_hash" == "$second_hash" ]] ||
  archphene_die "launcher template changed across identical rebuilds"

archphene_note "Launcher template reproducibility passed: $first_hash"
