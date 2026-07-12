#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <unistd.h>

#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 0x0001U
#endif

static uint32_t wl_header(uint16_t size, uint16_t opcode) {
    return ((uint32_t)size << 16) | opcode;
}

static int make_memfd(const char *name) {
#ifdef SYS_memfd_create
    return (int)syscall(SYS_memfd_create, name, MFD_CLOEXEC);
#else
    errno = ENOSYS;
    return -1;
#endif
}

static void put_px(uint8_t *p, int width, int height, int stride, int x, int y, uint8_t r, uint8_t g, uint8_t b, uint8_t a) {
    if (x < 0 || x >= width || y < 0 || y >= height) return;
    size_t i = ((size_t)y * (size_t)stride) + (size_t)x * 4;
    p[i + 0] = r;
    p[i + 1] = g;
    p[i + 2] = b;
    p[i + 3] = a;
}

static void rect(uint8_t *p, int width, int height, int stride, int x, int y, int w, int h, uint8_t r, uint8_t g, uint8_t b) {
    if (w <= 0 || h <= 0) return;
    int x2 = x + w;
    int y2 = y + h;
    if (x < 0) x = 0;
    if (y < 0) y = 0;
    if (x2 > width) x2 = width;
    if (y2 > height) y2 = height;
    for (int yy = y; yy < y2; yy++) {
        for (int xx = x; xx < x2; xx++) {
            put_px(p, width, height, stride, xx, yy, r, g, b, 255);
        }
    }
}

static void stroke(uint8_t *p, int width, int height, int stride, int x, int y, int w, int h, uint8_t r, uint8_t g, uint8_t b) {
    rect(p, width, height, stride, x, y, w, 1, r, g, b);
    rect(p, width, height, stride, x, y + h - 1, w, 1, r, g, b);
    rect(p, width, height, stride, x, y, 1, h, r, g, b);
    rect(p, width, height, stride, x + w - 1, y, 1, h, r, g, b);
}

static int scale_dim(int value, int total, int basis) {
    if (basis <= 0) return value;
    int scaled = (value * total) / basis;
    return scaled < 1 ? 1 : scaled;
}

static const char *glyph(char ch) {
    if (ch >= 'a' && ch <= 'z') ch = (char)(ch - 'a' + 'A');
    switch (ch) {
        case '0': return "01110" "10001" "10011" "10101" "11001" "10001" "01110";
        case '1': return "00100" "01100" "00100" "00100" "00100" "00100" "01110";
        case '2': return "01110" "10001" "00001" "00010" "00100" "01000" "11111";
        case '3': return "11110" "00001" "00001" "01110" "00001" "00001" "11110";
        case '4': return "00010" "00110" "01010" "10010" "11111" "00010" "00010";
        case '5': return "11111" "10000" "10000" "11110" "00001" "00001" "11110";
        case '6': return "01110" "10000" "10000" "11110" "10001" "10001" "01110";
        case '7': return "11111" "00001" "00010" "00100" "01000" "01000" "01000";
        case '8': return "01110" "10001" "10001" "01110" "10001" "10001" "01110";
        case '9': return "01110" "10001" "10001" "01111" "00001" "00001" "01110";
        case 'A': return "01110" "10001" "10001" "11111" "10001" "10001" "10001";
        case 'B': return "11110" "10001" "10001" "11110" "10001" "10001" "11110";
        case 'C': return "01111" "10000" "10000" "10000" "10000" "10000" "01111";
        case 'D': return "11110" "10001" "10001" "10001" "10001" "10001" "11110";
        case 'E': return "11111" "10000" "10000" "11110" "10000" "10000" "11111";
        case 'F': return "11111" "10000" "10000" "11110" "10000" "10000" "10000";
        case 'G': return "01111" "10000" "10000" "10111" "10001" "10001" "01111";
        case 'H': return "10001" "10001" "10001" "11111" "10001" "10001" "10001";
        case 'I': return "11111" "00100" "00100" "00100" "00100" "00100" "11111";
        case 'K': return "10001" "10010" "10100" "11000" "10100" "10010" "10001";
        case 'L': return "10000" "10000" "10000" "10000" "10000" "10000" "11111";
        case 'M': return "10001" "11011" "10101" "10101" "10001" "10001" "10001";
        case 'N': return "10001" "11001" "10101" "10011" "10001" "10001" "10001";
        case 'O': return "01110" "10001" "10001" "10001" "10001" "10001" "01110";
        case 'P': return "11110" "10001" "10001" "11110" "10000" "10000" "10000";
        case 'R': return "11110" "10001" "10001" "11110" "10100" "10010" "10001";
        case 'S': return "01111" "10000" "10000" "01110" "00001" "00001" "11110";
        case 'T': return "11111" "00100" "00100" "00100" "00100" "00100" "00100";
        case 'U': return "10001" "10001" "10001" "10001" "10001" "10001" "01110";
        case 'V': return "10001" "10001" "10001" "10001" "01010" "01010" "00100";
        case '%': return "11001" "11010" "00100" "01000" "10110" "00110" "00000";
        case '/': return "00001" "00010" "00100" "01000" "10000" "00000" "00000";
        case 'X': return "10001" "01010" "00100" "00100" "01010" "10001" "00000";
        case '-': return "00000" "00000" "00000" "11111" "00000" "00000" "00000";
        case '+': return "00000" "00100" "00100" "11111" "00100" "00100" "00000";
        case '=': return "00000" "00000" "11111" "00000" "11111" "00000" "00000";
        case '(': return "00010" "00100" "01000" "01000" "01000" "00100" "00010";
        case ')': return "01000" "00100" "00010" "00010" "00010" "00100" "01000";
        case ',': return "00000" "00000" "00000" "00000" "00000" "00100" "01000";
        case '.': return "00000" "00000" "00000" "00000" "00000" "01100" "01100";
        case ' ': return "00000" "00000" "00000" "00000" "00000" "00000" "00000";
        default: return "00000" "00000" "00000" "00000" "00000" "00000" "00000";
    }
}

