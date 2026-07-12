# Security policy

Archphene is a research prototype and has not completed a production security audit.

## Reporting a vulnerability

Do not open a public issue for a vulnerability.

Use [GitHub private vulnerability reporting](https://github.com/Nulifyer/Archphene/security/advisories/new) and include:

- affected commit, release, package, and architecture;
- Android version and device;
- reproduction steps or proof of concept;
- impact on package verification, signing, UID isolation, permissions, storage, Wayland parsing, or process lifecycle;
- relevant logs without unrelated personal data.

If private vulnerability reporting is unavailable, contact the repository owner through their GitHub profile and request a private reporting channel.

## Supported versions

No production-stable version is supported yet. Security fixes are developed against the latest `main` branch and will be included in subsequent GitHub Releases.

## Scope reminders

Relevant security boundaries include:

- Arch and Arch Linux ARM package authenticity;
- dependency and archive validation;
- generated APK contents and signing;
- Android package identity and signer continuity;
- Wayland protocol parsing and object lifecycle;
- Android permission and document brokers;
- Linux child-process and descendant lifecycle;
- release workflow and signing-key handling.

Archphene does not provide GrapheneOS-equivalent platform security on unsupported hardware. See the [security model](docs/security.md).