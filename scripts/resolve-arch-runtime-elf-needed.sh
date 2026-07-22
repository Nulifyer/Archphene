#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"
runtime_root=tooling/downloads/arch-curated-kcalc-x86_64/runtime-root
starts=(usr/bin/kcalc)
readelf_bin=
while (($#)); do
  case "$1" in
    --runtime-root) runtime_root="${2:?}"; shift 2;; --start) if [[ "${starts[*]}" == usr/bin/kcalc ]]; then starts=(); fi; starts+=("${2:?}"); shift 2;; --readelf) readelf_bin="${2:?}"; shift 2;;
    -h|--help) echo "usage: $0 [--runtime-root PATH] [--start RELATIVE]... [--readelf PATH]"; exit 0;; *) archphene_die "unknown argument: $1";;
  esac
done
[[ "$runtime_root" == /* ]] || runtime_root="$ARCHPHENE_ROOT/$runtime_root"; runtime_root="$(realpath "$runtime_root")"
if [[ -z "$readelf_bin" ]]; then
  if command -v readelf >/dev/null; then readelf_bin="$(command -v readelf)"; else sdk="$(archphene_android_sdk)"; readelf_bin="$sdk/ndk/29.0.14206865/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"; fi
fi
archphene_require_file "$readelf_bin"
declare -A provided visited resolved missing
while IFS= read -r -d '' file; do name="$(basename "$file")"; [[ -v "provided[$name]" ]] || provided[$name]="$file"; done < <(find "$runtime_root/usr/lib" -maxdepth 1 -type f -print0)
queue=()
for entry in "${starts[@]}"; do path="$runtime_root/${entry#/}"; archphene_require_file "$path"; queue+=("$(realpath "$path")"); done
edges=(); index=0
while ((index < ${#queue[@]})); do
  object="${queue[index]}"; ((index += 1)); [[ -v "visited[$object]" ]] && continue; visited[$object]=1
  while IFS= read -r name; do
    [[ -n "$name" ]] || continue
    from="${object#"$runtime_root/"}"
    if [[ -v "provided[$name]" ]]; then target="${provided[$name]}"; resolved[$name]="$target"; queue+=("$target"); edges+=("$from"$'\t'"$name"$'\t'"${target#"$runtime_root/"}");
    else missing[$name]=1; edges+=("$from"$'\t'"$name"$'\t'); fi
  done < <("$readelf_bin" -d "$object" 2>/dev/null | sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p' || true)
done
out_dir="$(dirname "$runtime_root")"; resolved_path="$out_dir/elf-needed-resolved.tsv"; missing_path="$out_dir/elf-needed-missing.txt"; edges_path="$out_dir/elf-needed-edges.tsv"
: > "$resolved_path"; : > "$missing_path"; printf '%s\n' "${edges[@]}" > "$edges_path"
for name in "${!resolved[@]}"; do printf '%s\t%s\n' "$name" "${resolved[$name]#"$runtime_root/"}"; done | sort > "$resolved_path"
printf '%s\n' "${!missing[@]}" | sed '/^$/d' | sort > "$missing_path"
archphene_note "RuntimeRoot: $runtime_root"; archphene_note "VisitedObjects: ${#visited[@]}"; archphene_note "ResolvedLibraries: ${#resolved[@]}"; archphene_note "MissingLibraries: ${#missing[@]}"; archphene_note "ResolvedFile: $resolved_path"; archphene_note "MissingFile: $missing_path"; archphene_note "EdgesFile: $edges_path"

