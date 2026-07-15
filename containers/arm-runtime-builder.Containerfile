FROM docker.io/library/archlinux:base-devel

RUN pacman-key --init \
    && pacman-key --populate archlinux \
    && pacman -Syu --noconfirm --needed \
       aarch64-linux-gnu-binutils aarch64-linux-gnu-gcc \
       bison curl gawk git gnupg libarchive pacman python texinfo \
    && pacman -Scc --noconfirm