static int text_width(const char *text, int s) {
    int n = 0;
    for (const char *c = text; *c; c++) n++;
    return n * 6 * s;
}

static void draw_char(uint8_t *p, int width, int height, int stride, int x, int y, char ch, int s, uint8_t r, uint8_t g, uint8_t b) {
    const char *bits = glyph(ch);
    for (int yy = 0; yy < 7; yy++) {
        for (int xx = 0; xx < 5; xx++) {
            if (bits[yy * 5 + xx] == '1') {
                rect(p, width, height, stride, x + xx * s, y + yy * s, s, s, r, g, b);
            }
        }
    }
}

static void draw_text(uint8_t *p, int width, int height, int stride, int x, int y, const char *text, int s, uint8_t r, uint8_t g, uint8_t b) {
    int cx = x;
    for (const char *c = text; *c; c++) {
        draw_char(p, width, height, stride, cx, y, *c, s, r, g, b);
        cx += 6 * s;
    }
}

static void draw_text_center(uint8_t *p, int width, int height, int stride, int x, int y, int w, int h, const char *text, int s, uint8_t r, uint8_t g, uint8_t b) {
    int tw = text_width(text, s);
    int tx = x + (w - tw) / 2;
    int ty = y + (h - 7 * s) / 2;
    draw_text(p, width, height, stride, tx, ty, text, s, r, g, b);
}

static void draw_button(uint8_t *p, int width, int height, int stride, int x, int y, int w, int h, const char *label, int font_s) {
    rect(p, width, height, stride, x, y, w, h, 247, 247, 247);
    stroke(p, width, height, stride, x, y, w, h, 199, 203, 207);
    draw_text_center(p, width, height, stride, x, y, w, h, label, font_s, 0, 28, 42);
}

