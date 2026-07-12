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

static int g_width = 300;
static int g_height = 180;
#define WIDTH g_width
#define HEIGHT g_height
#define STRIDE (WIDTH * 4)
#define FRAME_BYTES (STRIDE * HEIGHT)

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

static void put_px(uint8_t *p, int x, int y, uint8_t r, uint8_t g, uint8_t b, uint8_t a) {
    if (x < 0 || x >= WIDTH || y < 0 || y >= HEIGHT) return;
    size_t i = ((size_t)y * STRIDE) + (size_t)x * 4;
    p[i + 0] = r;
    p[i + 1] = g;
    p[i + 2] = b;
    p[i + 3] = a;
}

static void rect(uint8_t *p, int x, int y, int w, int h, uint8_t r, uint8_t g, uint8_t b) {
    for (int yy = y; yy < y + h; yy++) {
        for (int xx = x; xx < x + w; xx++) {
            put_px(p, xx, yy, r, g, b, 255);
        }
    }
}

static void draw(uint8_t *p) {
    for (int y = 0; y < HEIGHT; y++) {
        for (int x = 0; x < WIDTH; x++) {
            put_px(p, x, y,
                    (uint8_t)(14 + (x * 42) / WIDTH),
                    (uint8_t)(16 + (y * 44) / HEIGHT),
                    (uint8_t)(48 + ((x + HEIGHT - y) * 34) / (WIDTH + HEIGHT)),
                    255);
        }
    }
    rect(p, 14, 14, 272, 152, 22, 27, 36);
    rect(p, 24, 24, 252, 38, 7, 21, 26);
    rect(p, 36, 77, 48, 28, 50, 64, 80);
    rect(p, 96, 77, 48, 28, 50, 64, 80);
    rect(p, 156, 77, 48, 28, 50, 64, 80);
    rect(p, 216, 77, 48, 28, 85, 73, 58);
    rect(p, 36, 116, 48, 28, 50, 64, 80);
    rect(p, 96, 116, 48, 28, 50, 64, 80);
    rect(p, 156, 116, 48, 28, 50, 64, 80);
    rect(p, 216, 116, 48, 28, 70, 91, 76);
    rect(p, 42, 152, 216, 6, 47, 157, 109);
    for (int i = 0; i < 6; i++) rect(p, 178 + i * 12, 33, 7, 18 + i * 2, 191, 239, 217);
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
    uint8_t msg[64];
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

static int env_dimension(const char *name, int fallback, int min_value) {
    const char *value = getenv(name);
    if (value == NULL || value[0] == '\0') return fallback;
    char *end = NULL;
    long parsed = strtol(value, &end, 10);
    if (end == value || parsed < min_value) return fallback;
    if (parsed > 4096) return 4096;
    return (int)parsed;
}

static void load_dimensions(void) {
    g_width = env_dimension("ARCHPHENE_WIDTH", g_width, 320);
    g_height = env_dimension("ARCHPHENE_HEIGHT", g_height, 240);
}
int main(void) {
    load_dimensions();
    int frame_fd = make_memfd("archphene-raw-wayland-shm");
    if (frame_fd < 0) {
        fprintf(stderr, "memfd_create failed: %s\n", strerror(errno));
        return 2;
    }
    if (ftruncate(frame_fd, FRAME_BYTES) != 0) {
        fprintf(stderr, "ftruncate failed: %s\n", strerror(errno));
        close(frame_fd);
        return 3;
    }
    uint8_t *pixels = mmap(NULL, FRAME_BYTES, PROT_READ | PROT_WRITE, MAP_SHARED, frame_fd, 0);
    if (pixels == MAP_FAILED) {
        fprintf(stderr, "mmap failed: %s\n", strerror(errno));
        close(frame_fd);
        return 4;
    }
    draw(pixels);
    msync(pixels, FRAME_BYTES, MS_SYNC);

    int fd = connect_socket();
    if (fd < 0) {
        fprintf(stderr, "connect failed: %s\n", strerror(errno));
        munmap(pixels, FRAME_BYTES);
        close(frame_fd);
        return 5;
    }

    uint32_t one[1] = {2};
    uint32_t pool[2] = {4, FRAME_BYTES};
    uint32_t buffer[6] = {5, 0, WIDTH, HEIGHT, STRIDE, 0};
    uint32_t surface[1] = {7};
    uint32_t attach[3] = {5, 0, 0};
    uint32_t damage[4] = {0, 0, WIDTH, HEIGHT};

    int ok = 0;
    ok |= send_u32_msg(fd, 1, 1, one, 1, -1);                       // wl_display.get_registry(new_id=2)
    ok |= send_bind(fd, 2, 1, "wl_shm", 1, 3);                       // wl_registry.bind -> wl_shm id 3
    ok |= send_bind(fd, 2, 2, "wl_compositor", 1, 6);                // wl_registry.bind -> compositor id 6
    ok |= send_u32_msg(fd, 3, 0, pool, 2, frame_fd);                 // wl_shm.create_pool(fd, id=4, size)
    ok |= send_u32_msg(fd, 4, 0, buffer, 6, -1);                     // wl_shm_pool.create_buffer(id=5, ...)
    ok |= send_u32_msg(fd, 6, 0, surface, 1, -1);                    // wl_compositor.create_surface(id=7)
    ok |= send_u32_msg(fd, 7, 1, attach, 3, -1);                     // wl_surface.attach(buffer=5, x=0, y=0)
    ok |= send_u32_msg(fd, 7, 2, damage, 4, -1);                     // wl_surface.damage(...)
    ok |= send_u32_msg(fd, 7, 6, NULL, 0, -1);                       // wl_surface.commit()

    close(fd);
    munmap(pixels, FRAME_BYTES);
    close(frame_fd);
    if (ok != 0) {
        fprintf(stderr, "failed to send one or more Wayland messages: %s\n", strerror(errno));
        return 6;
    }
    printf("sent raw Wayland wl_shm commit %dx%d stride=%d bytes=%d\n", WIDTH, HEIGHT, STRIDE, FRAME_BYTES);
    return 0;
}