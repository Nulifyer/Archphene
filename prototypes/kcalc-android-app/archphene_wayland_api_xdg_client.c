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
#include "wayland-xdg-shell-client-protocol.h"

#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 0x0001U
#endif

struct xdg_api_state {
    uint32_t shm_name;
    uint32_t compositor_name;
    uint32_t xdg_name;
    uint32_t output_name;
    uint32_t seat_name;
    uint32_t global_count;
    uint32_t shm_format_count;
    uint32_t output_done;
    int output_width;
    int output_height;
    int output_scale;
    uint32_t seat_capabilities;
    int seat_named;
    int pointer_entered;
    int pointer_moved;
    int pointer_button;
    int pointer_x;
    int pointer_y;
    int pointer_dispatches;
    int real_pointer_repainted;
    int keyboard_keymap;
    int keyboard_keymap_fd;
    int keyboard_keymap_format;
    int keyboard_keymap_size;
    int keyboard_keymap_has_xkb;
    int keyboard_entered;
    int keyboard_key;
    int keyboard_last_key;
    int keyboard_modifiers;
    int keyboard_modifiers_nonzero;
    int keyboard_last_mods;
    int keyboard_repeat_info;
    int keyboard_repeat_rate;
    int keyboard_repeat_delay;
    int keyboard_dispatches;
    int real_keyboard_repainted;
    uint32_t configure_serial;
    int configured_width;
    int configured_height;
    int surface_configured;
    int frame_done;
    int buffer_released;
};

static int make_memfd(const char *name) {
#ifdef SYS_memfd_create
    return (int)syscall(SYS_memfd_create, name, MFD_CLOEXEC);
#else
    errno = ENOSYS;
    return -1;
#endif
}

static void registry_global(void *data, struct wl_registry *registry, uint32_t name,
        const char *interface, uint32_t version) {
    (void)registry;
    (void)version;
    struct xdg_api_state *state = (struct xdg_api_state *)data;
    state->global_count++;
    if (strcmp(interface, "wl_shm") == 0) state->shm_name = name;
    else if (strcmp(interface, "wl_compositor") == 0) state->compositor_name = name;
    else if (strcmp(interface, "xdg_wm_base") == 0) state->xdg_name = name;
    else if (strcmp(interface, "wl_output") == 0) state->output_name = name;
    else if (strcmp(interface, "wl_seat") == 0) state->seat_name = name;
}

static void registry_global_remove(void *data, struct wl_registry *registry, uint32_t name) {
    (void)data; (void)registry; (void)name;
}

static const struct wl_registry_listener registry_listener = { registry_global, registry_global_remove };

static void shm_format(void *data, struct wl_shm *shm, uint32_t format) {
    (void)shm; (void)format;
    ((struct xdg_api_state *)data)->shm_format_count++;
}

static const struct wl_shm_listener shm_listener = { shm_format };

static void frame_done(void *data, struct wl_callback *callback, uint32_t callback_data) {
    (void)callback;
    (void)callback_data;
    ((struct xdg_api_state *)data)->frame_done = 1;
}

static const struct wl_callback_listener frame_listener = { frame_done };

static void buffer_release(void *data, struct wl_buffer *buffer) {
    (void)buffer;
    ((struct xdg_api_state *)data)->buffer_released = 1;
}

static const struct wl_buffer_listener buffer_listener = { buffer_release };

static void output_geometry(void *data, struct wl_output *output, int32_t x, int32_t y,
        int32_t physical_width, int32_t physical_height, int32_t subpixel,
        const char *make, const char *model, int32_t transform) {
    (void)output; (void)x; (void)y; (void)physical_width; (void)physical_height;
    (void)subpixel; (void)make; (void)model; (void)transform;
    struct xdg_api_state *state = (struct xdg_api_state *)data;
    if (state->output_scale == 0) state->output_scale = 1;
}

static void output_mode(void *data, struct wl_output *output, uint32_t flags, int32_t width, int32_t height, int32_t refresh) {
    (void)output; (void)refresh;
    if ((flags & WL_OUTPUT_MODE_CURRENT) != 0) {
        struct xdg_api_state *state = (struct xdg_api_state *)data;
        state->output_width = width;
        state->output_height = height;
    }
}

