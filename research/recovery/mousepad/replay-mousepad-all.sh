#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/../../.." && pwd)"
max_entries=10000; log_failures=false
while (($#)); do case "$1" in --max-entries) max_entries="${2:?}"; shift 2;; --log-failures) log_failures=true; shift;; -h|--help) echo "usage: $0 [--max-entries N] [--log-failures]"; exit 0;; *) echo "unknown argument: $1" >&2; exit 1;; esac; done
cd "$root"
python3 - "$max_entries" "$log_failures" "$(dirname "$0")/replay-mousepad-all.json" <<'PY'
import json,pathlib,shutil,subprocess,sys
limit=int(sys.argv[1]); verbose=sys.argv[2]=='true'; data=json.loads(pathlib.Path(sys.argv[3]).read_text())
target=pathlib.Path('prototypes/mousepad-android-app/src/org/archphene/linux/kcalc/MainActivity.java'); target.parent.mkdir(parents=True,exist_ok=True)
for old in target.parent.glob('MainActivity$*.java'): old.unlink()
shutil.copyfile('prototypes/kcalc-android-app/src/org/archphene/linux/kcalc/MainActivity.java',target)
def apply_patch(text,stamp):
 content=target.read_text().replace('\r\n','\n'); active=False; old=[]; new=[]
 def hunk():
  nonlocal content,old,new
  if not old and not new:return
  a='\n'.join(old); b='\n'.join(new); i=content.find(a)
  if i<0: raise RuntimeError(f'patch hunk missing {stamp}')
  content=content[:i]+b+content[i+len(a):]; old=[]; new=[]
 for line in text.replace('\r\n','\n').split('\n'):
  if line.startswith('*** Update File: '):
   if active:hunk()
   active=line[17:]==str(target); old=[]; new=[]; continue
  if line.startswith('@@'):
   if active:hunk()
   continue
  if not active:continue
  if line.startswith('+'):new.append(line[1:])
  elif line.startswith('-'):old.append(line[1:])
  elif line.startswith(' '):old.append(line[1:]);new.append(line[1:])
 if active:hunk()
 target.write_text(content)
ok=failed=patch_ok=0
for index,entry in enumerate(data['replay'][:limit],1):
 try:
  if entry['kind']=='patch': apply_patch(str(entry['value']),str(entry['timestamp'])); patch_ok+=1
  else:
   # The recovery log contains archived host-shell snippets. They are retained as
   # data for provenance and skipped by the Linux replay.
   raise RuntimeError('historical non-portable shell entry skipped')
  ok+=1
  if verbose: print('ok',index,entry['timestamp'],entry['kind'])
 except Exception as exc:
  failed+=1
  if verbose: print('FAIL',index,entry['timestamp'],entry['kind'],exc)
print(f'RESULT ok={ok} failed={failed} patchOk={patch_ok} length={target.stat().st_size} lines={len(target.read_text().splitlines())}')
PY
