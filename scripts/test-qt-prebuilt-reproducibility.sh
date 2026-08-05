#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
container_cli="${CONTAINER_CLI:-podman}"
image=localhost/archphene-qt-prebuilt-reproducibility:qt6.11.1
mkdir -p "$root/tooling/build"
base="$(mktemp -d "$root/tooling/build/qt-prebuilt-reproducibility.XXXXXX")"
active=()
cleanup() {
  local worktree
  for worktree in "${active[@]}"; do
    git -C "$root" worktree remove --force "$worktree" 2>/dev/null || true
  done
  rm -rf "$base"
}
trap cleanup EXIT

"$container_cli" build -f "$root/containers/qt-platform-theme.Containerfile" \
  -t "$image" "$root/containers"

specifications=(
  'platform:ed5dd11ca1f94c2651121e3d131bea7917991035:archphene-qt-platform-theme.pro:libarchphene_qt_platform_theme.so:49ca077ebc9e9a6e40619dc0821e790e5e22ce4ac7171f94ab7f7501d0198acf'
  'style:88340f520f2961381e33bb3052a6e071a0ce755c:archphene-qt-style.pro:libarchphene_qt_style.so:d05f52c6d648887987b37fe2bb49b472f16478bbff95de189ac82d0accaf9210'
  'kde-config:931ef9131f9e3f87b60430887b06d99449f80d9d:archphene-kde-config.pro:libarchphene_kde_config.so:e43c899cac04076f38014fc335f8c27278b859dd66bf996c92bf71934a7cdeef'
)
for specification in "${specifications[@]}"; do
  IFS=: read -r name commit project library expected <<<"$specification"
  worktree="$base/$name"
  git -C "$root" worktree add --quiet --detach "$worktree" "$commit"
  active+=("$worktree")
  "$container_cli" run --rm \
    -v "$worktree:/workspace" -w /tmp "$image" bash -lc \
    "mkdir build && cd build && \
     qmake6 /workspace/native/archphene-qt-platform-theme/$project && \
     make -j2 >/dev/null && cp $library /workspace/rebuilt.so"
  rebuilt="$worktree/rebuilt.so"
  actual="$(sha256sum "$rebuilt" | cut -d ' ' -f1)"
  [[ "$actual" == "$expected" ]] || {
    echo "Qt $name rebuild changed: expected $expected, got $actual" >&2
    exit 1
  }
  cmp "$rebuilt" "$root/prebuilt/qt-bridge/x86_64/$library"
  git -C "$root" worktree remove --force "$worktree"
  active=("${active[@]:0:${#active[@]}-1}")
done

echo "Qt x86_64 prebuilt reproducibility passed: three historical source commits rebuilt byte-for-byte."