static void output_done(void *data, struct wl_output *output) {
    (void)output;
    ((struct xdg_api_state *)data)->output_done = 1;
}

static void output_scale(void *data, struct wl_output *output, int32_t factor) {
    (void)output;
    ((struct xdg_api_state *)data)->output_scale = factor;
}

static const struct wl_output_listener output_listener = {
    output_geometry,
    output_mode,
    output_done,
    output_scale,
    NULL,
    NULL,
};

static void seat_capabilities(void *data, struct wl_seat *seat, uint32_t capabilities) {
    (void)seat;
    ((struct xdg_api_state *)data)->seat_capabilities = capabilities;
}

static void seat_name(void *data, struct wl_seat *seat, const char *name) {
    (void)seat; (void)name;
    ((struct xdg_api_state *)data)->seat_named = 1;
}

static const struct wl_seat_listener seat_listener = {
    seat_capabilities,
    seat_name,
};

static void pointer_enter(void *data, struct wl_pointer *pointer, uint32_t serial, struct wl_surface *surface, wl_fixed_t sx, wl_fixed_t sy) {
    (void)pointer; (void)serial; (void)surface;
    struct xdg_api_state *state = (struct xdg_api_state *)data;
    state->pointer_entered = 1;
    state->pointer_x = sx / 256;
    state->pointer_y = sy / 256;
}

static void pointer_leave(void *data, struct wl_pointer *pointer, uint32_t serial, struct wl_surface *surface) {
    (void)data; (void)pointer; (void)serial; (void)surface;
}

static void pointer_motion(void *data, struct wl_pointer *pointer, uint32_t time, wl_fixed_t sx, wl_fixed_t sy) {
    (void)pointer; (void)time;
    struct xdg_api_state *state = (struct xdg_api_state *)data;
    state->pointer_moved = 1;
    state->pointer_x = sx / 256;
    state->pointer_y = sy / 256;
}

static void pointer_button(void *data, struct wl_pointer *pointer, uint32_t serial, uint32_t time, uint32_t button, uint32_t button_state) {
    (void)pointer; (void)serial; (void)time; (void)button;
    if (button_state != 0) {
        ((struct xdg_api_state *)data)->pointer_button = 1;
    }
}

static void pointer_axis(void *data, struct wl_pointer *pointer, uint32_t time, uint32_t axis, wl_fixed_t value) {
    (void)data; (void)pointer; (void)time; (void)axis; (void)value;
}

static const struct wl_pointer_listener pointer_listener = {
    pointer_enter,
    pointer_leave,
    pointer_motion,
    pointer_button,
    pointer_axis,
    NULL,
    NULL,
    NULL,
    NULL,
    NULL,
    NULL,
};

static void keyboard_keymap(void *data, struct wl_keyboard *keyboard, uint32_t format, int32_t fd, uint32_t size) {
    (void)keyboard;
    struct xdg_api_state *state = (struct xdg_api_state *)data;
    state->keyboard_keymap = 1;
    state->keyboard_keymap_format = (int)format;
    state->keyboard_keymap_size = (int)size;
    state->keyboard_keymap_fd = fd >= 0 ? 1 : 0;
    if (fd >= 0 && size > 0) {
        char buf[512];
        ssize_t n = read(fd, buf, sizeof(buf) - 1);
        if (n > 0) {
            buf[n] = '\0';
            if (strstr(buf, "xkb_keymap") != NULL) state->keyboard_keymap_has_xkb = 1;
        }
        close(fd);
    }
}

static void keyboard_enter(void *data, struct wl_keyboard *keyboard, uint32_t serial, struct wl_surface *surface, struct wl_array *keys) {
    (void)keyboard; (void)serial; (void)surface; (void)keys;
    ((struct xdg_api_state *)data)->keyboard_entered = 1;
}

static void keyboard_leave(void *data, struct wl_keyboard *keyboard, uint32_t serial, struct wl_surface *surface) {
    (void)data; (void)keyboard; (void)serial; (void)surface;
}

