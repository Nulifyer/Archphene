#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"
skip_install=false; release_build=false; include_package_runtime=false; terminal_apk=; serial=emulator-5554; version_code=10000; version_name=1.0.0; android_sdk=; keystore_path=; keystore_password=; key_alias=androiddebugkey; key_password=
while (($#)); do case "$1" in
 --skip-install) skip_install=true; shift;; --release-build) release_build=true; shift;; --include-package-runtime) include_package_runtime=true; shift;; --terminal-apk) terminal_apk="${2:?}"; shift 2;; --serial) serial="${2:?}"; shift 2;; --version-code) version_code="${2:?}"; shift 2;; --version-name) version_name="${2:?}"; shift 2;; --android-sdk) android_sdk="${2:?}"; shift 2;; --keystore-path) keystore_path="${2:?}"; shift 2;; --keystore-password) keystore_password="${2:?}"; shift 2;; --key-alias) key_alias="${2:?}"; shift 2;; --key-password) key_password="${2:?}"; shift 2;; -h|--help) echo "usage: $0 [options]"; exit 0;; *) archphene_die "unknown argument: $1";; esac; done
[[ -z "$android_sdk" ]] || export ANDROID_SDK_ROOT="$(cd "$android_sdk" && pwd)"
args=(--version-code "$version_code" --version-name "$version_name")
[[ "$release_build" == false ]] || args+=(--release-build)
[[ "$include_package_runtime" == true ]] || args+=(--skip-runtime)
"$ARCHPHENE_SCRIPTS_DIR/build-manager-podman.sh" "${args[@]}"
apk="$ARCHPHENE_ROOT/prototypes/linux-app-manager-stub/out-linux/archphene.apk"; archphene_require_file "$apk"
if [[ "$skip_install" == false ]]; then archphene_init_adb "$serial"; archphene_adb_run install -r "$apk"; archphene_adb_run shell am start -n org.archpheneos.manager/.MainActivity; fi
archphene_note "Linux manager built: $apk"
