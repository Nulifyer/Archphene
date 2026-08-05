# Licensing and release notices

Archphene-owned source is licensed under the repository's
[MIT License](../LICENSE). Rust workspace metadata, the Android manager,
Builder, and generated app-shell source use that same project license.

## Packaged notices

Current release builds verify these exact notice assets:

| Artifact | Packaged notice | Applies to |
|---|---|---|
| Manager | `assets/licenses/Archphene-MIT.txt` | Archphene-owned manager and native source |
| Manager | `assets/licenses/Apache-2.0.txt` and `AndroidApkSig-NOTICE.txt` | Android `apksig` 9.3.0 |
| Manager | `assets/licenses/JetBrainsMonoNerdFont-OFL.txt` | JetBrains Mono Nerd Font |
| Builder | `assets/licenses/Archphene-MIT.txt` | Archphene-owned Builder source |
| Generated app shell | `assets/licenses/Archphene-MIT.txt` | Archphene-owned app-shell source |

The release verifier compares every listed asset byte-for-byte with its reviewed
repository source. The production APK SBOMs inventory the files and bind them to
the signed APK digest, release version, and source commit.

## Rust component inventory

Release CI derives a separate manager and Builder dependency closure for each
Android Rust target. It uses `cargo metadata --offline --locked
--filter-platform`, follows only normal runtime dependencies from the fixed
workspace roots, and joins every registry package to its `Cargo.lock` SHA-256.
`scripts/release-rust-components.py` rejects missing licenses, unlocked or
non-registry sources, duplicate identities, and noncanonical output.

Each APK's SPDX 2.3 SBOM includes those exact Cargo packages with name, version,
package URL, registry archive checksum, and the package's declared license
expression. Legacy Cargo `MIT/Apache-2.0`-style alternatives are retained in a
comment and normalized to SPDX `OR` syntax. The APK-to-package relationship is
recorded as `STATIC_LINK`. `licenseConcluded` remains `NOASSERTION`: recording
upstream metadata does not substitute for reviewing and packaging required
license and notice texts.

Each APK is accompanied by `<apk-name>.rust-licenses.zip` and its checksum. The
deterministic bundle contains its exact component manifest plus every
`LICENSE*`, `LICENCE*`, `COPYING*`, `UNLICENSE*`, `NOTICE*`, and `COPYRIGHT*`
file found in each checksum-locked Cargo source package, including nested
vendored notices. Its index binds every included file to a SHA-256, and the SPDX
document records the bundle's filename and SHA-256. Generation fails when a
component has no candidate license file. This closes Rust license-text
collection, but it does not make an unsupported license conclusion.

## Separate package licenses

Packages installed later through pacman or the reviewed AUR path are not
relicensed by Archphene. They enter the shared Arch root under their upstream
package licenses and remain separate from Archphene's Android application
license.

## Release audit

[`release/native-components.json`](../release/native-components.json) is the
canonical evidence-bound inventory for the 14 non-Rust third-party components
or component sets currently entering the manager or Builder. It records source
locations, pins, declared licenses where reviewed, exact repository evidence,
and the validation model. `scripts/release-native-audit.py` rejects missing
components, stale evidence, malformed blockers, and unreviewed additions.

Ten records are verified: patched glibc, D-Bus, Mbed TLS, Mesa, PipeWire,
libepoxy, virglrenderer, both Qt bridges, and the Termux Pulse closure.
Release CI checks each source archive SHA-256 or Git commit and publishes every
discovered license, notice, and copyright file in
`Archphene-native-licenses-<version>.zip`. The deterministic archive embeds the
canonical manifest and a per-file checksum index.

The release also supplies `Archphene-glibc-source-<version>.zip`. It contains a
deterministic archive of the exact pristine glibc Git tree, the applied
Archphene patch, both architecture build scripts, the AArch64 container recipe,
and an indexed checksum for every build-control file.

The release supplies `Archphene-termux-pulse-source-<version>.zip` for all 28
Termux binary packages. It includes each exact historical package recipe and
build-framework snapshot, every checksum-pinned upstream source archive,
recipe-contained Android source, and indexed license files for all 14 packages.

All 14 records are verified. Arch package-runtime transactions intentionally
track the current signed repositories and record their resolved versions in each
build artifact. GTK compatibility artifacts use checksum verification and the
maintained compatibility source/build path; exact historical reconstruction is
not a product requirement. The tag workflow still runs `--require-complete`
before creating a GitHub draft so newly introduced audit blockers fail closed.
SPDX file licenses and package license conclusions remain `NOASSERTION` where no
file-level conclusion was made.

Run the current consistency gate with:

```bash
python3 scripts/test-release-license-contract.py
python3 scripts/test-release-rust-components.py
python3 scripts/test-release-rust-licenses.py
python3 scripts/test-release-sbom.py
python3 scripts/test-release-native-audit.py
python3 scripts/test-release-native-licenses.py
python3 scripts/test-release-glibc-source.py
python3 scripts/test-qt-prebuilt-provenance.py
bash scripts/test-qt-prebuilt-reproducibility.sh
python3 scripts/release-termux-pulse-audit.py \
  --repository tooling/external/termux-packages-release --verify-binaries
python3 scripts/release-termux-pulse-source.py verify \
  --manifest release/termux-pulse-recipes.json \
  --repository tooling/external/termux-packages-release \
  --cache tooling/downloads/termux-pulse-source \
  --source-date-epoch 1785888000 \
  --output tooling/build/Archphene-termux-pulse-source-test.zip
```
