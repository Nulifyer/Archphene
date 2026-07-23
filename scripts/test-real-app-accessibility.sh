#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=
target=
profile=KCalc
probe_apk=
adb_path=
timeout=30
artifact_dir=
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --target-package) target="${2:?}"; shift 2 ;;
    --profile) profile="${2:?}"; shift 2 ;;
    --probe-apk) probe_apk="${2:?}"; shift 2 ;;
    --adb-path) adb_path="${2:?}"; shift 2 ;;
    --timeout-seconds) timeout="${2:?}"; shift 2 ;;
    --artifact-dir) artifact_dir="${2:?}"; shift 2 ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" && -n "$target" ]] \
  || archphene_die '--serial and --target-package are required'
archphene_validate_choice "$profile" profile KCalc Kate Mousepad TextEditor
profile_label="$profile"
[[ "$profile" != TextEditor ]] || profile_label='Text Editor'
[[ -z "$adb_path" ]] || ARCHPHENE_ADB="$adb_path"
archphene_test_init "$serial"

probe=org.archphene.accessibilityprobe
service="$probe/org.archphene.bridge.ProbeAccessibilityService"
if [[ -n "$probe_apk" ]]; then
  archphene_require_file "$probe_apk"
  archphene_adb_run install -r "$probe_apk" >/dev/null
else
  archphene_adb_run shell pm path "$probe" >/dev/null 2>&1 \
    || archphene_die 'the test AccessibilityService is not installed; pass --probe-apk only when installation is explicitly approved'
fi
archphene_adb_run shell pm path "$target" >/dev/null

safe_target="${target//[^A-Za-z0-9._-]/_}"
tree_name="framework-accessibility-tree-$safe_target.txt"
tree_file="files/$tree_name"
command_file=files/framework-accessibility-command.txt
response_file=files/framework-accessibility-response.txt
safe_serial="${serial//[^A-Za-z0-9._-]/_}"
artifact_dir="${artifact_dir:-$ARCHPHENE_ROOT/tooling/artifacts/visual-audit/$safe_serial/${profile,,}-accessibility}"
mkdir -p "$artifact_dir"
text_editor_state=files/linux-home/.local/share/org.gnome.TextEditor
text_editor_backup="files/archphene-accessibility-text-editor-state-$safe_serial"
had_text_editor_state=false
old_services="$(archphene_adb_run shell settings get secure enabled_accessibility_services | tr -d '\r')"
old_enabled="$(archphene_adb_run shell settings get secure accessibility_enabled | tr -d '\r')"
restore() {
  local restore_failed=false
  archphene_adb_run shell am force-stop "$probe" >/dev/null 2>&1 || true
  if [[ "$old_services" == null || -z "$old_services" ]]; then
    archphene_adb_run shell settings delete secure enabled_accessibility_services \
      >/dev/null 2>&1 || restore_failed=true
  else
    archphene_adb_run shell settings put secure enabled_accessibility_services \
      "$old_services" >/dev/null 2>&1 || restore_failed=true
  fi
  if [[ "$old_enabled" == null || -z "$old_enabled" ]]; then
    archphene_adb_run shell settings delete secure accessibility_enabled \
      >/dev/null 2>&1 || restore_failed=true
  else
    archphene_adb_run shell settings put secure accessibility_enabled \
      "$old_enabled" >/dev/null 2>&1 || restore_failed=true
  fi
  if [[ "$profile" == TextEditor ]]; then
    archphene_adb_run shell am force-stop "$target" >/dev/null 2>&1 \
      || restore_failed=true
    archphene_adb_run shell run-as "$target" rm -rf "$text_editor_state" \
      >/dev/null 2>&1 || restore_failed=true
    if [[ "$had_text_editor_state" == true ]]; then
      archphene_adb_run shell run-as "$target" mkdir -p \
        files/linux-home/.local/share >/dev/null 2>&1 || restore_failed=true
      archphene_adb_run shell run-as "$target" cp -a \
        "$text_editor_backup" "$text_editor_state" >/dev/null 2>&1 \
        || restore_failed=true
    fi
    archphene_adb_run shell run-as "$target" rm -rf "$text_editor_backup" \
      >/dev/null 2>&1 || restore_failed=true
  fi
  if [[ "$restore_failed" == true ]]; then
    echo "error: could not restore $profile accessibility test state" >&2
    return 1
  fi
}
trap restore EXIT
if [[ "$profile" == TextEditor ]]; then
  archphene_adb_run shell am force-stop "$target"
  archphene_adb_run shell run-as "$target" rm -rf "$text_editor_backup"
  if archphene_adb_run shell run-as "$target" test -e "$text_editor_state"; then
    archphene_adb_run shell run-as "$target" cp -a \
      "$text_editor_state" "$text_editor_backup"
    had_text_editor_state=true
  fi
  # Start from one deterministic blank document. The exact prior session is
  # restored by the EXIT trap, including when a semantic assertion fails.
  archphene_adb_run shell run-as "$target" rm -rf "$text_editor_state"
