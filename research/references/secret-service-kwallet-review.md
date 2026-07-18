# Secret Service and KWallet interoperability review

Date: 2026-07-18

This note records the source review and device evidence behind Archphene's private Secret Service implementation. It is research evidence, not a general compatibility guarantee.

## Sources reviewed

- [Secret Service API specification](https://specifications.freedesktop.org/secret-service/latest/)
- [GNOME libsecret](https://gitlab.gnome.org/GNOME/libsecret), release 0.21.7
- [KDE KWallet](https://invent.kde.org/frameworks/kwallet), release 6.28.0
- [KWallet API documentation](https://api.kde.org/kwallet-index.html)
- [Arch Linux kwallet packaging](https://gitlab.archlinux.org/archlinux/packaging/packages/kwallet), package 6.28.0-1 at commit `b27a827`
- [Mbed TLS 3.6.6](https://github.com/Mbed-TLS/mbedtls/releases/tag/mbedtls-3.6.6)

The Arch PKGBUILD applies no downstream source patch to KWallet 6.28.0.

## Implemented compatibility

The private session bus exposes one always-unlocked login collection through `org.freedesktop.secrets`. It supports sender-bound plain sessions and `dh-ietf1024-sha256-aes128-cbc-pkcs7` sessions, default/login aliases, bounded item and property operations, content types, standard collection item signals, and key cleanup when clients disconnect.

Mbed TLS performs RFC 2409 MODP-1024 validation and exponentiation, HKDF-SHA256 key derivation, AES-128-CBC PKCS7 encryption, secure randomness, and explicit secret-buffer erasure. The Android store separately encrypts complete records with an Android Keystore non-exportable AES-256-GCM key.

KWallet 6.28.0 needs a bridge-owned compatibility daemon because the released daemon:

1. reads a collection pointer after moving its owner;
2. wraps a borrowed collection proxy in an owning smart pointer; and
3. reverses the folder and wallet arguments in one `listEntries` call.

The pinned helper patches only those daemon defects and keeps the public KF6 Wallet ABI and D-Bus protocol unchanged. Applications and the packaged Arch clients remain unmodified.

## Validated behavior

On the 4 KB x86_64 emulator:

- unmodified Arch `secret-tool` stores, looks up, and clears a value through an encrypted DH session;
- the KWallet D-Bus API opens the Login wallet, creates a folder, writes, lists, reads, and removes a password;
- unmodified Arch `kwallet-query` overwrites and reads that entry;
- KWallet data survives daemon restart;
- the private Android record remains encrypted and no test secret appears in Android logs.

The direct encrypted store and Secret Service wire contract also pass on the 16 KB x86_64 emulator and physical AArch64 Samsung device. On physical AArch64, the checksum-cataloged Arch Linux ARM closure validates official `secret-tool`, the patched compatibility `kwalletd6`, and official `kwallet-query`, including direct KWallet D-Bus writes, query overwrite/read, daemon-restart persistence, cleanup, and no plaintext in Android logs. The official Arch x86_64 client closure is intentionally skipped on 16 KB Android because its upstream ELF load segments are 4 KB-aligned.

## Observed upstream utility behavior

In this fixture, Arch `kwallet-query 6.28.0-1` opens the wallet and calls `hasFolder`, but exits successfully without calling `createFolder` or `writePassword` when it is asked to create the first item in an empty folder. Once a standards-compatible item exists, the same untouched binary writes and reads it correctly. Method-only opt-in tracing confirmed that the skipped calls never reach the compatibility daemon.

This is recorded as an upstream-client observation, not worked around by changing Secret Service semantics. The KWallet D-Bus API's first-folder create/write path passes directly.

## Remaining validation

- Revisit 16 KB x86 client execution only after the upstream Arch closure is rebuilt with compatible ELF alignment.