static void draw(uint8_t *p, int width, int height, int stride) {
    rect(p, width, height, stride, 0, 0, width, height, 0, 0, 0);

    int ref_w = 357;
    int ref_h = 445;
    int win_w = width * 78 / 100;
    int max_h_by_w = (win_w * ref_h) / ref_w;
    int win_h = max_h_by_w;
    if (win_h > height * 86 / 100) {
        win_h = height * 86 / 100;
        win_w = (win_h * ref_w) / ref_h;
    }
    if (win_w < 320) {
        win_w = width - 32;
        win_h = (win_w * ref_h) / ref_w;
    }
    int wx = (width - win_w) / 2;
    int wy = (height - win_h) / 2;
    int s = win_w / ref_w;
    if (s < 2) s = 2;
    int font = s + 1;
    int small = s;

    rect(p, width, height, stride, wx, wy, win_w, win_h, 239, 241, 243);
    stroke(p, width, height, stride, wx, wy, win_w, win_h, 95, 100, 105);

    int title_h = scale_dim(33, win_h, ref_h);
    int menu_h = scale_dim(27, win_h, ref_h);
    rect(p, width, height, stride, wx + 1, wy + 1, win_w - 2, title_h, 225, 229, 233);
    draw_text_center(p, width, height, stride, wx, wy + 2, win_w, title_h, "KCALC", small, 20, 28, 35);
    draw_text(p, width, height, stride, wx + scale_dim(12, win_w, ref_w), wy + scale_dim(10, win_h, ref_h), "[]", small, 20, 80, 110);
    draw_text(p, width, height, stride, wx + win_w - scale_dim(72, win_w, ref_w), wy + scale_dim(10, win_h, ref_h), "- ^ X", small, 20, 28, 35);

    int menu_y = wy + title_h;
    rect(p, width, height, stride, wx + 1, menu_y, win_w - 2, menu_h, 225, 229, 233);
    draw_text(p, width, height, stride, wx + scale_dim(12, win_w, ref_w), menu_y + scale_dim(8, win_h, ref_h), "FILE", small, 0, 28, 42);
    draw_text(p, width, height, stride, wx + scale_dim(55, win_w, ref_w), menu_y + scale_dim(8, win_h, ref_h), "EDIT", small, 0, 28, 42);
    draw_text(p, width, height, stride, wx + scale_dim(100, win_w, ref_w), menu_y + scale_dim(8, win_h, ref_h), "SETTINGS", small, 0, 28, 42);
    draw_text(p, width, height, stride, wx + scale_dim(170, win_w, ref_w), menu_y + scale_dim(8, win_h, ref_h), "HELP", small, 0, 28, 42);

    int body_y = menu_y + menu_h;
    int display_h = scale_dim(108, win_h, ref_h);
    rect(p, width, height, stride, wx + 1, body_y, win_w - 2, display_h, 252, 252, 252);
    rect(p, width, height, stride, wx + scale_dim(12, win_w, ref_w), body_y + scale_dim(45, win_h, ref_h), win_w - scale_dim(24, win_w, ref_w), 1, 190, 190, 190);
    rect(p, width, height, stride, wx + 1, body_y + display_h - 1, win_w - 2, 1, 203, 207, 211);

    int grid_y = body_y + display_h + scale_dim(16, win_h, ref_h);
    int pad = scale_dim(10, win_w, ref_w);
    int gap = scale_dim(7, win_w, ref_w);
    int key_h = scale_dim(42, win_h, ref_h);
    int key_w = (win_w - pad * 2 - gap * 4) / 5;
    int col0 = wx + pad;
    int col1 = col0 + key_w + gap;
    int col2 = col1 + key_w + gap;
    int col3 = col2 + key_w + gap;
    int col4 = col3 + key_w + gap;
    int row0 = grid_y;
    int row1 = row0 + key_h + gap;
    int row2 = row1 + key_h + gap;
    int row3 = row2 + key_h + gap;
    int row4 = row3 + key_h + gap;

    draw_button(p, width, height, stride, col0, row0, key_w, key_h, "%", font);
    draw_button(p, width, height, stride, col1, row0, key_w, key_h, "/", font);
    draw_button(p, width, height, stride, col2, row0, key_w, key_h, "X", font);
    draw_button(p, width, height, stride, col3, row0, key_w, key_h, "-", font);
    draw_button(p, width, height, stride, col4, row0, key_w, key_h, "C", font);

    draw_button(p, width, height, stride, col0, row1, key_w, key_h, "7", font);
    draw_button(p, width, height, stride, col1, row1, key_w, key_h, "8", font);
    draw_button(p, width, height, stride, col2, row1, key_w, key_h, "9", font);
    draw_button(p, width, height, stride, col3, row1, key_w, key_h * 2 + gap, "+", font);
    draw_button(p, width, height, stride, col4, row1, key_w, key_h, "AC", font);

    draw_button(p, width, height, stride, col0, row2, key_w, key_h, "4", font);
    draw_button(p, width, height, stride, col1, row2, key_w, key_h, "5", font);
    draw_button(p, width, height, stride, col2, row2, key_w, key_h, "6", font);
    draw_button(p, width, height, stride, col4, row2, key_w, key_h, "(", font);

    draw_button(p, width, height, stride, col0, row3, key_w, key_h, "1", font);
    draw_button(p, width, height, stride, col1, row3, key_w, key_h, "2", font);
    draw_button(p, width, height, stride, col2, row3, key_w, key_h, "3", font);
    draw_button(p, width, height, stride, col3, row3, key_w, key_h * 2 + gap, "=", font);
    draw_button(p, width, height, stride, col4, row3, key_w, key_h, ")", font);

    draw_button(p, width, height, stride, col0, row4, key_w * 2 + gap, key_h, "0", font);
    draw_button(p, width, height, stride, col2, row4, key_w, key_h, ",", font);
    draw_button(p, width, height, stride, col4, row4, key_w, key_h, "+/-", small);

    int status_y = wy + win_h - scale_dim(24, win_h, ref_h);
    rect(p, width, height, stride, wx + 1, status_y, win_w - 2, scale_dim(23, win_h, ref_h), 239, 241, 243);
    rect(p, width, height, stride, wx + 1, status_y, win_w - 2, 1, 203, 207, 211);
    draw_text(p, width, height, stride, wx + win_w - scale_dim(82, win_w, ref_w), status_y + scale_dim(7, win_h, ref_h), "NORM", small, 0, 28, 42);
}
static int connect_socket(void) {
    const char *runtime_dir = getenv("XDG_RUNTIME_DIR");
    const char *display = getenv("WAYLAND_DISPLAY");
    if (!runtime_dir || !display || runtime_dir[0] == '\0' || display[0] == '\0') {
        errno = EINVAL;
        return -1;
    }
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    char path[sizeof(addr.sun_path)];
    int written = snprintf(path, sizeof(path), "%s/%s", runtime_dir, display);
    if (written < 0 || (size_t)written >= sizeof(path)) {
        errno = ENAMETOOLONG;
        return -1;
    }
    memcpy(addr.sun_path, path, strlen(path) + 1);
    socklen_t len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + strlen(path) + 1);
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    if (connect(fd, (struct sockaddr *)&addr, len) != 0) {
        int err = errno;
        close(fd);
        errno = err;
        return -1;
    }
    return fd;
}