fi

get_target_tree() {
  archphene_adb_run shell run-as "$probe" cat "$tree_file" 2>/dev/null \
    | tr -d '\r' || true
}

wait_target_tree() {
  local absent="$1"
  shift
  local deadline tree expected present
  deadline=$((SECONDS + timeout))
  while ((SECONDS < deadline)); do
    tree="$(get_target_tree)"
    present=true
    for expected in "$@"; do
      [[ "$tree" == *"$expected"* ]] || present=false
    done
    if [[ "$present" == true && ( -z "$absent" || "$tree" != *"$absent"* ) ]]; then
      printf '%s' "$tree"
      return 0
    fi
    sleep 0.2
  done
  printf '%s\n' "$tree" >"$artifact_dir/timeout-tree.txt"
  archphene_die "timed out waiting for $profile accessibility tree: $* (absent: $absent)"
}

base64url() {
  printf '%s' "$1" | base64 -w0 | tr '+/' '-_' | tr -d '='
}

wait_target_action() {
  local selector="$1" required="$2" deadline tree
  deadline=$((SECONDS + timeout))
  while ((SECONDS < deadline)); do
    tree="$(get_target_tree)"
    if python3 -c '
import sys
selector, required = sys.argv[1], int(sys.argv[2])
for line in sys.stdin:
    if not line.startswith("NODE|"):
        continue
    fields = line.rstrip("\r\n").split("|")
    if len(fields) < 11 or fields[6] != "true":
        continue
    if selector not in (fields[3], fields[4]):
        continue
    try:
        actions = int(fields[-1])
    except ValueError:
        continue
    if actions & required:
        raise SystemExit(0)
raise SystemExit(1)
' "$selector" "$required" <<<"$tree"; then
      return 0
    fi
    sleep 0.1
  done
  archphene_die "timed out waiting for $profile accessibility action: $selector"
}

invoke_accessibility_action() {
  local selector="$1" action="${2:-click}" value="${3:-}" required id payload response deadline
  case "$action" in
    click) required=16 ;;
    focus) required=1 ;;
    scroll-forward) required=4096 ;;
    scroll-backward) required=8192 ;;
    set-text) required=2097152 ;;
    *) archphene_die "unsupported accessibility action: $action" ;;
  esac
  wait_target_action "$selector" "$required"
  id="real-$(printf '%012x' "$((RANDOM << 16 | RANDOM))")"
  payload="$id"$'\t'"$target"$'\t'"$action"$'\t'"$(base64url "$selector")"$'\t'"$(base64url "$value")"
  archphene_adb_run shell run-as "$probe" rm -f "$response_file"
  printf '%s' "$payload" | "$ARCHPHENE_ADB" "${ARCHPHENE_ADB_ARGS[@]}" \
    shell run-as "$probe" tee "$command_file" >/dev/null
  deadline=$((SECONDS + timeout))
  while ((SECONDS < deadline)); do
    response="$(archphene_adb_run shell run-as "$probe" \
      cat "$response_file" 2>/dev/null | tr -d '\r\n' || true)"
    if [[ "$response" == "$id"$'\t'* ]]; then
      [[ "$response" == "$id"$'\t'OK ]] \
        || archphene_die "accessibility action '$action $selector' was rejected: $response"
      return 0
    fi
    sleep 0.1
  done
  archphene_die "timed out waiting for accessibility action '$action $selector'"
}

assert_current_bounds() {
  local tree="$1" temporary
  temporary="$artifact_dir/current-tree.txt"
  printf '%s\n' "$tree" >"$temporary"
  python3 "$ARCHPHENE_SCRIPTS_DIR/lib/accessibility-tree-check.py" \
    "$temporary" --expected-text "$profile_label" \
    --display-width "$width" --display-height "$height" >/dev/null
}

