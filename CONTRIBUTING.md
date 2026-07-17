# Contributing to Archphene

Archphene is an early systems project spanning Android application security, Wayland, Linux runtimes, package verification, storage brokers, and desktop application compatibility.

## Before opening work

- Read the [project overview](README.md), [architecture](docs/architecture.md), [security model](docs/security.md), and [roadmap](docs/roadmap.md).
- Search existing issues and pull requests.
- Use an issue for substantial architecture, protocol, permission, package, or storage changes before investing in a large implementation.
- Keep historical experiment evidence under `research/`; keep current behavior under `docs/`.

## Development expectations

- Preserve Android package, UID, SELinux, permission, lifecycle, and installer boundaries.
- Do not solve shared bridge behavior with application-title, hardcoded coordinate, or package-specific protocol hacks.
- Reject malformed package and Wayland input deterministically.
- Never commit keystores, passwords, downloaded package archives, SDKs, build outputs, or device data.
- Keep release builds non-debuggable.
- Add focused regression tests for affected behavior.
- State the Android version, CPU ABI, package version, and device/emulator used for validation.

See [Development](docs/development.md) for build and test commands.

## Pull requests

A pull request should include:

- the problem and intended behavior;
- security and compatibility implications;
- implementation summary;
- tests run and target environment;
- screenshots for visible Android or Linux UI changes;
- documentation updates where behavior changed;
- known limitations or follow-up work.

Keep unrelated refactors out of the same change.

## Bug reports

Use the bug issue form and include reproducible steps, logs, package versions, Android build details, ABI, and whether the target is an emulator, stock Android device, or GrapheneOS-supported device.

Do not publish vulnerabilities in a normal issue. Follow [SECURITY.md](SECURITY.md).

## Licensing

Project-owned source is licensed under the [MIT License](LICENSE). Contributions are submitted under that license. Preserve upstream notices and document provenance and compatible licensing for vendored code, generated sources, and prebuilt artifacts.
