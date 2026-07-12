# GrapheneOS Host + Arch VM Track

Purpose: preserve the GrapheneOS security/update model and expose Arch packages through an isolated guest environment.

This is the serious phone-oriented path.

## Target shape

- GrapheneOS/AOSP-derived host on a supported device.
- APKs run normally in Android app sandboxes.
- Arch ARM or Arch x86_64 rootfs runs in a VM or similarly strong guest boundary.
- Arch packages never mutate Android host partitions.
- GUI Linux apps render through a mediated display bridge.

## First milestones

1. Build GrapheneOS from source for one supported Pixel.
2. Boot a minimal Arch guest rootfs outside the phone first.
3. Prototype terminal-only guest launch.
4. Move to AVF/pKVM or equivalent VM integration.
5. Add explicit bridges for display, files, network, clipboard, and audio.
6. Threat-model guest-to-host and host-to-guest paths.

## Key risk

The hard work is not pacman. The hard work is preserving verified boot, SELinux, app sandboxing, update integrity, hardware-backed security features, and GrapheneOS' attack-surface discipline while adding a second app ecosystem.
