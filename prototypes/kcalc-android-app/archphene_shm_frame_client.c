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

static int g_width = 320;
static int g_height = 200;
#define WIDTH g_width
#define HEIGHT g_height
#define STRIDE (WIDTH * 4)
#define FRAME_BYTES (STRIDE * HEIGHT)

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
            uint8_t r = (uint8_t)(10 + (x * 34) / WIDTH);
            uint8_t g = (uint8_t)(18 + (y * 38) / HEIGHT);
            uint8_t b = (uint8_t)(38 + ((WIDTH - x + y) * 30) / (WIDTH + HEIGHT));
            put_px(p, x, y, r, g, b, 255);
        }
    }

    rect(p, 18, 18, 284, 164, 23, 31, 42);
    rect(p, 26, 26, 268, 44, 9, 24, 29);
    rect(p, 26, 78, 64, 34, 45, 62, 76);
    rect(p, 98, 78, 64, 34, 45, 62, 76);
    rect(p, 170, 78, 64, 34, 45, 62, 76);
    rect(p, 242, 78, 52, 34, 90, 77, 58);
    rect(p, 26, 120, 64, 34, 45, 62, 76);
    rect(p, 98, 120, 64, 34, 45, 62, 76);
    rect(p, 170, 120, 64, 34, 45, 62, 76);
    rect(p, 242, 120, 52, 34, 76, 91, 78);
    rect(p, 34, 164, 252, 8, 39, 145, 103);

    for (int i = 0; i < 5; i++) {
        rect(p, 212 + i * 14, 36, 8, 20 + i * 3, 184, 236, 212);
    }
    rect(p, 46, 91, 22, 5, 222, 191, 112);
    rect(p, 118, 91, 22, 5, 222, 191, 112);
    rect(p, 190, 91, 22, 5, 222, 191, 112);
    rect(p, 262, 91, 14, 5, 222, 191, 112);
    rect(p, 262, 86, 5, 15, 222, 191, 112);
}

static int connect_socket(void) {
    const char *runtime_dir = getenv("XDG_RUNTIME_DIR");
    const char *display = getenv("WAYLAND_DISPLAY");
    if (runtime_dir == NULL || display == NULL || runtime_dir[0] == '\0' || display[0] == '\0') {
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

static int send_fd_with_header(int socket_fd, int frame_fd) {
    char header[128];
    int header_len = snprintf(header, sizeof(header), "ARCHPHENE_SHM_FRAME_V1 %d %d %d %d\n", WIDTH, HEIGHT, STRIDE, FRAME_BYTES);
    struct iovec iov;
    iov.iov_base = header;
    iov.iov_len = (size_t)header_len;

    char control[CMSG_SPACE(sizeof(int))];
    memset(control, 0, sizeof(control));

    struct msghdr msg;
    memset(&msg, 0, sizeof(msg));
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = control;
    msg.msg_controllen = sizeof(control);

    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type = SCM_RIGHTS;
    cmsg->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(cmsg), &frame_fd, sizeof(int));

    ssize_t n = sendmsg(socket_fd, &msg, 0);
    if (n != header_len) return -1;
    return 0;
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
    int frame_fd = make_memfd("archphene-wl-shm-frame");
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

    int socket_fd = connect_socket();
    if (socket_fd < 0) {
        fprintf(stderr, "connect frame socket failed: %s\n", strerror(errno));
        munmap(pixels, FRAME_BYTES);
        close(frame_fd);
        return 5;
    }
    if (send_fd_with_header(socket_fd, frame_fd) != 0) {
        fprintf(stderr, "sendmsg SCM_RIGHTS failed: %s\n", strerror(errno));
        close(socket_fd);
        munmap(pixels, FRAME_BYTES);
        close(frame_fd);
        return 6;
    }

    close(socket_fd);
    munmap(pixels, FRAME_BYTES);
    close(frame_fd);
    printf("sent wl_shm-style memfd frame %dx%d stride=%d bytes=%d\n", WIDTH, HEIGHT, STRIDE, FRAME_BYTES);
    return 0;
}