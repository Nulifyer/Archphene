#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
"$root/scripts/build-android-dbus-podman.sh" --architecture x86_64
"$root/scripts/build-android-dbus-podman.sh" --architecture aarch64
