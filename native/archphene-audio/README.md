# Android audio payload

Archphene provides Linux playback and optional capture through a private
PulseAudio server inside each audio-enabled wrapper UID. Linux applications
keep using the standard Pulse native protocol through `PULSE_SERVER`; the
server renders through Android AAudio and falls back to OpenSL ES when needed.

The payload is built from pinned official Termux packages by
`scripts/build-android-pulse.sh`. `termux-pulse-packages.tsv` records every
download URL component, byte count, and SHA-256 digest. The build extracts only
the PulseAudio server, playback modules, pipe-source module, native protocol
module, `pacat` regression client, and their reachable Bionic dependencies. It
removes repository-specific RUNPATH values and validates every ELF dependency
before publishing `SHA256SUMS`.

Playback does not require an Android runtime permission. Microphone input is an
independent per-wrapper manager setting and `audio-input` capability. When it is
enabled, a Bionic helper watches only Pulse source streams attached to the
private `archphene_input` source. The first attached Linux stream asks the
same-UID Android broker to request `RECORD_AUDIO`; wrapper or playback startup
alone never requests it. After consent, the helper captures mono 48 kHz PCM16
through AAudio and feeds the private Pulse source. Android denial and the global
microphone privacy switch remain authoritative.

The private socket and anonymous Pulse authentication are safe only because the
server and client share one Android application UID and sandbox. Archphene does
not expose this socket outside the wrapper.

Run `scripts/test-android-microphone-bridge.ps1` against a prepared
`pavucontrol` wrapper to validate real capture on a physical device. The script
can temporarily disable and restore the device-wide microphone privacy switch;
it never changes that setting unless explicitly requested.

Termux package metadata and sources are available from:

- <https://packages.termux.dev/apt/termux-main>
- <https://github.com/termux/termux-packages/tree/master/packages/pulseaudio>

PulseAudio is LGPL-2.1-or-later. Included libraries retain their upstream
licenses; release packaging must publish the corresponding notices and source
offer before this payload is shipped publicly.
