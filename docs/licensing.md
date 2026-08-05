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

## Separate package licenses

Packages installed later through pacman or the reviewed AUR path are not
relicensed by Archphene. They enter the shared Arch root under their upstream
package licenses and remain separate from Archphene's Android application
license.

## Open release audit

The complete component-license and notice audit is not closed. The signed
manager APK also carries pinned native/runtime components such as patched glibc,
pacman/libalpm, GnuPG, libarchive, Mesa, D-Bus, PulseAudio, PipeWire camera
runtime, virglrenderer, and transitive Rust crates. Their exact source/package
pins and artifact checksums are retained, but the release still needs one
canonical component inventory, required copyright notices, complete license
texts, and any corresponding-source or relinking obligations before the
licensing release gate can be marked complete. SPDX file entries therefore use
`NOASSERTION` for component licenses rather than making an unsupported
conclusion.

Run the current consistency gate with:

```bash
python3 scripts/test-release-license-contract.py
```
