#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <wayland-client.h>

struct probe_state {
    uint32_t shm_name;
    uint32_t compositor_name;
    uint32_t xdg_name;
    uint32_t global_count;
    uint32_t shm_format_count;
};

static void registry_global(void *data, struct wl_registry *registry, uint32_t name,
        const char *interface, uint32_t version) {
    (void)registry;
    (void)version;
    struct probe_state *state = (struct probe_state *)data;
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
    struct probe_state *state = (struct probe_state *)data;
    state->shm_format_count++;
}

static const struct wl_shm_listener shm_listener = {
    shm_format,
};

int main(void) {
    struct probe_state state = {0};
    struct wl_display *display = wl_display_connect(NULL);
    if (display == NULL) {
        perror("wl_display_connect");
        return 10;
    }

    struct wl_registry *registry = wl_display_get_registry(display);
    if (registry == NULL) {
        fprintf(stderr, "wl_display_get_registry returned null\n");
        wl_display_disconnect(display);
        return 11;
    }
    if (wl_registry_add_listener(registry, &registry_listener, &state) != 0) {
        fprintf(stderr, "wl_registry_add_listener failed\n");
        wl_registry_destroy(registry);
        wl_display_disconnect(display);
        return 12;
    }
    if (wl_display_roundtrip(display) < 0) {
        fprintf(stderr, "initial wl_display_roundtrip failed\n");
        wl_registry_destroy(registry);
        wl_display_disconnect(display);
        return 13;
    }

    if (state.shm_name == 0 || state.compositor_name == 0 || state.xdg_name == 0) {
        fprintf(stderr, "missing globals after roundtrip: wl_shm=%u wl_compositor=%u xdg_wm_base=%u total=%u\n",
                state.shm_name, state.compositor_name, state.xdg_name, state.global_count);
        wl_registry_destroy(registry);
        wl_display_disconnect(display);
        return 14;
    }

    struct wl_shm *shm = (struct wl_shm *)wl_registry_bind(registry, state.shm_name, &wl_shm_interface, 1);
    struct wl_compositor *compositor = (struct wl_compositor *)wl_registry_bind(
            registry, state.compositor_name, &wl_compositor_interface, 1);
    if (shm == NULL || compositor == NULL) {
        fprintf(stderr, "wl_registry_bind failed: shm=%p compositor=%p\n", (void *)shm, (void *)compositor);
        if (shm != NULL) wl_shm_destroy(shm);
        if (compositor != NULL) wl_compositor_destroy(compositor);
        wl_registry_destroy(registry);
        wl_display_disconnect(display);
        return 15;
    }
    if (wl_shm_add_listener(shm, &shm_listener, &state) != 0) {
        fprintf(stderr, "wl_shm_add_listener failed\n");
        wl_shm_destroy(shm);
        wl_compositor_destroy(compositor);
        wl_registry_destroy(registry);
        wl_display_disconnect(display);
        return 16;
    }
    if (wl_display_roundtrip(display) < 0) {
        fprintf(stderr, "bind wl_display_roundtrip failed\n");
        wl_shm_destroy(shm);
        wl_compositor_destroy(compositor);
        wl_registry_destroy(registry);
        wl_display_disconnect(display);
        return 17;
    }

    printf("wayland-client API probe connected globals=%u wl_shm=%u wl_compositor=%u xdg_wm_base=%u shm_formats=%u\n",
            state.global_count, state.shm_name, state.compositor_name, state.xdg_name, state.shm_format_count);
    wl_shm_destroy(shm);
    wl_compositor_destroy(compositor);
    wl_registry_destroy(registry);
    wl_display_disconnect(display);
    return state.shm_format_count > 0 ? 0 : 18;
}
