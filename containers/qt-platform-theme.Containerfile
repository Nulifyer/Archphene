FROM docker.io/archlinux:base-devel-20260705.0.552420

RUN printf '%s\n' \
        '[options]' \
        'Architecture = auto' \
        'CheckSpace' \
        'SigLevel = Required DatabaseOptional' \
        'LocalFileSigLevel = Optional' \
        '' \
        '[core]' \
        'Server = https://archive.archlinux.org/repos/2026/07/05/$repo/os/$arch' \
        '' \
        '[extra]' \
        'Server = https://archive.archlinux.org/repos/2026/07/05/$repo/os/$arch' \
        > /etc/pacman.conf \
    && pacman -Syu --noconfirm --needed qt6-base \
    && test "$(pkg-config --modversion Qt6Core)" = 6.11.1 \
    && pacman -Scc --noconfirm