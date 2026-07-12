# Arch Host + Waydroid Track

Purpose: prove the easy direction first: Arch packages as the native host package ecosystem, APK support through Waydroid, and desktop/mobile UI experiments on x86_64 or ARM boards.

This track optimizes for fast local validation. It does not provide GrapheneOS-grade Android security.

## Target shape

- Arch Linux x86_64 or Arch Linux ARM host.
- Linux 7.x where supported by the hardware.
- Wayland desktop or phone shell.
- Waydroid for APK support.
- Full disk encryption and secure boot experiments where hardware allows.

## First milestones

1. Define host hardware target.
2. Install Arch baseline.
3. Install a Wayland compositor.
4. Install Waydroid and validate APK launch.
5. Capture gaps: input, rotation, GPU, audio, camera, notifications, suspend, package updates.

## Key risk

This is useful for desktop/laptop/tablet exploration, but Waydroid's Android container is not GrapheneOS. Treat Android apps in this track as compatibility testing, not as proof of the final security goal.
