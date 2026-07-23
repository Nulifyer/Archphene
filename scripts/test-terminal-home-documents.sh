#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib/android-test.sh"

serial=emulator-5554
skip_install=false
while (($#)); do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --skip-install) skip_install=true; shift ;;
    *) archphene_die "unknown argument: $1" ;;
  esac
done
archphene_test_init "$serial"

apk="$ARCHPHENE_ROOT/prototypes/archphene-terminal-app/out-linux/archphene-terminal.apk"
package=org.archpheneos.terminal
authority=$package.documents
if [[ "$skip_install" == false ]]; then
  archphene_require_file "$apk"
  archphene_adb_run install -r "$apk" >/dev/null
fi
archphene_adb_run shell pm path "$package" >/dev/null 2>&1 \
  || archphene_die 'Terminal is not installed; omit --skip-install or provide the build artifact'

dump="$(archphene_adb_run shell dumpsys package "$package")"
[[ "$dump" == *"$authority"* && "$dump" == *android.content.action.DOCUMENTS_PROVIDER* ]] \
  || archphene_die 'Terminal document provider is not registered'
archphene_adb_run shell run-as "$package" sh -c \
  "'mkdir -p files/terminal/home/Documents; printf archphene-terminal-home > files/terminal/home/Documents/provider-test.txt; printf private > files/terminal/home/.private-test'"

set +e
roots="$(archphene_adb_run shell content query \
  --uri "content://$authority/root" 2>&1)"
status=$?
set -e
direct_denied=false
if ((status)) || archphene_regex_contains "$roots" \
    'SecurityException|Permission Denial|Error while accessing provider'; then
  direct_denied=true
fi

if [[ "$direct_denied" == true ]]; then
  archphene_adb_run shell am start -W -a android.intent.action.OPEN_DOCUMENT \
    -c android.intent.category.OPENABLE -t text/plain \
    --eu android.provider.extra.INITIAL_URI \
    "content://$authority/root/archphene-terminal-home" >/dev/null
  archphene_wait_ui 'text="Archphene Home"' terminal-home-ui 15
  ui="$ARCHPHENE_UI"
  [[ "$ui" == *'Archphene Home'* && "$ui" == *Documents* \
      && "$ui" != *private-test* ]] \
    || archphene_die 'SAF-only home root filtering is incorrect'
else
  [[ "$roots" == *archphene-terminal-home* && "$roots" == *'Archphene Home'* ]] \
    || archphene_die 'document root unavailable'
  children="$(archphene_adb_run shell content query \
    --uri "content://$authority/document/home/children")"
  [[ "$children" == *Documents* && "$children" != *private-test* ]] \
    || archphene_die 'home filtering incorrect'
  file="$(archphene_adb_run shell content read \
    --uri "content://$authority/document/home%2FDocuments%2Fprovider-test.txt")"
  [[ "$file" == *archphene-terminal-home* ]] \
    || archphene_die 'file content unavailable'
fi

archphene_note "Terminal Storage Access Framework home passed on $serial: provider registration, SAF access, visible Documents, and private-dotfile filtering validated."
