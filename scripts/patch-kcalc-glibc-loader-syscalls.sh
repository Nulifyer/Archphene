#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"
python3 - "$ARCHPHENE_ROOT/prototypes/kcalc-android-app/lib/x86_64" <<'PY'
import pathlib,sys
root=pathlib.Path(sys.argv[1])
def patch(path, changes):
 if not path.is_file(): raise SystemExit(f'missing loader target: {path}')
 backup=path.with_name(path.name+'.orig')
 if not backup.exists(): backup.write_bytes(path.read_bytes())
 data=bytearray(path.read_bytes())
 for name,offset,expected,replacement in changes:
  found=bytes(data[offset:offset+len(expected)])
  if found==replacement: print(f'{path.name} already patched: {name}'); continue
  if found!=expected: raise SystemExit(f'{path.name} unexpected bytes at 0x{offset:x}: found {found.hex(" ").upper()} expected {expected.hex(" ").upper()}')
  data[offset:offset+len(replacement)]=replacement; print(f'{path.name} patched: {name} at 0x{offset:x}')
 path.write_bytes(data)
loader=[('startup set_robust_list syscall -> success',0x140d8,b'\x0f\x05',b'\x31\xc0'),('startup rseq syscall -> failure path',0x1416d,b'\x0f\x05',b'\xf7\xd8')]
for name in ('libarchphene_ld.so','ld-linux-x86-64.so.2','libld.so.2'): patch(root/name,loader)
patch(root/'libc.so.6',[
 ('startup rt_sigprocmask syscall -> success',0x27765,b'\x0f\x05',b'\x31\xc0'),
 ('libc pthread startup set_robust_list syscall -> success',0x974cd,b'\x0f\x05',b'\x31\xc0'),
 ('libc pthread startup rseq syscall -> failure path',0x977b3,b'\x0f\x05',b'\xf7\xd8'),
 ('libc fork child set_robust_list syscall -> success',0xe56dc,b'\x0f\x05',b'\x31\xc0')])
PY