static void keyboard_key(void *data, struct wl_keyboard *keyboard, uint32_t serial, uint32_t time, uint32_t key, uint32_t state_value) {
    (void)keyboard; (void)serial; (void)time;
    if (state_value == WL_KEYBOARD_KEY_STATE_PRESSED) {
        struct xdg_api_state *state = (struct xdg_api_state *)data;
        state->keyboard_key = 1;
        state->keyboard_last_key = (int)key;
    }
}

static void keyboard_modifiers(void *data, struct wl_keyboard *keyboard, uint32_t serial, uint32_t mods_depressed, uint32_t mods_latched, uint32_t mods_locked, uint32_t group) {
    (void)keyboard; (void)serial; (void)mods_latched; (void)mods_locked; (void)group;
    struct xdg_api_state *state = (struct xdg_api_state *)data;
    state->keyboard_modifiers++;
    state->keyboard_last_mods = (int)mods_depressed;
    if (mods_depressed != 0) state->keyboard_modifiers_nonzero = 1;
}

static void keyboard_repeat_info(void *data, struct wl_keyboard *keyboard, int32_t rate, int32_t delay) {
    (void)keyboard;
    struct xdg_api_state *state = (struct xdg_api_state *)data;
    state->keyboard_repeat_info = 1;
    state->keyboard_repeat_rate = rate;
    state->keyboard_repeat_delay = delay;
}

static const struct wl_keyboard_listener keyboard_listener = {
    keyboard_keymap,
    keyboard_enter,
    keyboard_leave,
    keyboard_key,
    keyboard_modifiers,
    keyboard_repeat_info,
};
static void xdg_surface_configure(void *data, struct xdg_surface *surface, uint32_t serial) {
    (void)surface;
    struct xdg_api_state *state = (struct xdg_api_state *)data;
    state->configure_serial = serial;
    state->surface_configured = 1;
}

static const struct xdg_surface_listener xdg_surface_listener = { xdg_surface_configure };

static void xdg_toplevel_configure(void *data, struct xdg_toplevel *toplevel, int32_t width, int32_t height, struct wl_array *states) {
    (void)toplevel; (void)states;
    struct xdg_api_state *state = (struct xdg_api_state *)data;
    if (width > 0) state->configured_width = width;
    if (height > 0) state->configured_height = height;
}

static void xdg_toplevel_close(void *data, struct xdg_toplevel *toplevel) {
    (void)data; (void)toplevel;
}

static void xdg_toplevel_configure_bounds(void *data, struct xdg_toplevel *toplevel, int32_t width, int32_t height) {
    (void)data; (void)toplevel; (void)width; (void)height;
}

static void xdg_toplevel_wm_capabilities(void *data, struct xdg_toplevel *toplevel, struct wl_array *capabilities) {
    (void)data; (void)toplevel; (void)capabilities;
}

static const struct xdg_toplevel_listener xdg_toplevel_listener = {
    xdg_toplevel_configure,
    xdg_toplevel_close,
    xdg_toplevel_configure_bounds,
    xdg_toplevel_wm_capabilities,
};

static void rect(uint8_t *p, int width, int height, int stride, int x, int y, int w, int h, uint8_t r, uint8_t g, uint8_t b) {
    int x2 = x + w;
    int y2 = y + h;
    if (x < 0) x = 0;
    if (y < 0) y = 0;
    if (x2 > width) x2 = width;
    if (y2 > height) y2 = height;
    for (int yy = y; yy < y2; yy++) {
        for (int xx = x; xx < x2; xx++) {
            size_t i = ((size_t)yy * (size_t)stride) + (size_t)xx * 4;
            p[i + 0] = r;
            p[i + 1] = g;
            p[i + 2] = b;
            p[i + 3] = 255;
        }
    }
}

static void draw(uint8_t *p, int width, int height, int stride) {
    rect(p, width, height, stride, 0, 0, width, height, 12, 16, 22);
    int margin = width / 20;
    if (margin < 24) margin = 24;
    int title = height / 14;
    if (title < 42) title = 42;
    rect(p, width, height, stride, margin, margin, width - margin * 2, height - margin * 2, 244, 246, 248);
    rect(p, width, height, stride, margin, margin, width - margin * 2, title, 42, 96, 130);
    rect(p, width, height, stride, margin + 24, margin + title + 28, width - margin * 2 - 48, height / 8, 255, 255, 255);
    int grid_top = margin + title + 28 + height / 8 + 32;
    int key_w = (width - margin * 2 - 72) / 4;
    int key_h = height / 13;
    for (int row = 0; row < 4; row++) {
        for (int col = 0; col < 4; col++) {
            rect(p, width, height, stride, margin + 24 + col * (key_w + 8), grid_top + row * (key_h + 10), key_w, key_h, 226, 231, 236);
        }
    }
}

