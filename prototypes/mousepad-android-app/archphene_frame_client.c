#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

static int g_width = 360;
static int g_height = 220;
#define WIDTH g_width
#define HEIGHT g_height

static void put_px(uint8_t *p, int x, int y, uint8_t r, uint8_t g, uint8_t b, uint8_t a) {
    if (x < 0 || x >= WIDTH || y < 0 || y >= HEIGHT) return;
    size_t i = ((size_t)y * WIDTH + x) * 4;
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

static void border(uint8_t *p, int x, int y, int w, int h, uint8_t r, uint8_t g, uint8_t b) {
    rect(p, x, y, w, 2, r, g, b);
    rect(p, x, y + h - 2, w, 2, r, g, b);
    rect(p, x, y, 2, h, r, g, b);
    rect(p, x + w - 2, y, 2, h, r, g, b);
}

static void seg(uint8_t *p, int x, int y, int w, int h, uint8_t mask, uint8_t r, uint8_t g, uint8_t b) {
    int t = 4;
    if (mask & 1) rect(p, x + t, y, w - 2 * t, t, r, g, b);
    if (mask & 2) rect(p, x + w - t, y + t, t, h / 2 - t, r, g, b);
    if (mask & 4) rect(p, x + w - t, y + h / 2, t, h / 2 - t, r, g, b);
    if (mask & 8) rect(p, x + t, y + h - t, w - 2 * t, t, r, g, b);
    if (mask & 16) rect(p, x, y + h / 2, t, h / 2 - t, r, g, b);
    if (mask & 32) rect(p, x, y + t, t, h / 2 - t, r, g, b);
    if (mask & 64) rect(p, x + t, y + h / 2 - t / 2, w - 2 * t, t, r, g, b);
}

static void digit(uint8_t *p, int x, int y, int value) {
    static const uint8_t masks[10] = {63, 6, 91, 79, 102, 109, 125, 7, 127, 111};
    seg(p, x, y, 28, 46, masks[value % 10], 210, 244, 230);
}

static int write_all(int fd, const void *buf, size_t len) {
    const uint8_t *p = (const uint8_t *)buf;
    while (len > 0) {
        ssize_t n = write(fd, p, len);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        p += n;
        len -= (size_t)n;
    }
    return 0;
}

static void draw_frame(uint8_t *p) {
    for (int y = 0; y < HEIGHT; y++) {
        for (int x = 0; x < WIDTH; x++) {
            uint8_t r = (uint8_t)(18 + (x * 22) / WIDTH);
            uint8_t g = (uint8_t)(24 + (y * 18) / HEIGHT);
            uint8_t b = (uint8_t)(34 + ((x + y) * 16) / (WIDTH + HEIGHT));
            put_px(p, x, y, r, g, b, 255);
        }
    }

    rect(p, 16, 14, 328, 192, 31, 37, 47);
    border(p, 16, 14, 328, 192, 87, 112, 135);
    rect(p, 34, 32, 292, 54, 8, 18, 18);
    border(p, 34, 32, 292, 54, 52, 99, 89);
    digit(p, 206, 38, 4);
    digit(p, 240, 38, 2);
    digit(p, 274, 38, 0);

    int labels[4][4] = {{7,8,9,-1},{4,5,6,-2},{1,2,3,-3},{0,-4,-5,-6}};
    for (int row = 0; row < 4; row++) {
        for (int col = 0; col < 4; col++) {
            int x = 34 + col * 74;
            int y = 102 + row * 24;
            uint8_t cr = labels[row][col] < 0 ? 78 : 48;
            uint8_t cg = labels[row][col] < 0 ? 87 : 58;
            uint8_t cb = labels[row][col] < 0 ? 98 : 70;
            rect(p, x, y, 58, 18, cr, cg, cb);
            border(p, x, y, 58, 18, 109, 129, 148);
            if (labels[row][col] >= 0) {
                digit(p, x + 18, y + 2, labels[row][col]);
            } else {
                rect(p, x + 20, y + 8, 18, 3, 222, 189, 110);
                if (labels[row][col] == -2 || labels[row][col] == -3) rect(p, x + 27, y + 4, 3, 11, 222, 189, 110);
            }
        }
    }

    rect(p, 34, 184, 292, 8, 33, 121, 94);
    rect(p, 34, 194, 192, 4, 141, 198, 170);
    rect(p, 232, 194, 94, 4, 214, 145, 83);
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
    const char *runtime_dir = getenv("XDG_RUNTIME_DIR");
    const char *display = getenv("WAYLAND_DISPLAY");
    if (runtime_dir == NULL || display == NULL || runtime_dir[0] == '\0' || display[0] == '\0') {
        fputs("XDG_RUNTIME_DIR and WAYLAND_DISPLAY must be set\n", stderr);
        return 2;
    }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    char path[sizeof(addr.sun_path)];
    int written = snprintf(path, sizeof(path), "%s/%s", runtime_dir, display);
    if (written < 0 || (size_t)written >= sizeof(path)) {
        fputs("frame socket path is too long\n", stderr);
        return 3;
    }
    memcpy(addr.sun_path, path, strlen(path) + 1);
    socklen_t addr_len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + strlen(path) + 1);

    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        fprintf(stderr, "socket failed: %s\n", strerror(errno));
        return 4;
    }
    if (connect(fd, (struct sockaddr *)&addr, addr_len) != 0) {
        fprintf(stderr, "connect(%s) failed: %s\n", path, strerror(errno));
        close(fd);
        return 5;
    }

    size_t bytes = (size_t)WIDTH * HEIGHT * 4;
    uint8_t *pixels = (uint8_t *)malloc(bytes);
    if (pixels == NULL) {
        close(fd);
        return 6;
    }
    draw_frame(pixels);

    char header[64];
    int header_len = snprintf(header, sizeof(header), "ARCHPHENE_FRAME_V1 %d %d\n", WIDTH, HEIGHT);
    if (write_all(fd, header, (size_t)header_len) != 0 || write_all(fd, pixels, bytes) != 0) {
        fprintf(stderr, "write frame failed: %s\n", strerror(errno));
        free(pixels);
        close(fd);
        return 7;
    }

    free(pixels);
    close(fd);
    printf("sent Linux-rendered frame %dx%d to %s\n", WIDTH, HEIGHT, path);
    return 0;
}