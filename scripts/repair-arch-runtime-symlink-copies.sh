#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"
runtime_root=tooling/downloads/arch-curated-kcalc-x86_64/runtime-root; package_dir=tooling/downloads/arch-curated-kcalc-x86_64/packages; prefixes=(usr/lib/)
while (($#)); do case "$1" in --runtime-root) runtime_root="${2:?}"; shift 2;; --package-dir) package_dir="${2:?}"; shift 2;; --include-archive-prefix) prefixes+=("${2:?}"); shift 2;; -h|--help) echo "usage: $0 [options]"; exit 0;; *) archphene_die "unknown argument: $1";; esac; done
runtime_root="$(realpath "$ARCHPHENE_ROOT/$runtime_root")"; package_dir="$(realpath "$ARCHPHENE_ROOT/$package_dir")"
python3 - "$runtime_root" "$package_dir" "${prefixes[@]}" <<'PY'
import pathlib,re,shutil,subprocess,sys
root=pathlib.Path(sys.argv[1]); packages=pathlib.Path(sys.argv[2]); prefixes=sys.argv[3:]; links=[]
for pkg in packages.glob('*.pkg.tar.*'):
 if pkg.name.endswith('.sig'):continue
 text=subprocess.run(['tar','-tvf',str(pkg)],capture_output=True,text=True,check=True).stdout
 for line in text.splitlines():
  if not line.startswith('l') or ' -> ' not in line:continue
  match=re.search(r'\s([^\s]+)\s+->\s+(.+)$',line)
  if not match:continue
  archive,target=match.groups()
  if prefixes and not any(archive.startswith(p) for p in prefixes):continue
  link=root/archive.lstrip('/'); destination=root/target.lstrip('/') if target.startswith('/') else link.parent/target
  links.append((pkg.name,archive,target,link,destination))
mapping={str(x[3]):x for x in links}
def source(candidate):
 seen=set()
 for _ in range(32):
  if candidate.is_file():return candidate
  if str(candidate) in seen or str(candidate) not in mapping:return None
  seen.add(str(candidate)); candidate=mapping[str(candidate)][4]
 return None
created=0; missing=[]
for pkg,archive,target,link,destination in links:
 if link.exists():continue
 found=source(destination)
 if not found:missing.append((archive,target,pkg));continue
 link.parent.mkdir(parents=True,exist_ok=True); shutil.copy2(found,link); created+=1
missing_path=root.parent/'missing-symlink-copy-targets.tsv'
if missing:missing_path.write_text(''.join('\t'.join(x)+'\n' for x in missing))
print(f'RuntimeRoot: {root}\nPackageDir: {packages}\nLinkEntries: {len(links)}\nCreatedCopies: {created}\nMissingTargets: {len(missing)}\nMissingTargetsFile: {missing_path if missing else ""}')
PY
