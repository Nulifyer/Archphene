#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
package_name=
expected_toolkit=
timeout=900
skip_install=false
while (($#)); do
  case "$1" in
    --serial)
      serial="${2:?}"
      shift 2
      ;;
    --package-name)
      package_name="${2:?}"
      shift 2
      ;;
    --expected-toolkit)
      expected_toolkit="${2:?}"
      shift 2
      ;;
    --timeout-seconds)
      timeout="${2:?}"
      shift 2
      ;;
    --skip-install)
      skip_install=true
      shift
      ;;
    -h|--help)
      echo "usage: $0 --package-name NAME --expected-toolkit qt6|gtk3|gtk4|wayland [--serial SERIAL] [--skip-install] [--timeout-seconds N]"
      exit 0
      ;;
    *)
      archphene_die "unknown argument: $1"
      ;;
  esac
done

[[ "$package_name" =~ ^[a-z0-9@._+-]{1,96}$ ]] \
  || archphene_die '--package-name must be a safe Arch package name'
archphene_validate_choice "$expected_toolkit" toolkit qt6 gtk3 gtk4 wayland
[[ "$timeout" =~ ^[0-9]+$ ]] && ((timeout >= 60)) \
  || archphene_die '--timeout-seconds must be an integer of at least 60'

archphene_test_init "$serial"
manager=org.archpheneos.manager
android_package="org.archphene.linux.p$(printf 'extra/%s' "$package_name" | sha256sum | cut -c1-32)"
artifact_dir="$ARCHPHENE_ROOT/tooling/artifacts"
serial_artifact="${serial//[^A-Za-z0-9._-]/_}"
artifact_prefix="$artifact_dir/candidate-$package_name-$serial_artifact"
mkdir -p "$artifact_dir" "$ARCHPHENE_ROOT/tooling/build"
tmp_dir="$(mktemp -d "$ARCHPHENE_ROOT/tooling/build/candidate-$package_name.XXXXXX")"
install_appop_mode="$(archphene_adb_run shell appops get "$manager" REQUEST_INSTALL_PACKAGES \
  | sed -n 's/.*REQUEST_INSTALL_PACKAGES: \([a-z_-]*\).*/\1/p' | head -n1)"
[[ -n "$install_appop_mode" ]] || install_appop_mode=default
cleanup() {
  if [[ -n "${android_package:-}" ]]; then
    archphene_adb_run shell am force-stop "$android_package" >/dev/null 2>&1 || true
  fi
  archphene_adb_run shell appops set "$manager" REQUEST_INSTALL_PACKAGES "$install_appop_mode" \
    >/dev/null 2>&1 || true
  rm -rf "$tmp_dir"
}
trap cleanup EXIT

manager_dump="$(archphene_adb_run shell dumpsys package "$manager")"
archphene_regex_contains "$manager_dump" '(?m)^\s*flags=\[[^]]*DEBUGGABLE' \
  || archphene_die 'candidate installation requires a debuggable manager'
[[ "$(archphene_adb_run shell getconf PAGE_SIZE | tr -d '\r\n')" == 4096 ]] \
  || archphene_die 'official Arch x86_64 candidates require the 4 KB lane'

if [[ "$skip_install" == false ]]; then
  archphene_adb_run shell appops set "$manager" REQUEST_INSTALL_PACKAGES allow
  # A previous interrupted transaction can leave a confirmation activity in
  # the foreground. Do not let that stale Install/Update button satisfy this
  # candidate's UI wait while its (potentially large) closure is still staging.
  archphene_adb_run shell am force-stop com.google.android.packageinstaller
  archphene_adb_run logcat -c
  archphene_adb_run shell am force-stop "$manager"
  archphene_adb_run shell am start -W -n "$manager/.MainActivity" \
    --es archphene_test_assemble_qt "$package_name" \
    --ez archphene_test_stage_transaction true \
    --ez archphene_test_install_assembled true >/dev/null

  package_pattern="$(python3 -c 'import re,sys;print(re.escape(sys.argv[1]))' "$package_name")"
  stage_log="$(archphene_wait_log \
    "Wrapper template .* for $package_pattern|Wrapper assembly failed" "$timeout" \
    'ArchphenePackages:I ArchpheneRuntime:I ArchpheneManager:E AndroidRuntime:E *:S')"
  if [[ "$stage_log" == *'Wrapper assembly failed'* ]]; then
    diagnostic="$(grep -m1 -E 'Runtime data contains|SecurityException|UnsupportedOperationException|IllegalStateException' \
      <<<"$stage_log" || true)"
    archphene_die "$package_name wrapper assembly failed${diagnostic:+: $diagnostic}"
  fi
  archphene_wait_ui 'text="(?:Install|Update)"' \
    "candidate-$package_name-installer" 90
  archphene_tap_ui_pattern "$ARCHPHENE_UI" 'text="(?:Install|Update)"' \
    "$package_name installer confirmation"
  archphene_wait_log "activated generated wrapper $android_package" 180 \
    'ArchpheneRuntime:I ArchpheneManager:E AndroidRuntime:E *:S' >/dev/null
