# Manager GitHub self-update result - 2026-07-12

## Environment

- Android 16 x86_64 emulator: `emulator-5554`
- Baseline: Archphene `0.9.0`, version code `9000`
- Target: public GitHub Release `v1.0.0`, version code `1000000002`
- Production signer SHA-256: `fb89debcc1d5057ba81959928ad8bb73aa6bf7be932e145e890224fdbec2928f`

## Result

The production-signed baseline discovered `v1.0.0` from the public GitHub API.
The manager fetched and validated the release checksum, downloaded the APK,
validated package and signer continuity, and opened Android's system-owned update
confirmation. After confirmation, Android installed the release and reported the
manager itself as installer and initiating package.

After process replacement and relaunch, the app list showed `Archphene 1.0.0 is up
to date` and did not retain the previous update-available state.

This proves the positive public-network path. The existing local-file test remains
useful for deterministic installer regression coverage.