#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"

avd_name=ArchpheneOS_x86_64_api36
gpu_mode=host
cores=6
memory_mb=8192
allow_snapshots=false
while (($#)); do
  case "$1" in
    --avd-name) avd_name="${2:?missing value for --avd-name}"; shift 2 ;;
    --gpu-mode) gpu_mode="${2:?missing value for --gpu-mode}"; shift 2 ;;
    --cores) cores="${2:?missing value for --cores}"; shift 2 ;;
    --memory-mb) memory_mb="${2:?missing value for --memory-mb}"; shift 2 ;;
    --allow-snapshots) allow_snapshots=true; shift ;;
    -h|--help) echo "usage: $0 [--avd-name NAME] [--gpu-mode host|auto|swiftshader_indirect] [--cores 2..16] [--memory-mb 2048..16384] [--allow-snapshots]"; exit 0 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
archphene_validate_choice "$gpu_mode" GPU-mode host auto swiftshader_indirect
[[ "$cores" =~ ^[0-9]+$ ]] && ((cores >= 2 && cores <= 16)) || archphene_die "cores must be from 2 to 16"
[[ "$memory_mb" =~ ^[0-9]+$ ]] && ((memory_mb >= 2048 && memory_mb <= 16384)) || archphene_die "memory must be from 2048 to 16384 MiB"
sdk="$(archphene_android_sdk)"
emulator="$(archphene_android_tool "$sdk" emulator/emulator)"
export ANDROID_SDK_ROOT="$sdk"
export ANDROID_AVD_HOME="$ARCHPHENE_ROOT/tooling/avd"
export ANDROID_EMULATOR_HOME="$ARCHPHENE_ROOT/tooling/emulator-home"
export ANDROID_PREFS_ROOT="$ANDROID_EMULATOR_HOME"
mkdir -p "$ANDROID_EMULATOR_HOME"
args=(-avd "$avd_name" -gpu "$gpu_mode" -cores "$cores" -memory "$memory_mb" -no-boot-anim -partition-size 2047 -no-metrics)
[[ "$allow_snapshots" == true ]] || args+=(-no-snapshot-load)
"$emulator" "${args[@]}" &
pid=$!
archphene_note "Started visible emulator $avd_name (PID $pid)"