static int read_exact(int fd, void *bytes, size_t len) {
    uint8_t *p = (uint8_t *)bytes;
    size_t off = 0;
    while (off < len) {
        ssize_t n = read(fd, p + off, len - off);
        if (n == 0) {
            errno = ECONNRESET;
            return -1;
        }
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        off += (size_t)n;
    }
    return 0;
}

static int send_bytes_with_fd(int socket_fd, const void *bytes, size_t len, int send_fd) {
    struct iovec iov;
    iov.iov_base = (void *)bytes;
    iov.iov_len = len;
    struct msghdr msg;
    memset(&msg, 0, sizeof(msg));
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;

    char control[CMSG_SPACE(sizeof(int))];
    if (send_fd >= 0) {
        memset(control, 0, sizeof(control));
        msg.msg_control = control;
        msg.msg_controllen = sizeof(control);
        struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
        cmsg->cmsg_level = SOL_SOCKET;
        cmsg->cmsg_type = SCM_RIGHTS;
        cmsg->cmsg_len = CMSG_LEN(sizeof(int));
        memcpy(CMSG_DATA(cmsg), &send_fd, sizeof(int));
    }

    ssize_t n = sendmsg(socket_fd, &msg, 0);
    return n == (ssize_t)len ? 0 : -1;
}