test_kcalc() {
  local tree selector
  tree="$(wait_target_tree '' '|KCalc|' '|Clear|' '|Equals|')"
  if [[ "$tree" == *'|Close|'* ]]; then
    invoke_accessibility_action Close
    wait_target_tree '|Close|' '|KCalc|' >/dev/null
  fi
  invoke_accessibility_action Clear
  tree="$(wait_target_tree '' '|KCalc|' '|Clear|' '|Equals|')"
  assert_current_bounds "$tree"
  for selector in Six Add Five Equals; do
    invoke_accessibility_action "$selector"
  done
  tree="$(wait_target_tree '' '|11|')"
  assert_current_bounds "$tree"
  invoke_accessibility_action File
  tree="$(wait_target_tree '' '|Quit|')"
  assert_current_bounds "$tree"
  invoke_accessibility_action Edit
  tree="$(wait_target_tree '' '|Undo|' '|Redo|' '|Copy|' '|Paste|')"
  assert_current_bounds "$tree"
  invoke_accessibility_action Settings
  tree="$(wait_target_tree '' '|Simple Mode|' '|Science Mode|' '|Configure KCalc')"
  assert_current_bounds "$tree"
  invoke_accessibility_action Help
  tree="$(wait_target_tree '' '|KCalc Handbook|' '|Report Bug' '|About KCalc|')"
  assert_current_bounds "$tree"
  invoke_accessibility_action 'About KCalc'
  tree="$(wait_target_tree '' '|About KCalc|' '|Close|')"
  assert_current_bounds "$tree"
  invoke_accessibility_action Close
  wait_target_tree '|Close|' '|KCalc|' >/dev/null
}

test_mousepad() {
  local tree
  tree="$(wait_target_tree '' '|Mousepad|')"
  if [[ "$tree" == *'previous session did not end normally'* ]]; then
    invoke_accessibility_action No
  fi
  tree="$(wait_target_tree '' '|Untitled 1 - Mousepad|' '|File|File menu|' '|Edit|Edit menu|')"
  assert_current_bounds "$tree"
  invoke_accessibility_action File
  tree="$(wait_target_tree '' '|Open a file|' '|Save current document as another file|')"
  assert_current_bounds "$tree"
  invoke_accessibility_action Edit
  tree="$(wait_target_tree '|Open a file|' '|Paste the clipboard|' '|Show the preferences dialog|')"
  assert_current_bounds "$tree"
  invoke_accessibility_action 'Show the preferences dialog'
  tree="$(wait_target_tree '' '|Mousepad Preferences|')"
  assert_current_bounds "$tree"
  archphene_adb_run shell input keyevent 4
  wait_target_tree '|Mousepad Preferences|' '|Untitled 1 - Mousepad|' >/dev/null
  invoke_accessibility_action File
  wait_target_tree '' '|Open a file|' >/dev/null
  invoke_accessibility_action 'Open a file'
  tree="$(wait_target_tree '' '|Open File|')"
  assert_current_bounds "$tree"
  archphene_adb_run shell input keyevent 4
  sleep 0.5
  if [[ "$(get_target_tree)" == *'|Open File|'* ]]; then
    archphene_adb_run shell input keyevent 4
  fi
  wait_target_tree '|Open File|' '|Untitled 1 - Mousepad|' >/dev/null
}

test_kate() {
  local tree
  tree="$(wait_target_tree '' '|Welcome to Kate|' '|New File|' '|Open File...|')"
  assert_current_bounds "$tree"
  invoke_accessibility_action File
  tree="$(wait_target_tree '' '|New|' '|Open…|' '|Quit|')"
  assert_current_bounds "$tree"
  invoke_accessibility_action View
  tree="$(wait_target_tree '|Quit|' '|Split View|' '|Tool Views|')"
  assert_current_bounds "$tree"
  invoke_accessibility_action Sessions
  tree="$(wait_target_tree '' '|New Session|' '|Manage Sessions...|')"
  assert_current_bounds "$tree"
  invoke_accessibility_action 'Manage Sessions...'
  tree="$(wait_target_tree '' '|Manage Sessions — Kate|' '|Filter Sessions|')"
  assert_current_bounds "$tree"
  invoke_accessibility_action Close
  wait_target_tree '|Manage Sessions — Kate|' '|Welcome to Kate|' >/dev/null
  archphene_adb_run shell input keyboard keycombination \
    KEYCODE_CTRL_LEFT KEYCODE_O
  tree="$(wait_target_tree '' '|Open File — Kate|')"
  assert_current_bounds "$tree"
  invoke_accessibility_action Cancel
  wait_target_tree '|Open File — Kate|' '|Welcome to Kate|' >/dev/null
}

