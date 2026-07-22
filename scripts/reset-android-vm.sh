#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"

avd_name=ArchpheneOS_x86_64_api36
system_image='system-images;android-36;google_apis;x86_64'
device=pixel_8
no_launch=false
while (($#)); do
  case "$1" in
    --avd-name) avd_name="${2:?missing value for --avd-name}"; shift 2 ;;
    --system-image) system_image="${2:?missing value for --system-image}"; shift 2 ;;
    --device) device="${2:?missing value for --device}"; shift 2 ;;
    --no-launch) no_launch=true; shift ;;
    -h|--help) echo "usage: $0 [--avd-name NAME] [--system-image PACKAGE] [--device ID] [--no-launch]"; exit 0 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ "$avd_name" =~ ^[A-Za-z0-9._-]+$ ]] || archphene_die "AVD name contains unsafe characters"
sdk="$(archphene_android_sdk)"
ARCHPHENE_ADB="$(archphene_adb)"
avdmanager="$(archphene_android_tool "$sdk" cmdline-tools/latest/bin/avdmanager)"
avd_root="$(realpath -m "$ARCHPHENE_ROOT/tooling/avd")"
avd_dir="$(realpath -m "$avd_root/$avd_name.avd")"
avd_ini="$(realpath -m "$avd_root/$avd_name.ini")"
emulator_home="$(realpath -m "$ARCHPHENE_ROOT/tooling/emulator-home")"
for target in "$avd_dir" "$avd_ini" "$emulator_home"; do
  [[ "$target" == "$ARCHPHENE_ROOT"/tooling/* ]] || archphene_die "refusing to remove path outside tooling: $target"
done
"$ARCHPHENE_ADB" -s emulator-5554 emu kill >/dev/null 2>&1 || true
sleep 3
pkill -x emulator 2>/dev/null || true
pkill -x qemu-system-x86_64 2>/dev/null || true
rm -rf -- "$avd_dir" "$emulator_home"
rm -f -- "$avd_ini"
mkdir -p "$avd_root" "$emulator_home"
export ANDROID_SDK_ROOT="$sdk" ANDROID_AVD_HOME="$avd_root"
export ANDROID_EMULATOR_HOME="$emulator_home" ANDROID_PREFS_ROOT="$emulator_home"
printf 'no\n' | "$avdmanager" create avd --force --name "$avd_name" --package "$system_image" --device "$device" --path "$avd_dir"
config="$avd_dir/config.ini"
archphene_require_file "$config"
set_config() {
  local key="$1" value="$2" temporary
  temporary="$(mktemp)"
  awk -F= -v key="$key" '$1 != key {print}' "$config" > "$temporary"
  printf '%s=%s\n' "$key" "$value" >> "$temporary"
  mv "$temporary" "$config"
}
set_config disk.dataPartition.size 16G
set_config fastboot.forceColdBoot no
set_config fastboot.forceFastBoot yes
set_config firstboot.saveToLocalSnapshot no
set_config hw.cpu.ncore 6
set_config hw.gpu.enabled yes
set_config hw.gpu.mode host
set_config hw.keyboard no
set_config hw.ramSize 8192
set_config showDeviceFrame yes
archphene_note "Recreated AVD at $avd_dir"
[[ "$no_launch" == true ]] || "$ARCHPHENE_SCRIPTS_DIR/start-android-vm.sh" --avd-name "$avd_name"

