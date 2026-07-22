#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"
runtime_root=tooling/downloads/arch-curated-kcalc-x86_64/runtime-root
resolved_file=tooling/downloads/arch-curated-kcalc-x86_64/elf-needed-resolved.tsv
app_lib_dir=prototypes/kcalc-android-app/lib/x86_64
loader_path=
compat_libc_path=
while (($#)); do
  case "$1" in
    --runtime-root) runtime_root="${2:?}"; shift 2;; --resolved-file) resolved_file="${2:?}"; shift 2;; --app-lib-dir) app_lib_dir="${2:?}"; shift 2;;
    --loader-path) loader_path="${2:?}"; shift 2;; --compat-libc-path) compat_libc_path="${2:?}"; shift 2;;
    -h|--help) echo "usage: $0 [--runtime-root PATH] [--resolved-file PATH] [--app-lib-dir PATH] [--loader-path PATH] [--compat-libc-path PATH]"; exit 0;; *) archphene_die "unknown argument: $1";;
  esac
done
for variable in runtime_root resolved_file app_lib_dir; do value="${!variable}"; [[ "$value" == /* ]] || printf -v "$variable" '%s/%s' "$ARCHPHENE_ROOT" "$value"; done
mkdir -p "$app_lib_dir"; archphene_require_directory "$runtime_root"; archphene_require_file "$resolved_file"
preserve=(libarchphene_qt_clipboard_probe.so libarchphene_wayland_jni.so libarchphene_wayland_socket_probe.so libarchphene_frame_client.so libarchphene_shm_frame_client.so libarchphene_wayland_shm_client.so libarchphene_wayland_evented_client.so libarchphene_wayland_xdg_client.so libarchphene_wayland_api_client.so libarchphene_wayland_client_android.so libarchphene_wayland_android_api_client.so libarchphene_wayland_android_api_render_client.so libarchphene_wayland_android_api_xdg_client.so)
copied=0; total_bytes=0
while IFS=$'\t' read -r name relative; do
  [[ -n "$name" && -n "$relative" ]] || continue
  source_file="$runtime_root/$relative"; archphene_require_file "$source_file"; cp "$source_file" "$app_lib_dir/$name"
  ((copied += 1)); ((total_bytes += $(stat -c %s "$source_file")))
done < "$resolved_file"
if [[ -n "$loader_path" ]]; then [[ "$loader_path" == /* ]] || loader_path="$ARCHPHENE_ROOT/$loader_path"; loader="$loader_path"; else loader="$runtime_root/usr/lib/ld-linux-x86-64.so.2"; fi
archphene_require_file "$loader"; cp "$loader" "$app_lib_dir/libld.so.2"; cp "$loader" "$app_lib_dir/libarchphene_ld.so"
if [[ -n "$compat_libc_path" ]]; then [[ "$compat_libc_path" == /* ]] || compat_libc_path="$ARCHPHENE_ROOT/$compat_libc_path"; archphene_require_file "$compat_libc_path"; cp "$compat_libc_path" "$app_lib_dir/libc.so.6"; fi
archphene_require_file "$runtime_root/usr/bin/kcalc"; cp "$runtime_root/usr/bin/kcalc" "$app_lib_dir/libarchphene_kcalc.so"
archphene_require_file "$runtime_root/usr/lib/qt6/plugins/platforms/libqwayland.so"; cp "$runtime_root/usr/lib/qt6/plugins/platforms/libqwayland.so" "$app_lib_dir/libqwayland.so"
archphene_require_file "$runtime_root/usr/lib/qt6/plugins/wayland-shell-integration/libxdg-shell.so"; cp "$runtime_root/usr/lib/qt6/plugins/wayland-shell-integration/libxdg-shell.so" "$app_lib_dir/libarchphene_xdg_shell.so"
for name in "${preserve[@]}"; do [[ -f "$app_lib_dir/$name" ]] || printf 'warning: expected preserved bridge library is missing: %s\n' "$name" >&2; done
megabytes="$(awk -v bytes="$total_bytes" 'BEGIN {printf "%.1f", bytes/1048576}')"
archphene_note "AppLibDir: $app_lib_dir"
archphene_note "CopiedResolvedLibraries: $copied"
archphene_note "CopiedResolvedMegabytes: $megabytes"
archphene_note "NativeFiles: $(find "$app_lib_dir" -maxdepth 1 -type f | wc -l)"

