#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"
android_abi=x86_64; serial=emulator-5554
while (($#)); do case "$1" in --android-abi) android_abi="${2:?}"; shift 2;; --serial) serial="${2:?}"; shift 2;; -h|--help) echo "usage: $0 [--android-abi x86_64|arm64-v8a] [--serial SERIAL]"; exit 0;; *) archphene_die "unknown argument: $1";; esac; done
apk="$ARCHPHENE_ROOT/prototypes/native-compositor-probe/out-$android_abi/archphene-compositor-probe.apk"; archphene_require_file "$apk"; archphene_init_adb "$serial"
archphene_adb_run get-state >/dev/null; archphene_adb_run shell input keyevent KEYCODE_WAKEUP; archphene_adb_run shell wm dismiss-keyguard || true; archphene_adb_run logcat -c
"$ARCHPHENE_SCRIPTS_DIR/install-apk.sh" --apk "$apk" --serial "$serial" --package org.archphene.compositorprobe
archphene_adb_run shell am force-stop org.archphene.compositorprobe; archphene_adb_run logcat -c; archphene_adb_run shell am start -W -n org.archphene.compositorprobe/.MainActivity >/dev/null; archphene_adb_run shell wm dismiss-keyguard || true
key_sent=false; tap_sent=false; scroll_sent=false; touch_sent=false; deadline=$((SECONDS + 60)); completion='registry, Android bitmap, xdg toplevel, keyboard input, damage-batched buffer scale/transform, viewporter/fractional scaling, Choreographer-paced frames, MotionEvent pointer/wheel/touch input, cursor surfaces, pointer gestures, nested popup grabs, synchronized subsurface trees, committed parent geometry, demand-driven clipboard, and Android InputConnection UTF-8 text-input v3 lifecycle complete'
while ((SECONDS < deadline)); do
  sleep 0.5; output="$(archphene_adb_run logcat -d -s ArchpheneCompositorProbe:I '*:S')"
  if [[ "$key_sent" == false && "$output" == *'keyboard target ready'* ]]; then archphene_adb_run shell input keyevent KEYCODE_DPAD_LEFT; key_sent=true; fi
  if [[ "$tap_sent" == false && "$output" =~ pointer\ target\ screen=([0-9]+),([0-9]+) ]]; then archphene_adb_run shell input tap "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}"; tap_sent=true; fi
  if [[ "$scroll_sent" == false && "$output" =~ scroll\ target\ ready\ screen=([0-9]+),([0-9]+) ]]; then archphene_adb_run shell input mouse scroll "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}" --axis VSCROLL,2 --axis HSCROLL,1; scroll_sent=true; fi
  if [[ "$touch_sent" == false && "$output" =~ touch\ target\ screen=([0-9]+),([0-9]+) ]]; then x="${BASH_REMATCH[1]}"; y="${BASH_REMATCH[2]}"; archphene_adb_run shell input swipe "$x" "$y" "$((x+20))" "$y" 300; touch_sent=true; fi
  [[ "$output" != *'Native compositor probe failed'* ]] || archphene_die "native compositor probe reported failure: $output"
  if [[ "$output" == *"$completion"* ]]; then archphene_note "Native compositor Android MotionEvent probe passed on $serial ($android_abi)."; exit 0; fi
done
archphene_die "timed out waiting for native compositor result on $serial ($android_abi)"