test_text_editor() {
  local tree new_documents
  tree="$(wait_target_tree '' '|Text Editor|' '|Open|' '|New Tab|' '|Main Menu|')"
  assert_current_bounds "$tree"
  invoke_accessibility_action 'New Tab'
  tree="$(wait_target_tree '' '|Text Editor|' '|New Document|')"
  new_documents="$(grep -c '|New Document|' <<<"$tree" || true)"
  ((new_documents >= 2)) \
    || archphene_die "Text Editor New Tab did not expose a second document (nodes=$new_documents)"
  assert_current_bounds "$tree"

  invoke_accessibility_action 'Main Menu'
  tree="$(wait_target_tree '' '|Preferences|' '|Keyboard Shortcuts|' '|About Text Editor|')"
  assert_current_bounds "$tree"
  invoke_accessibility_action 'Preferences'
  tree="$(wait_target_tree '' '|Preferences|' '|Highlight Current Line|' '|Indentation|')"
  assert_current_bounds "$tree"
  archphene_adb_run shell input keyevent KEYCODE_ESCAPE
  wait_target_tree '|Highlight Current Line|' '|Text Editor|' '|Main Menu|' >/dev/null
  # The Open split button's recent-document popover and Android SAF handoff
  # are covered by test-mousepad-android-document-workflow.sh. Keep this test
  # focused on the target app's exported GTK semantics.
}

archphene_adb_run shell am start -W -n \
  "$probe/org.archphene.bridge.AccessibilityProbeActivity" >/dev/null
archphene_adb_run shell run-as "$probe" rm -f \
  "$tree_file" "$command_file" "$response_file"
archphene_adb_run shell settings put secure enabled_accessibility_services "$service"
archphene_adb_run shell settings put secure accessibility_enabled 1
archphene_adb_run logcat -c
activity="$(archphene_launcher "$target")"
archphene_adb_run shell am force-stop "$target"
archphene_adb_run shell am start -W -n "$activity" >/dev/null
archphene_wait_log 'mapped=true' "$timeout" 'ArchpheneInput:V AndroidRuntime:E *:S' >/dev/null

read -r width height <<<"$(archphene_adb_run shell wm size \
  | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -n1)"
case "$profile" in
  KCalc) test_kcalc ;;
  Kate) test_kate ;;
  Mousepad) test_mousepad ;;
  TextEditor) test_text_editor ;;
esac

deadline=$((SECONDS + timeout))
tree=
node_count=0
while ((SECONDS < deadline)); do
  tree="$(get_target_tree)"
  node_count="$(grep -c '^NODE|' <<<"$tree" || true)"
  if ((node_count >= 6)) && [[ "$tree" == *"$profile_label"* ]]; then
    break
  fi
  sleep .25
done
((node_count >= 6)) && [[ "$tree" == *"$profile_label"* ]] \
  || archphene_die "real $profile semantic tree did not settle (nodes=$node_count)"
printf '%s\n' "$tree" >"$artifact_dir/accessibility-tree.txt"
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/accessibility-tree-check.py" \
  "$artifact_dir/accessibility-tree.txt" --expected-text "$profile_label" \
  --display-width "$width" --display-height "$height"

log="$(archphene_adb_run logcat -d -s ArchpheneAccessibilityProbe:I ArchpheneInput:V AndroidRuntime:E '*:S')"
printf '%s\n' "$log" >"$artifact_dir/logcat.txt"
[[ "$log" != *'FATAL EXCEPTION'* ]] || archphene_die 'target or accessibility probe crashed'
python3 "$ARCHPHENE_SCRIPTS_DIR/lib/visual-manifest.py" \
  "$artifact_dir/manifest.json" \
  --field "serial=$serial" --field "package=$target" --field "app=$profile" \
  --field 'state=real Android semantic tree' --field 'toolkit=AT-SPI' \
  --field "display=${width}x${height}" \
  --artifact "$artifact_dir/accessibility-tree.txt" \
  --artifact "$artifact_dir/logcat.txt"
archphene_note "Real $profile accessibility passed with exported roles, states, actions, and bounded nodes. Evidence: $artifact_dir"
