# Android audio payload

Archphene's current Rust/Kotlin manager provides Linux playback through one
session-scoped private PulseAudio server for each authorized graphical process.
Linux applications keep using the standard Pulse native protocol through
`PULSE_SERVER`; the server renders through Android AAudio and falls back to
OpenSL ES when needed.

The payload is built from pinned official Termux packages by
`scripts/build-android-pulse.sh`. `termux-pulse-packages.tsv` records every
download URL component, byte count, and SHA-256 digest. The build extracts only
the PulseAudio server, playback modules, native protocol module, bounded
playback probe, and their reachable Bionic dependencies. It removes
repository-specific RUNPATH values and validates every ELF dependency before
publishing `SHA256SUMS`. `scripts/stage-archphene-android-audio.sh` stages the
verified dual-ABI result into the manager APK.

The manager creates the Pulse server and socket inside its private cache before
starting the authorized Linux process. Only a descriptor whose verified
executable closure carries `audio-output` receives the socket address. The
socket is not exposed through Binder, a generated launcher UID, or shared
storage, and is removed when the session closes. Anonymous Pulse authentication
is therefore bounded by the manager's Android sandbox and private filesystem
mode.

Playback requires no Android runtime permission. The current manager APK does
not request `RECORD_AUDIO`; microphone input must be implemented later as an
independent capability, permission, consent, and privacy boundary.

Run `scripts/test-android-audio-bridge.sh` against a current generated
`pavucontrol` launcher and an active non-audio launcher. The gate verifies exact
metadata, absence of microphone permission, private server startup, stock
client authentication, bounded 48 kHz stereo playback, non-audio denial,
cleanup/fatal logs, and a full-device screenshot.

Termux package metadata and sources are available from:

- <https://packages.termux.dev/apt/termux-main>
- <https://github.com/termux/termux-packages/tree/master/packages/pulseaudio>

PulseAudio is LGPL-2.1-or-later. Included libraries retain their upstream
licenses; release packaging must publish the corresponding notices and source
offer before this payload is shipped publicly.