static int send_u32_msg(int fd, uint32_t object, uint16_t opcode, const uint32_t *payload, size_t words, int send_fd) {
    uint32_t msg[16];
    uint16_t size = (uint16_t)(8 + words * 4);
    msg[0] = object;
    msg[1] = wl_header(size, opcode);
    for (size_t i = 0; i < words; i++) msg[2 + i] = payload[i];
    return send_bytes_with_fd(fd, msg, size, send_fd);
}

static int send_bind(int fd, uint32_t registry, uint32_t name, const char *interface_name, uint32_t version, uint32_t new_id) {
    uint8_t msg[80];
    memset(msg, 0, sizeof(msg));
    uint32_t len = (uint32_t)strlen(interface_name) + 1;
    uint32_t padded = (len + 3U) & ~3U;
    uint16_t size = (uint16_t)(8 + 4 + 4 + padded + 4 + 4);
    uint32_t *w = (uint32_t *)msg;
    w[0] = registry;
    w[1] = wl_header(size, 0);
    w[2] = name;
    w[3] = len;
    memcpy(msg + 16, interface_name, len);
    uint32_t *tail = (uint32_t *)(msg + 16 + padded);
    tail[0] = version;
    tail[1] = new_id;
    return send_bytes_with_fd(fd, msg, size, -1);
}

static uint32_t get_u32(const uint8_t *bytes) {
    return (uint32_t)bytes[0] | ((uint32_t)bytes[1] << 8) | ((uint32_t)bytes[2] << 16) | ((uint32_t)bytes[3] << 24);
}

static int parse_global(const uint8_t *payload, uint16_t size, uint32_t *shm_name, uint32_t *compositor_name, uint32_t *xdg_name) {
    if (size < 20) return 0;
    uint32_t name = get_u32(payload);
    uint32_t len = get_u32(payload + 4);
    if (len == 0 || len > 64 || 8 + len > size - 8) return -1;
    char iface[80];
    memset(iface, 0, sizeof(iface));
    memcpy(iface, payload + 8, len < sizeof(iface) ? len : sizeof(iface) - 1);
    uint32_t padded = (len + 3U) & ~3U;
    if (8 + padded + 4 > size - 8) return -1;
    if (strcmp(iface, "wl_shm") == 0) *shm_name = name;
    if (strcmp(iface, "wl_compositor") == 0) *compositor_name = name;
    if (strcmp(iface, "xdg_wm_base") == 0) *xdg_name = name;
    return 0;
}

static int read_shm_format(int fd, uint32_t shm_object) {
    for (;;) {
        uint8_t header[8];
        if (read_exact(fd, header, sizeof(header)) != 0) return -1;
        uint32_t object = get_u32(header);
        uint32_t size_opcode = get_u32(header + 4);
        uint16_t opcode = (uint16_t)(size_opcode & 0xffffU);
        uint16_t size = (uint16_t)((size_opcode >> 16) & 0xffffU);
        if (size < 8 || size > 4096) {
            errno = EPROTO;
            return -1;
        }
        uint8_t payload[4088];
        if (read_exact(fd, payload, (size_t)size - 8) != 0) return -1;
        if (object == shm_object && opcode == 0) return 0;
    }
}

static int read_xdg_configure(int fd, uint32_t xdg_surface, uint32_t xdg_toplevel, uint32_t *serial_out, int *width_out, int *height_out) {
    int width = 0;
    int height = 0;
    for (;;) {
        uint8_t header[8];
        if (read_exact(fd, header, sizeof(header)) != 0) return -1;
        uint32_t object = get_u32(header);
        uint32_t size_opcode = get_u32(header + 4);
        uint16_t opcode = (uint16_t)(size_opcode & 0xffffU);
        uint16_t size = (uint16_t)((size_opcode >> 16) & 0xffffU);
        if (size < 8 || size > 4096) {
            errno = EPROTO;
            return -1;
        }
        uint8_t payload[4088];
        if (read_exact(fd, payload, (size_t)size - 8) != 0) return -1;
        if (object == xdg_toplevel && opcode == 0) {
            if (size < 20) {
                errno = EPROTO;
                return -1;
            }
            width = (int)get_u32(payload);
            height = (int)get_u32(payload + 4);
        }
        if (object == xdg_surface && opcode == 0) {
            if (size < 12) {
                errno = EPROTO;
                return -1;
            }
            *serial_out = get_u32(payload);
            *width_out = width > 0 ? width : 420;
            *height_out = height > 0 ? height : 260;
            return 0;
        }
    }
}