fi

installed_path="$(archphene_adb_run shell pm path "$android_package" \
  | head -n1 | sed 's/^package://;s/\r$//')"
[[ -n "$installed_path" ]] || archphene_die "$package_name wrapper is not installed"
local_apk="$tmp_dir/$package_name.apk"
archphene_adb_run pull "$installed_path" "$local_apk" >/dev/null
manifest="$(apkanalyzer manifest print "$local_apk")"
python3 -c '
import re, sys
toolkit, text = sys.argv[1], sys.stdin.read()
node = re.search(r"<meta-data\b[^>]*\bandroid:name=\"org\.archphene\.bridge\.toolkit\"[^>]*/?>", text)
if node is None or not re.search(rf"\bandroid:value=\"{re.escape(toolkit)}\"", node.group(0)):
    raise SystemExit(f"generated wrapper toolkit metadata is not {toolkit}")
' "$expected_toolkit" <<<"$manifest"

package_dump="$(archphene_adb_run shell dumpsys package "$android_package")"
[[ "$package_dump" == *'installerPackageName=org.archpheneos.manager'* ]] \
  || archphene_die 'wrapper was not installed by the Archphene manager'
activity="$(archphene_launcher "$android_package")"

archphene_adb_run shell am force-stop "$android_package"
archphene_adb_run logcat -c
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log "Acquired runtime pack lease [a-f0-9]{64} for $android_package" 60 \
  'ArchpheneRuntime:I ArchpheneLinuxApp:I AndroidRuntime:E *:S' >/dev/null
pid=
deadline=$((SECONDS + 30))
while ((SECONDS < deadline)); do
  pid="$(archphene_android_pid "$android_package" || true)"
  [[ -n "$pid" ]] && break
  sleep .5
done
[[ -n "$pid" ]] || archphene_die "$package_name Android process is missing"

wait_candidate_log() {
  local pattern="$1" seconds="$2" deadline=$((SECONDS + $2)) log
  while ((SECONDS < deadline)); do
    log="$(archphene_adb_run logcat -d -v brief --pid="$pid" \
      -s ArchpheneRuntime:V ArchpheneLinuxApp:V ArchpheneInput:V AndroidRuntime:E '*:S' \
      2>/dev/null || true)"
    if archphene_regex_contains "$log" "$pattern"; then
      printf '%s' "$log"
      return 0
    fi
    sleep .3
  done
  archphene_die "timed out waiting for candidate PID $pid log pattern: $pattern"
}

launch_log="$(wait_candidate_log \
  'Linux Wayland client connected to shared native compositor' 90)"
[[ "$launch_log" != *'FATAL EXCEPTION'* ]] || archphene_die "$package_name wrapper crashed"
mapped_log="$(wait_candidate_log 'mapped=true.*primary=true' 60)"
[[ "$mapped_log" != *'FATAL EXCEPTION'* ]] || archphene_die "$package_name wrapper crashed"

linux_pid=
deadline=$((SECONDS + 30))
while ((SECONDS < deadline)); do
  linux_pid="$(archphene_linux_loader_pid "$pid" || true)"
  [[ -n "$linux_pid" ]] && break
  sleep .5
done
[[ -n "$linux_pid" ]] || archphene_die "$package_name Linux loader is missing"

archphene_adb_run exec-out screencap -p \
  >"$artifact_prefix.png"
ui="$(archphene_capture_ui "candidate-$package_name-running" 2>/dev/null || true)"
if [[ -n "$ui" ]]; then
  [[ "$ui" == *'class="android.widget.ImageView"'* ]] \
    || archphene_die "$package_name did not expose a rendered compositor surface"
else
  # Continuously animating GTK/GPU surfaces can prevent uiautomator from
  # reaching its idle state. In that case the mapped Wayland window plus a
  # nontrivial Android screenshot provide the rendering evidence.
  screenshot_bytes="$(wc -c <"$artifact_prefix.png")"
  ((screenshot_bytes > 10000)) \
    || archphene_die "$package_name produced neither a UI hierarchy nor a screenshot"
  archphene_regex_contains "$mapped_log" 'mapped=true.*primary=true' \
    || archphene_die "$package_name did not map a primary Wayland window"
fi
archphene_adb_run logcat -d -v threadtime \
  -s ArchpheneRuntime:V ArchpheneLinuxApp:V ArchpheneInput:V AndroidRuntime:E '*:S' \
  >"$artifact_prefix-logcat.txt"

archphene_note "$package_name install/launch smoke passed on $serial: toolkit=$expected_toolkit, Android PID $pid, Linux PID $linux_pid, package $android_package. Package-specific compatibility tests are still required."