static void draw_pointer_repaint(uint8_t *p, int width, int height, int stride, int x, int y) {
    draw(p, width, height, stride);
    int radius = width / 28;
    if (radius < 18) radius = 18;
    if (radius > 64) radius = 64;
    rect(p, width, height, stride, x - radius, y - radius, radius * 2, radius * 2, 25, 150, 90);
    rect(p, width, height, stride, x - radius / 3, y - radius * 2, radius * 2, radius / 2, 255, 255, 255);
}

static void draw_keyboard_repaint(uint8_t *p, int width, int height, int stride, int key) {
    draw(p, width, height, stride);
    int margin = width / 20;
    if (margin < 24) margin = 24;
    int h = height / 10;
    if (h < 80) h = 80;
    rect(p, width, height, stride, margin * 2, height - margin * 2 - h, width - margin * 4, h, 180, 52, 80);
    int marker = key % 12;
    rect(p, width, height, stride, margin * 2 + marker * 18, height - margin * 2 - h + 18, 72, h - 36, 255, 255, 255);
}
int main(void) {
    struct xdg_api_state state = {0};
    int interactive_pointer = getenv("ARCHPHENE_INTERACTIVE_POINTER") != NULL;
    int interactive_keyboard = getenv("ARCHPHENE_INTERACTIVE_KEYBOARD") != NULL;
    struct wl_display *display = wl_display_connect(NULL);
    if (display == NULL) { perror("wl_display_connect"); return 40; }

    struct wl_registry *registry = wl_display_get_registry(display);
    wl_registry_add_listener(registry, &registry_listener, &state);
    if (wl_display_roundtrip(display) < 0) { fprintf(stderr, "registry roundtrip failed\n"); return 41; }
    if (state.shm_name == 0 || state.compositor_name == 0 || state.xdg_name == 0 || state.output_name == 0 || state.seat_name == 0) {
        fprintf(stderr, "missing globals shm=%u compositor=%u xdg=%u output=%u seat=%u\n", state.shm_name, state.compositor_name, state.xdg_name, state.output_name, state.seat_name);
        return 42;
    }

    struct wl_shm *shm = (struct wl_shm *)wl_registry_bind(registry, state.shm_name, &wl_shm_interface, 1);
    struct wl_compositor *compositor = (struct wl_compositor *)wl_registry_bind(registry, state.compositor_name, &wl_compositor_interface, 1);
    struct xdg_wm_base *xdg = (struct xdg_wm_base *)wl_registry_bind(registry, state.xdg_name, &xdg_wm_base_interface, 1);
    struct wl_output *output = (struct wl_output *)wl_registry_bind(registry, state.output_name, &wl_output_interface, 2);
    struct wl_seat *seat = (struct wl_seat *)wl_registry_bind(registry, state.seat_name, &wl_seat_interface, 2);
    wl_shm_add_listener(shm, &shm_listener, &state);
    wl_output_add_listener(output, &output_listener, &state);
    wl_seat_add_listener(seat, &seat_listener, &state);
    struct wl_pointer *pointer = wl_seat_get_pointer(seat);
    wl_pointer_add_listener(pointer, &pointer_listener, &state);
    struct wl_keyboard *keyboard = wl_seat_get_keyboard(seat);
    wl_keyboard_add_listener(keyboard, &keyboard_listener, &state);
    if (wl_display_roundtrip(display) < 0 || state.shm_format_count == 0 || !state.output_done || state.seat_capabilities == 0 || !state.keyboard_keymap || !state.keyboard_keymap_fd || !state.keyboard_keymap_has_xkb || !state.keyboard_repeat_info) {
        fprintf(stderr, "environment roundtrip failed formats=%u output_done=%u seat_caps=%u keyboard_keymap=%d keymap_fd=%d keymap_xkb=%d repeat_info=%d\n", state.shm_format_count, state.output_done, state.seat_capabilities, state.keyboard_keymap, state.keyboard_keymap_fd, state.keyboard_keymap_has_xkb, state.keyboard_repeat_info);
        return 43;
    }

    struct wl_surface *surface = wl_compositor_create_surface(compositor);
    struct xdg_surface *xdg_surface = xdg_wm_base_get_xdg_surface(xdg, surface);
    struct xdg_toplevel *toplevel = xdg_surface_get_toplevel(xdg_surface);
    xdg_surface_add_listener(xdg_surface, &xdg_surface_listener, &state);
    xdg_toplevel_add_listener(toplevel, &xdg_toplevel_listener, &state);
    wl_surface_commit(surface);
    if (wl_display_roundtrip(display) < 0 || !state.surface_configured || state.configure_serial == 0) {
        fprintf(stderr, "xdg configure roundtrip failed configured=%d serial=%u\n", state.surface_configured, state.configure_serial);
        return 44;
    }

    int width = state.configured_width > 0 ? state.configured_width : 420;
    int height = state.configured_height > 0 ? state.configured_height : 260;
    if (width < 320) width = 320;
    if (height < 240) height = 240;
    if (width > 4096) width = 4096;
    if (height > 4096) height = 4096;
    int stride = width * 4;
    int frame_bytes = stride * height;
    int fd = make_memfd("archphene-wayland-api-xdg");
    if (fd < 0 || ftruncate(fd, frame_bytes) != 0) { fprintf(stderr, "memfd/ftruncate failed: %s\n", strerror(errno)); return 45; }
    uint8_t *pixels = mmap(NULL, (size_t)frame_bytes, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (pixels == MAP_FAILED) { fprintf(stderr, "mmap failed: %s\n", strerror(errno)); return 46; }
    draw(pixels, width, height, stride);
    msync(pixels, (size_t)frame_bytes, MS_SYNC);

    struct wl_shm_pool *pool = wl_shm_create_pool(shm, fd, frame_bytes);
    struct wl_buffer *buffer = wl_shm_pool_create_buffer(pool, 0, width, height, stride, WL_SHM_FORMAT_ARGB8888);
    wl_buffer_add_listener(buffer, &buffer_listener, &state);
    struct wl_callback *frame = wl_surface_frame(surface);
    wl_callback_add_listener(frame, &frame_listener, &state);
    xdg_surface_ack_configure(xdg_surface, state.configure_serial);
    wl_surface_attach(surface, buffer, 0, 0);
    wl_surface_damage(surface, 0, 0, width, height);
    wl_surface_commit(surface);
    if (!interactive_pointer) {
        if (wl_display_roundtrip(display) < 0 || !state.frame_done || !state.buffer_released || !state.pointer_entered || !state.pointer_moved || !state.pointer_button) {
            fprintf(stderr, "post-commit lifecycle failed frame_done=%d buffer_released=%d pointer_entered=%d pointer_moved=%d pointer_button=%d\n", state.frame_done, state.buffer_released, state.pointer_entered, state.pointer_moved, state.pointer_button);
            return 47;
        }
    } else {
        if (wl_display_roundtrip(display) < 0 || !state.frame_done || !state.buffer_released) {
            fprintf(stderr, "interactive initial lifecycle failed frame_done=%d buffer_released=%d\n", state.frame_done, state.buffer_released);
            return 47;
        }
        while (!state.pointer_button) {
            if (wl_display_dispatch(display) < 0) {
                fprintf(stderr, "interactive pointer dispatch failed after %d events: %s\n", state.pointer_dispatches, strerror(errno));
                return 49;
            }
            state.pointer_dispatches++;
        }
        state.frame_done = 0;
        state.buffer_released = 0;
        draw_pointer_repaint(pixels, width, height, stride, state.pointer_x, state.pointer_y);
        msync(pixels, (size_t)frame_bytes, MS_SYNC);
        wl_callback_destroy(frame);
        frame = wl_surface_frame(surface);
        wl_callback_add_listener(frame, &frame_listener, &state);
        wl_surface_attach(surface, buffer, 0, 0);
        wl_surface_damage(surface, 0, 0, width, height);
        wl_surface_commit(surface);
        if (wl_display_roundtrip(display) < 0 || !state.frame_done || !state.buffer_released) {
            fprintf(stderr, "interactive repaint failed frame_done=%d buffer_released=%d\n", state.frame_done, state.buffer_released);
            return 50;
        }
        state.real_pointer_repainted = 1;
        if (interactive_keyboard) {
            while (!state.keyboard_key || !state.keyboard_modifiers_nonzero) {
                if (wl_display_dispatch(display) < 0) {
                    fprintf(stderr, "interactive keyboard dispatch failed after %d events: %s\n", state.keyboard_dispatches, strerror(errno));
                    return 51;
                }
                state.keyboard_dispatches++;
            }
            state.frame_done = 0;
            state.buffer_released = 0;
            draw_keyboard_repaint(pixels, width, height, stride, state.keyboard_last_key);
            msync(pixels, (size_t)frame_bytes, MS_SYNC);
            wl_callback_destroy(frame);
            frame = wl_surface_frame(surface);
            wl_callback_add_listener(frame, &frame_listener, &state);
            wl_surface_attach(surface, buffer, 0, 0);
            wl_surface_damage(surface, 0, 0, width, height);
            wl_surface_commit(surface);
            if (wl_display_roundtrip(display) < 0 || !state.frame_done || !state.buffer_released) {
                fprintf(stderr, "interactive keyboard repaint failed frame_done=%d buffer_released=%d\n", state.frame_done, state.buffer_released);
                return 52;
            }
            state.real_keyboard_repainted = 1;
        }
    }

    wl_keyboard_destroy(keyboard);
    wl_pointer_destroy(pointer);
    wl_callback_destroy(frame);
    wl_buffer_destroy(buffer);
    wl_shm_pool_destroy(pool);
    xdg_toplevel_destroy(toplevel);
    xdg_surface_destroy(xdg_surface);
    wl_surface_destroy(surface);
    xdg_wm_base_destroy(xdg);
    wl_output_destroy(output);
    wl_seat_destroy(seat);
    if (wl_display_roundtrip(display) < 0) {
        fprintf(stderr, "cleanup roundtrip failed\n");
        return 48;
    }

    printf("wayland-client API xdg committed %dx%d stride=%d bytes=%d serial=%u globals=%u formats=%u output=%dx%d scale=%d seat_caps=%u seat_named=%d pointer_entered=%d pointer_moved=%d pointer_button=%d pointer_x=%d pointer_y=%d pointer_dispatches=%d real_pointer_repainted=%d keyboard_keymap=%d keyboard_keymap_fd=%d keyboard_keymap_format=%d keyboard_keymap_size=%d keyboard_keymap_xkb=%d keyboard_entered=%d keyboard_key=%d keyboard_last_key=%d keyboard_dispatches=%d keyboard_modifiers=%d keyboard_last_mods=%d keyboard_modifiers_nonzero=%d keyboard_repeat_info=%d keyboard_repeat_rate=%d keyboard_repeat_delay=%d real_keyboard_repainted=%d frame_done=%d buffer_released=%d cleanup_done=1\n",
            width, height, stride, frame_bytes, state.configure_serial, state.global_count, state.shm_format_count, state.output_width, state.output_height, state.output_scale, state.seat_capabilities, state.seat_named, state.pointer_entered, state.pointer_moved, state.pointer_button, state.pointer_x, state.pointer_y, state.pointer_dispatches, state.real_pointer_repainted, state.keyboard_keymap, state.keyboard_keymap_fd, state.keyboard_keymap_format, state.keyboard_keymap_size, state.keyboard_keymap_has_xkb, state.keyboard_entered, state.keyboard_key, state.keyboard_last_key, state.keyboard_dispatches, state.keyboard_modifiers, state.keyboard_last_mods, state.keyboard_modifiers_nonzero, state.keyboard_repeat_info, state.keyboard_repeat_rate, state.keyboard_repeat_delay, state.real_keyboard_repainted, state.frame_done, state.buffer_released);
    munmap(pixels, (size_t)frame_bytes);
    close(fd);
    wl_display_disconnect(display);
    return 0;
}