static int read_post_commit_events(int fd, uint32_t frame_callback, uint32_t buffer_id) {
    int frame_done = 0;
    int buffer_released = 0;
    while (!frame_done || !buffer_released) {
        uint8_t header[8];
        if (read_exact(fd, header, sizeof(header)) != 0) return -1;
        uint32_t object = get_u32(header);
        uint32_t size_opcode = get_u32(header + 4);
        uint16_t opcode = (uint16_t)(size_opcode & 0xffffU);
        uint16_t size = (uint16_t)((size_opcode >> 16) & 0xffffU);
        if (size < 8 || size > 4096) {
            errno = EPROTO;
            return -1;
        }
        uint8_t payload[4088];
        if (read_exact(fd, payload, (size_t)size - 8) != 0) return -1;
        if (object == frame_callback && opcode == 0) frame_done = 1;
        if (object == buffer_id && opcode == 0) buffer_released = 1;
    }
    return 0;
}
static int read_registry_roundtrip(int fd, uint32_t callback_id, uint32_t *shm_name, uint32_t *compositor_name, uint32_t *xdg_name) {
    int callback_done = 0;
    while (!callback_done) {
        uint8_t header[8];
        if (read_exact(fd, header, sizeof(header)) != 0) return -1;
        uint32_t object = get_u32(header);
        uint32_t size_opcode = get_u32(header + 4);
        uint16_t opcode = (uint16_t)(size_opcode & 0xffffU);
        uint16_t size = (uint16_t)((size_opcode >> 16) & 0xffffU);
        if (size < 8 || size > 4096) {
            errno = EPROTO;
            return -1;
        }
        uint8_t payload[4088];
        if (read_exact(fd, payload, (size_t)size - 8) != 0) return -1;
        if (object == 2 && opcode == 0) {
            if (parse_global(payload, size, shm_name, compositor_name, xdg_name) != 0) {
                errno = EPROTO;
                return -1;
            }
        }
        if (object == callback_id && opcode == 0) callback_done = 1;
    }
    return 0;
}

