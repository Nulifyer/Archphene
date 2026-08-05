FROM docker.io/archlinux:base-devel-20260705.0.552420@sha256:b21289eb1954872de0dc9f88976627e38611b1817be75e50946c83ab7b9c474d

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
    && pacman -Syu --noconfirm --needed aarch64-linux-gnu-gcc glib2 kconfig qt6-base \
    && test "$(pkg-config --modversion Qt6Core)" = 6.11.1 \
    && pacman -Scc --noconfirm
