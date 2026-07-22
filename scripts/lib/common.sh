#!/usr/bin/env bash

# Shared helpers for Archphene's Linux host scripts. Callers must enable their
# preferred shell options before sourcing this file.

ARCHPHENE_SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARCHPHENE_ROOT="$(cd "$ARCHPHENE_SCRIPTS_DIR/.." && pwd)"

archphene_die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

archphene_note() {
  printf '%s\n' "$*"
}

archphene_require_command() {
  command -v "$1" >/dev/null 2>&1 || archphene_die "required command is missing: $1"
}

archphene_require_file() {
  [[ -f "$1" ]] || archphene_die "required file is missing: $1"
}

archphene_require_directory() {
  [[ -d "$1" ]] || archphene_die "required directory is missing: $1"
}

archphene_validate_choice() {
  local value="$1" label="$2"
  shift 2
  local choice
  for choice in "$@"; do
    [[ "$value" == "$choice" ]] && return 0
  done
  archphene_die "$label must be one of: $*"
}

archphene_android_sdk() {
  local candidate
  for candidate in \
      "$ARCHPHENE_ROOT/tooling/android-sdk" \
      "${ANDROID_SDK_ROOT:-}" \
      "${ANDROID_HOME:-}"; do
    [[ -n "$candidate" && -d "$candidate" ]] || continue
    (cd "$candidate" && pwd)
    return 0
  done
  archphene_die "Android SDK not found in tooling/android-sdk, ANDROID_SDK_ROOT, or ANDROID_HOME"
}

archphene_android_tool() {
  local sdk="$1" relative="$2" candidate
  for candidate in "$sdk/$relative"; do
    [[ -x "$candidate" || -f "$candidate" ]] || continue
    printf '%s\n' "$candidate"
    return 0
  done
  archphene_die "Android SDK tool is missing: $sdk/$relative"
}

archphene_adb() {
  if command -v adb >/dev/null 2>&1; then
    command -v adb
    return 0
  fi
  local sdk
  sdk="$(archphene_android_sdk)"
  archphene_android_tool "$sdk" "platform-tools/adb"
}

archphene_adb_args() {
  local serial="$1"
  ARCHPHENE_ADB_ARGS=()
  [[ -z "$serial" ]] || ARCHPHENE_ADB_ARGS=(-s "$serial")
}

archphene_adb_run() {
  "$ARCHPHENE_ADB" "${ARCHPHENE_ADB_ARGS[@]}" "$@"
}

archphene_android_pid() {
  local package="$1" candidates processes
  candidates="$(archphene_adb_run shell pidof "$package" 2>/dev/null | tr -d '\r')"
  [[ -n "$candidates" ]] || return 1
  processes="$(archphene_adb_run shell ps -A -o PID,PPID)"
  awk -v ids="$candidates" '
    BEGIN {
      count = split(ids, values, / +/)
      for (i = 1; i <= count; i++) candidate[values[i]] = 1
    }
    { parent[$1] = $2 }
    END {
      for (id in candidate) {
        if (!(parent[id] in candidate)) {
          print id
          exit
        }
      }
    }
  ' <<<"$processes"
}

archphene_linux_loader_pid() {
  local android_pid="$1" processes
  processes="$(archphene_adb_run shell ps -A -o PID,PPID,NAME)"
  awk -v root="$android_pid" '
    { parent[$1] = $2; name[$1] = $3 }
    END {
      for (candidate in name) {
        if (name[candidate] != "loader" && name[candidate] != "libarchphene_ld.so") continue
        current = candidate
        for (depth = 0; depth < 64 && current in parent; depth++) {
          if (parent[current] == root) {
            print candidate
            exit
          }
          current = parent[current]
        }
      }
    }
  ' <<<"$processes"
}

archphene_init_adb() {
  ARCHPHENE_ADB="$(archphene_adb)"
  archphene_adb_args "${1:-}"
}

archphene_capture_ui() {
  local name="$1"
  archphene_adb_run shell uiautomator dump --compressed "/sdcard/$name.xml" >/dev/null
  archphene_adb_run shell cat "/sdcard/$name.xml"
}

archphene_regex_contains() {
  local text="$1" pattern="$2"
  python3 -c 'import re,sys; raise SystemExit(0 if re.search(sys.argv[1], sys.stdin.read()) else 1)' \
    "$pattern" <<<"$text"
}

archphene_ui_node_center() {
  local text="$1" pattern="$2" label="$3"
  python3 -c '
import re, sys
pattern, label = sys.argv[1:]
text = sys.stdin.read()
match = re.search(pattern + r"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", text)
if match is None:
    raise SystemExit(f"Could not find {label}")
x1, y1, x2, y2 = map(int, match.groups())
print((x1 + x2) // 2, (y1 + y2) // 2)
' "$pattern" "$label" <<<"$text"
}

archphene_tap_ui_pattern() {
  local text="$1" pattern="$2" label="$3" center x y
  center="$(archphene_ui_node_center "$text" "$pattern" "$label")"
  read -r x y <<<"$center"
  archphene_adb_run shell input tap "$x" "$y" >/dev/null
}

archphene_wait_ui() {
  local pattern="$1" name="$2" seconds="${3:-15}" deadline
  deadline=$((SECONDS + seconds))
  ARCHPHENE_UI=
  while ((SECONDS < deadline)); do
    sleep 0.7
    ARCHPHENE_UI="$(archphene_capture_ui "$name" 2>/dev/null || true)"
    if archphene_regex_contains "$ARCHPHENE_UI" "$pattern"; then
      return 0
    fi
  done
  archphene_die "timed out waiting for UI pattern: $pattern"
}

archphene_podman_image_exists() {
  podman image exists "$1" >/dev/null 2>&1
}

archphene_sha256_file() {
  sha256sum "$1" | awk '{print $1}'
}

archphene_mktemp_dir() {
  mkdir -p "$ARCHPHENE_ROOT/tooling/build"
  mktemp -d "$ARCHPHENE_ROOT/tooling/build/$1.XXXXXX"
}

archphene_ensure_debug_keystore() {
  local key="$ARCHPHENE_ROOT/tooling/signing/archpheneos-manager-debug.keystore"
  if [[ ! -f "$key" ]]; then
    archphene_require_command keytool
    mkdir -p "$(dirname "$key")"
    keytool -genkeypair -noprompt \
      -keystore "$key" -storepass android -keypass android \
      -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=Archphene,O=Archphene,C=US" >&2
  fi
  printf '%s\n' "$key"
}

archphene_ensure_named_debug_keystore() {
  local name="$1" distinguished_name="$2"
  local key="$ARCHPHENE_ROOT/tooling/signing/$name"
  if [[ ! -f "$key" ]]; then
    archphene_require_command keytool
    mkdir -p "$(dirname "$key")"
    keytool -genkeypair -noprompt -keystore "$key" \
      -storepass android -keypass android -alias androiddebugkey \
      -keyalg RSA -keysize 2048 -validity 10000 -dname "$distinguished_name" >&2
  fi
  printf '%s\n' "$key"
}

archphene_probe_signing_environment() {
  archphene_ensure_debug_keystore >/dev/null
  export KEYSTORE_PATH=/workspace/tooling/signing/archpheneos-manager-debug.keystore
  export KEYSTORE_PASSWORD=android
  export KEY_ALIAS=androiddebugkey
  export KEY_PASSWORD=android
}
