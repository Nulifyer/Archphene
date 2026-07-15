# Termux terminal components

Archphene vendors the `terminal-emulator` and `terminal-view` source modules from
`termux/termux-app` revision `3df69d1da197dd9bd71a3bafd902dffd720576b4`. The upstream repository explicitly identifies these two modules as
Apache License 2.0 exceptions to the GPLv3 application license.

The PTY implementation in `native/archphene-terminal/terminal_pty.c` is derived
from `terminal-emulator/src/main/jni/termux.c`. Archphene changes process-group
cleanup, descriptor cleanup, and one JNI string-release target. No Termux app or
service code is included.

Upstream: https://github.com/termux/termux-app