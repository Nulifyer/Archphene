#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <wayland-client.h>

#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 0x0001U
#endif

struct render_state {
    uint32_t shm_name;
    uint32_t compositor_name;
    uint32_t xdg_name;
    uint32_t global_count;
    uint32_t shm_format_count;
};

static int make_memfd(const char *name) {
#ifdef SYS_memfd_create
    return (int)syscall(SYS_memfd_create, name, MFD_CLOEXEC);
#else
    errno = ENOSYS;
    return -1;
#endif
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
static void registry_global(void *data, struct wl_registry *registry, uint32_t name,
        const char *interface, uint32_t version) {
    (void)registry;
    (void)version;
    struct render_state *state = (struct render_state *)data;
    state->global_count++;
    if (strcmp(interface, "wl_shm") == 0) {
        state->shm_name = name;
    } else if (strcmp(interface, "wl_compositor") == 0) {
        state->compositor_name = name;
    } else if (strcmp(interface, "xdg_wm_base") == 0) {
        state->xdg_name = name;
    }
}

static void registry_global_remove(void *data, struct wl_registry *registry, uint32_t name) {
    (void)data;
    (void)registry;
    (void)name;
}

static const struct wl_registry_listener registry_listener = {
    registry_global,
    registry_global_remove,
};

static void shm_format(void *data, struct wl_shm *shm, uint32_t format) {
    (void)shm;
    (void)format;
    struct render_state *state = (struct render_state *)data;
    state->shm_format_count++;
}

static const struct wl_shm_listener shm_listener = {
    shm_format,
};

static void put_px(uint8_t *p, int width, int height, int stride, int x, int y, uint8_t r, uint8_t g, uint8_t b) {
    if (x < 0 || x >= width || y < 0 || y >= height) return;
    size_t i = ((size_t)y * (size_t)stride) + (size_t)x * 4;
    p[i + 0] = r;
    p[i + 1] = g;
    p[i + 2] = b;
    p[i + 3] = 255;
}

static void rect(uint8_t *p, int width, int height, int stride, int x, int y, int w, int h, uint8_t r, uint8_t g, uint8_t b) {
    int x2 = x + w;
    int y2 = y + h;
    if (x < 0) x = 0;
    if (y < 0) y = 0;
    if (x2 > width) x2 = width;
    if (y2 > height) y2 = height;
    for (int yy = y; yy < y2; yy++) {
        for (int xx = x; xx < x2; xx++) {
            put_px(p, width, height, stride, xx, yy, r, g, b);
        }
    }
}

static void draw(uint8_t *p, int width, int height, int stride) {
    rect(p, width, height, stride, 0, 0, width, height, 18, 22, 28);
    rect(p, width, height, stride, 24, 24, width - 48, height - 48, 242, 244, 247);
    rect(p, width, height, stride, 24, 24, width - 48, 40, 46, 92, 118);
    rect(p, width, height, stride, 48, 92, width - 96, 52, 255, 255, 255);
    for (int row = 0; row < 4; row++) {
        for (int col = 0; col < 4; col++) {
            int x = 48 + col * ((width - 96) / 4);
            int y = 168 + row * 46;
            int w = ((width - 96) / 4) - 8;
            rect(p, width, height, stride, x, y, w, 34, 230, 234, 238);
        }
    }
    rect(p, width, height, stride, width - 64, 24, 16, 16, 228, 80, 70);
}

int main(void) {
    struct render_state state = {0};
    struct wl_display *display = wl_display_connect(NULL);
    if (display == NULL) {
        perror("wl_display_connect");
        return 20;
    }
    struct wl_registry *registry = wl_display_get_registry(display);
    if (registry == NULL || wl_registry_add_listener(registry, &registry_listener, &state) != 0) {
        fprintf(stderr, "registry setup failed\n");
        wl_display_disconnect(display);
        return 21;
    }
    if (wl_display_roundtrip(display) < 0) {
        fprintf(stderr, "registry roundtrip failed\n");
        wl_display_disconnect(display);
        return 22;
    }
    if (state.shm_name == 0 || state.compositor_name == 0) {
        fprintf(stderr, "missing globals wl_shm=%u wl_compositor=%u total=%u\n",
                state.shm_name, state.compositor_name, state.global_count);
        wl_display_disconnect(display);
        return 23;
    }

    struct wl_shm *shm = (struct wl_shm *)wl_registry_bind(registry, state.shm_name, &wl_shm_interface, 1);
    struct wl_compositor *compositor = (struct wl_compositor *)wl_registry_bind(
            registry, state.compositor_name, &wl_compositor_interface, 1);
    if (shm == NULL || compositor == NULL || wl_shm_add_listener(shm, &shm_listener, &state) != 0) {
        fprintf(stderr, "bind setup failed\n");
        wl_display_disconnect(display);
        return 24;
    }
    if (wl_display_roundtrip(display) < 0 || state.shm_format_count == 0) {
        fprintf(stderr, "shm format roundtrip failed formats=%u\n", state.shm_format_count);
        wl_display_disconnect(display);
        return 25;
    }

    int width = env_dimension("ARCHPHENE_WIDTH", 420, 320);
    int height = env_dimension("ARCHPHENE_HEIGHT", 260, 240);
    int stride = width * 4;
    int frame_bytes = stride * height;
    int fd = make_memfd("archphene-wayland-api-render");
    if (fd < 0) {
        fprintf(stderr, "memfd_create failed: %s\n", strerror(errno));
        wl_display_disconnect(display);
        return 26;
    }
    if (ftruncate(fd, frame_bytes) != 0) {
        fprintf(stderr, "ftruncate failed: %s\n", strerror(errno));
        close(fd);
        wl_display_disconnect(display);
        return 27;
    }
    uint8_t *pixels = mmap(NULL, (size_t)frame_bytes, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (pixels == MAP_FAILED) {
        fprintf(stderr, "mmap failed: %s\n", strerror(errno));
        close(fd);
        wl_display_disconnect(display);
        return 28;
    }
    draw(pixels, width, height, stride);
    msync(pixels, (size_t)frame_bytes, MS_SYNC);

    struct wl_shm_pool *pool = wl_shm_create_pool(shm, fd, frame_bytes);
    struct wl_buffer *buffer = wl_shm_pool_create_buffer(pool, 0, width, height, stride, WL_SHM_FORMAT_ARGB8888);
    struct wl_surface *surface = wl_compositor_create_surface(compositor);
    if (pool == NULL || buffer == NULL || surface == NULL) {
        fprintf(stderr, "surface setup failed pool=%p buffer=%p surface=%p\n", (void *)pool, (void *)buffer, (void *)surface);
        munmap(pixels, (size_t)frame_bytes);
        close(fd);
        wl_display_disconnect(display);
        return 29;
    }
    wl_surface_attach(surface, buffer, 0, 0);
    wl_surface_damage(surface, 0, 0, width, height);
    wl_surface_commit(surface);
    if (wl_display_flush(display) < 0) {
        fprintf(stderr, "wl_display_flush failed\n");
        munmap(pixels, (size_t)frame_bytes);
        close(fd);
        wl_display_disconnect(display);
        return 30;
    }

    printf("wayland-client API render committed %dx%d stride=%d bytes=%d globals=%u formats=%u\n",
            width, height, stride, frame_bytes, state.global_count, state.shm_format_count);
    munmap(pixels, (size_t)frame_bytes);
    close(fd);
    wl_display_disconnect(display);
    return 0;
}
