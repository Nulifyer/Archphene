# Android audio payload

Archphene provides Linux playback through a private PulseAudio server inside
each audio-enabled wrapper UID. Linux applications keep using the standard
Pulse native protocol through `PULSE_SERVER`; the server renders through
Android AAudio and falls back to OpenSL ES when needed.

The payload is built from pinned official Termux packages by
`scripts/build-android-pulse.sh`. `termux-pulse-packages.tsv` records every
download URL component, byte count, and SHA-256 digest. The build extracts only
the PulseAudio server, playback modules, native protocol module, and their
reachable Bionic dependencies. It removes repository-specific RUNPATH values
and validates every ELF dependency before publishing `SHA256SUMS`.

Playback does not require an Android runtime permission. Capture is deliberately
not enabled by this payload: microphone support must be a separate bridge
capability that requests `RECORD_AUDIO` at the point of use.

The private socket and anonymous Pulse authentication are safe only because the
server and client share one Android application UID and sandbox. Archphene does
not expose this socket outside the wrapper.

Termux package metadata and sources are available from:

- <https://packages.termux.dev/apt/termux-main>
- <https://github.com/termux/termux-packages/tree/master/packages/pulseaudio>

PulseAudio is LGPL-2.1-or-later. Included libraries retain their upstream
licenses; release packaging must publish the corresponding notices and source
offer before this payload is shipped publicly.