int main(void) {
    int fd = connect_socket();
    if (fd < 0) {
        fprintf(stderr, "connect failed: %s\n", strerror(errno));
        return 5;
    }

    uint32_t registry_payload[1] = {2};
    uint32_t sync_payload[1] = {8};
    int ok = 0;
    ok |= send_u32_msg(fd, 1, 1, registry_payload, 1, -1);
    ok |= send_u32_msg(fd, 1, 0, sync_payload, 1, -1);
    if (ok != 0) {
        fprintf(stderr, "failed to request registry roundtrip: %s\n", strerror(errno));
        close(fd);
        return 6;
    }

    uint32_t shm_name = 0;
    uint32_t compositor_name = 0;
    uint32_t xdg_name = 0;
    if (read_registry_roundtrip(fd, 8, &shm_name, &compositor_name, &xdg_name) != 0) {
        fprintf(stderr, "registry roundtrip failed: %s\n", strerror(errno));
        close(fd);
        return 7;
    }
    if (shm_name == 0 || compositor_name == 0 || xdg_name == 0) {
        fprintf(stderr, "missing globals: wl_shm=%u wl_compositor=%u xdg_wm_base=%u\n", shm_name, compositor_name, xdg_name);
        close(fd);
        return 8;
    }

    uint32_t surface[1] = {7};
    uint32_t xdg_surface_req[2] = {10, 7};
    uint32_t xdg_toplevel_req[1] = {11};

    ok = 0;
    ok |= send_bind(fd, 2, shm_name, "wl_shm", 1, 3);
    if (ok != 0) {
        fprintf(stderr, "failed to bind wl_shm: %s\n", strerror(errno));
        close(fd);
        return 9;
    }
    if (read_shm_format(fd, 3) != 0) {
        fprintf(stderr, "wl_shm.format read failed: %s\n", strerror(errno));
        close(fd);
        return 10;
    }
    ok |= send_bind(fd, 2, compositor_name, "wl_compositor", 1, 6);
    ok |= send_bind(fd, 2, xdg_name, "xdg_wm_base", 1, 9);
    ok |= send_u32_msg(fd, 6, 0, surface, 1, -1);
    ok |= send_u32_msg(fd, 9, 2, xdg_surface_req, 2, -1);
    ok |= send_u32_msg(fd, 10, 1, xdg_toplevel_req, 1, -1);
    ok |= send_u32_msg(fd, 7, 6, NULL, 0, -1);
    if (ok != 0) {
        fprintf(stderr, "failed to create xdg toplevel: %s\n", strerror(errno));
        close(fd);
        return 11;
    }

    uint32_t configure_serial = 0;
    int width = 0;
    int height = 0;
    if (read_xdg_configure(fd, 10, 11, &configure_serial, &width, &height) != 0 || configure_serial == 0) {
        fprintf(stderr, "xdg configure read failed: %s serial=%u\n", strerror(errno), configure_serial);
        close(fd);
        return 12;
    }
    if (width < 320) width = 320;
    if (height < 240) height = 240;
    if (width > 4096) width = 4096;
    if (height > 4096) height = 4096;

    int stride = width * 4;
    int frame_bytes = stride * height;
    int frame_fd = make_memfd("archphene-xdg-wayland-shm");
    if (frame_fd < 0) {
        fprintf(stderr, "memfd_create failed: %s\n", strerror(errno));
        close(fd);
        return 13;
    }
    if (ftruncate(frame_fd, frame_bytes) != 0) {
        fprintf(stderr, "ftruncate failed: %s\n", strerror(errno));
        close(frame_fd);
        close(fd);
        return 14;
    }
    uint8_t *pixels = mmap(NULL, (size_t)frame_bytes, PROT_READ | PROT_WRITE, MAP_SHARED, frame_fd, 0);
    if (pixels == MAP_FAILED) {
        fprintf(stderr, "mmap failed: %s\n", strerror(errno));
        close(frame_fd);
        close(fd);
        return 15;
    }
    draw(pixels, width, height, stride);
    msync(pixels, (size_t)frame_bytes, MS_SYNC);

    uint32_t ack[1] = {configure_serial};
    uint32_t pool[2] = {4, (uint32_t)frame_bytes};
    uint32_t buffer[6] = {5, 0, (uint32_t)width, (uint32_t)height, (uint32_t)stride, 0};
    uint32_t attach[3] = {5, 0, 0};
    uint32_t frame[1] = {12};
    uint32_t damage[4] = {0, 0, (uint32_t)width, (uint32_t)height};

    ok = 0;
    ok |= send_u32_msg(fd, 10, 4, ack, 1, -1);
    ok |= send_u32_msg(fd, 3, 0, pool, 2, frame_fd);
    ok |= send_u32_msg(fd, 4, 0, buffer, 6, -1);
    ok |= send_u32_msg(fd, 7, 1, attach, 3, -1);
    ok |= send_u32_msg(fd, 7, 3, frame, 1, -1);
    ok |= send_u32_msg(fd, 7, 2, damage, 4, -1);
    ok |= send_u32_msg(fd, 7, 6, NULL, 0, -1);

    if (ok != 0) {
        fprintf(stderr, "failed to send Wayland commit after xdg configure: %s\n", strerror(errno));
        close(fd);
        munmap(pixels, (size_t)frame_bytes);
        close(frame_fd);
        return 16;
    }
    if (read_post_commit_events(fd, 12, 5) != 0) {
        fprintf(stderr, "post-commit frame/release events failed: %s\n", strerror(errno));
        close(fd);
        munmap(pixels, (size_t)frame_bytes);
        close(frame_fd);
        return 17;
    }

    close(fd);
    munmap(pixels, (size_t)frame_bytes);
    close(frame_fd);
    printf("xdg Wayland wl_shm commit %dx%d stride=%d bytes=%d globals wl_shm=%u wl_compositor=%u xdg_wm_base=%u configure_serial=%u frame_done=1 buffer_released=1\n",
            width, height, stride, frame_bytes, shm_name, compositor_name, xdg_name, configure_serial);
    return 0;
}


