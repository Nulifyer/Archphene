#include <errno.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/uio.h>
#include <sys/un.h>
#include <unistd.h>
#include <wayland-client.h>
#include "wayland-xdg-shell-client-protocol.h"

#define MAX_OBJECTS 4096

struct wl_proxy {
    int fd;
    struct wl_display *display;
    uint32_t id;
    uint32_t version;
    const struct wl_interface *interface;
    void (**listener)(void);
    void *listener_data;
    void *user_data;
};

struct wl_display {
    struct wl_proxy proxy;
    uint32_t next_id;
    struct wl_proxy *objects[MAX_OBJECTS];
};

static const struct wl_message empty_messages[] = {{"", "", NULL}};
const struct wl_interface wl_display_interface = {"wl_display", 1, 2, empty_messages, 2, empty_messages};
const struct wl_interface wl_registry_interface = {"wl_registry", 1, 1, empty_messages, 2, empty_messages};
const struct wl_interface wl_callback_interface = {"wl_callback", 1, 0, NULL, 1, empty_messages};
const struct wl_interface wl_compositor_interface = {"wl_compositor", 1, 3, empty_messages, 0, NULL};
const struct wl_interface wl_shm_pool_interface = {"wl_shm_pool", 1, 3, empty_messages, 0, NULL};
const struct wl_interface wl_shm_interface = {"wl_shm", 1, 2, empty_messages, 1, empty_messages};
const struct wl_interface wl_buffer_interface = {"wl_buffer", 1, 1, empty_messages, 1, empty_messages};
const struct wl_interface wl_surface_interface = {"wl_surface", 1, 10, empty_messages, 2, empty_messages};
const struct wl_interface wl_output_interface = {"wl_output", 2, 1, empty_messages, 6, empty_messages};
const struct wl_interface wl_seat_interface = {"wl_seat", 2, 4, empty_messages, 2, empty_messages};
const struct wl_interface wl_pointer_interface = {"wl_pointer", 2, 2, empty_messages, 5, empty_messages};
const struct wl_interface wl_keyboard_interface = {"wl_keyboard", 1, 1, empty_messages, 6, empty_messages};
const struct wl_interface xdg_wm_base_interface = {"xdg_wm_base", 1, 4, empty_messages, 1, empty_messages};
const struct wl_interface xdg_surface_interface = {"xdg_surface", 1, 5, empty_messages, 1, empty_messages};
const struct wl_interface xdg_toplevel_interface = {"xdg_toplevel", 1, 14, empty_messages, 2, empty_messages};
const struct wl_interface xdg_positioner_interface = {"xdg_positioner", 1, 10, empty_messages, 0, NULL};
const struct wl_interface xdg_popup_interface = {"xdg_popup", 1, 3, empty_messages, 3, empty_messages};

static struct wl_display *display_from_proxy(struct wl_proxy *proxy) {
    if (proxy == NULL) return NULL;
    return proxy->display;
}

static uint32_t get_u32(const uint8_t *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static void put_u32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)(v & 0xffU);
    p[1] = (uint8_t)((v >> 8) & 0xffU);
    p[2] = (uint8_t)((v >> 16) & 0xffU);
    p[3] = (uint8_t)((v >> 24) & 0xffU);
}

static int read_exact(int fd, void *buf, size_t len) {
    uint8_t *p = (uint8_t *)buf;
    size_t off = 0;
    while (off < len) {
        ssize_t n = read(fd, p + off, len - off);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (n == 0) {
            errno = ECONNRESET;
            return -1;
        }
        off += (size_t)n;
    }
    return 0;
}

static int recv_header_with_fd(int fd, uint8_t *header, int *received_fd) {
    struct iovec iov = {
        .iov_base = header,
        .iov_len = 8,
    };
    union {
        char buf[CMSG_SPACE(sizeof(int))];
        struct cmsghdr align;
    } control;
    memset(&control, 0, sizeof(control));
    struct msghdr msg;
    memset(&msg, 0, sizeof(msg));
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = control.buf;
    msg.msg_controllen = sizeof(control.buf);
    for (;;) {
        ssize_t n = recvmsg(fd, &msg, MSG_WAITALL);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (n != 8) {
            errno = ECONNRESET;
            return -1;
        }
        *received_fd = -1;
        for (struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg); cmsg != NULL; cmsg = CMSG_NXTHDR(&msg, cmsg)) {
            if (cmsg->cmsg_level == SOL_SOCKET && cmsg->cmsg_type == SCM_RIGHTS && cmsg->cmsg_len >= CMSG_LEN(sizeof(int))) {
                memcpy(received_fd, CMSG_DATA(cmsg), sizeof(int));
                break;
            }
        }
        return 0;
    }
}
static int write_all(int fd, const void *buf, size_t len) {
    const uint8_t *p = (const uint8_t *)buf;
    size_t off = 0;
    while (off < len) {
        ssize_t n = write(fd, p + off, len - off);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        off += (size_t)n;
    }
    return 0;
}

static int send_message(int fd, uint32_t object, uint16_t opcode, const uint8_t *payload, uint16_t payload_len) {
    uint8_t header[8];
    put_u32(header, object);
    put_u32(header + 4, ((uint32_t)(payload_len + 8U) << 16) | opcode);
    if (write_all(fd, header, sizeof(header)) != 0) return -1;
    if (payload_len > 0 && write_all(fd, payload, payload_len) != 0) return -1;
    return 0;
}

static int send_message_with_fd(int fd, uint32_t object, uint16_t opcode, const uint8_t *payload, uint16_t payload_len, int send_fd) {
    uint8_t message[8 + 512];
    if (payload_len > 512) {
        errno = EMSGSIZE;
        return -1;
    }
    put_u32(message, object);
    put_u32(message + 4, ((uint32_t)(payload_len + 8U) << 16) | opcode);
    if (payload_len > 0) memcpy(message + 8, payload, payload_len);

    struct iovec iov = {
        .iov_base = message,
        .iov_len = (size_t)payload_len + 8U,
    };
    union {
        char buf[CMSG_SPACE(sizeof(int))];
        struct cmsghdr align;
    } control;
    memset(&control, 0, sizeof(control));
    struct msghdr msg;
    memset(&msg, 0, sizeof(msg));
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = control.buf;
    msg.msg_controllen = sizeof(control.buf);

    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type = SCM_RIGHTS;
    cmsg->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(cmsg), &send_fd, sizeof(int));
    msg.msg_controllen = cmsg->cmsg_len;

    for (;;) {
        ssize_t written = sendmsg(fd, &msg, 0);
        if (written >= 0) return 0;
        if (errno != EINTR) return -1;
    }
}

static struct wl_proxy *new_proxy(struct wl_display *display, const struct wl_interface *interface, uint32_t version) {
    if (display->next_id >= MAX_OBJECTS) {
        errno = EMFILE;
        return NULL;
    }
    struct wl_proxy *proxy = (struct wl_proxy *)calloc(1, sizeof(*proxy));
    if (proxy == NULL) return NULL;
    proxy->fd = display->proxy.fd;
    proxy->display = display;
    proxy->id = display->next_id++;
    proxy->version = version == 0 ? 1 : version;
    proxy->interface = interface;
    display->objects[proxy->id] = proxy;
    return proxy;
}

static int connect_wayland_socket(const char *name) {
    const char *runtime = getenv("XDG_RUNTIME_DIR");
    if (runtime == NULL || runtime[0] == '\0') {
        errno = ENOENT;
        return -1;
    }
    if (name == NULL || name[0] == '\0') name = getenv("WAYLAND_DISPLAY");
    if (name == NULL || name[0] == '\0') name = "wayland-0";

    char path[sizeof(((struct sockaddr_un *)0)->sun_path)];
    int written = snprintf(path, sizeof(path), "%s/%s", runtime, name);
    if (written <= 0 || (size_t)written >= sizeof(path)) {
        errno = ENAMETOOLONG;
        return -1;
    }

    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    memcpy(addr.sun_path, path, strlen(path) + 1);
    if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) != 0) {
        int saved = errno;
        close(fd);
        errno = saved;
        return -1;
    }
    return fd;
}

struct wl_display *wl_display_connect(const char *name) {
    int fd = connect_wayland_socket(name);
    if (fd < 0) return NULL;
    struct wl_display *display = (struct wl_display *)calloc(1, sizeof(*display));
    if (display == NULL) {
        close(fd);
        return NULL;
    }
    display->proxy.fd = fd;
    display->proxy.display = display;
    display->proxy.id = 1;
    display->proxy.version = 1;
    display->proxy.interface = &wl_display_interface;
    display->next_id = 2;
    display->objects[1] = &display->proxy;
    return display;
}

void wl_display_disconnect(struct wl_display *display) {
    if (display == NULL) return;
    int fd = display->proxy.fd;
    for (uint32_t i = 2; i < display->next_id && i < MAX_OBJECTS; i++) {
        free(display->objects[i]);
    }
    close(fd);
    free(display);
}

static int send_sync(struct wl_display *display, struct wl_proxy **callback_out) {
    struct wl_proxy *callback = new_proxy(display, &wl_callback_interface, 1);
    if (callback == NULL) return -1;
    uint8_t payload[4];
    put_u32(payload, callback->id);
    if (send_message(display->proxy.fd, 1, 0, payload, sizeof(payload)) != 0) return -1;
    *callback_out = callback;
    return 0;
}

static int dispatch_event(struct wl_display *display, uint32_t stop_callback_id, int *done) {
    uint8_t header[8];
    int received_fd = -1;
    if (recv_header_with_fd(display->proxy.fd, header, &received_fd) != 0) return -1;
    uint32_t object_id = get_u32(header);
    uint32_t size_opcode = get_u32(header + 4);
    uint16_t opcode = (uint16_t)(size_opcode & 0xffffU);
    uint16_t size = (uint16_t)((size_opcode >> 16) & 0xffffU);
    if (size < 8 || size > 4096) {
        errno = EPROTO;
        return -1;
    }
    uint8_t payload[4088];
    if (read_exact(display->proxy.fd, payload, (size_t)size - 8U) != 0) return -1;
    struct wl_proxy *proxy = object_id < MAX_OBJECTS ? display->objects[object_id] : NULL;
    if (proxy == NULL) {
        if (received_fd >= 0) close(received_fd);
        return 0;
    }

    if (proxy->interface == &wl_registry_interface && opcode == 0 && proxy->listener != NULL) {
        uint32_t name = get_u32(payload);
        uint32_t string_len = get_u32(payload + 4);
        if (string_len == 0 || 8U + string_len > size) {
            errno = EPROTO;
            return -1;
        }
        const char *iface = (const char *)(payload + 8);
        uint32_t padded = (string_len + 3U) & ~3U;
        uint32_t version = get_u32(payload + 8 + padded);
        void (*global)(void *, struct wl_registry *, uint32_t, const char *, uint32_t) =
                (void (*)(void *, struct wl_registry *, uint32_t, const char *, uint32_t))proxy->listener[0];
        global(proxy->listener_data, (struct wl_registry *)proxy, name, iface, version);
    } else if (proxy->interface == &wl_shm_interface && opcode == 0 && proxy->listener != NULL) {
        uint32_t format = get_u32(payload);
        void (*format_fn)(void *, struct wl_shm *, uint32_t) =
                (void (*)(void *, struct wl_shm *, uint32_t))proxy->listener[0];
        format_fn(proxy->listener_data, (struct wl_shm *)proxy, format);
    } else if (proxy->interface == &wl_output_interface && opcode == 0 && proxy->listener != NULL) {
        int32_t x = (int32_t)get_u32(payload);
        int32_t y = (int32_t)get_u32(payload + 4);
        int32_t physical_width = (int32_t)get_u32(payload + 8);
        int32_t physical_height = (int32_t)get_u32(payload + 12);
        int32_t subpixel = (int32_t)get_u32(payload + 16);
        uint32_t make_len = get_u32(payload + 20);
        const char *make = (const char *)(payload + 24);
        uint32_t make_padded = (make_len + 3U) & ~3U;
        uint32_t model_offset = 24U + make_padded;
        uint32_t model_len = get_u32(payload + model_offset);
        const char *model = (const char *)(payload + model_offset + 4U);
        uint32_t model_padded = (model_len + 3U) & ~3U;
        int32_t transform = (int32_t)get_u32(payload + model_offset + 4U + model_padded);
        void (*geometry_fn)(void *, struct wl_output *, int32_t, int32_t, int32_t, int32_t, int32_t, const char *, const char *, int32_t) =
                (void (*)(void *, struct wl_output *, int32_t, int32_t, int32_t, int32_t, int32_t, const char *, const char *, int32_t))proxy->listener[0];
        geometry_fn(proxy->listener_data, (struct wl_output *)proxy, x, y, physical_width, physical_height, subpixel, make, model, transform);
    } else if (proxy->interface == &wl_output_interface && opcode == 1 && proxy->listener != NULL) {
        uint32_t flags = get_u32(payload);
        int32_t width = (int32_t)get_u32(payload + 4);
        int32_t height = (int32_t)get_u32(payload + 8);
        int32_t refresh = (int32_t)get_u32(payload + 12);
        void (*mode_fn)(void *, struct wl_output *, uint32_t, int32_t, int32_t, int32_t) =
                (void (*)(void *, struct wl_output *, uint32_t, int32_t, int32_t, int32_t))proxy->listener[1];
        mode_fn(proxy->listener_data, (struct wl_output *)proxy, flags, width, height, refresh);
    } else if (proxy->interface == &wl_output_interface && opcode == 2 && proxy->listener != NULL) {
        void (*done_fn)(void *, struct wl_output *) =
                (void (*)(void *, struct wl_output *))proxy->listener[2];
        done_fn(proxy->listener_data, (struct wl_output *)proxy);
    } else if (proxy->interface == &wl_output_interface && opcode == 3 && proxy->listener != NULL) {
        int32_t factor = (int32_t)get_u32(payload);
        void (*scale_fn)(void *, struct wl_output *, int32_t) =
                (void (*)(void *, struct wl_output *, int32_t))proxy->listener[3];
        scale_fn(proxy->listener_data, (struct wl_output *)proxy, factor);
    } else if (proxy->interface == &wl_seat_interface && opcode == 0 && proxy->listener != NULL) {
        uint32_t capabilities = get_u32(payload);
        void (*capabilities_fn)(void *, struct wl_seat *, uint32_t) =
                (void (*)(void *, struct wl_seat *, uint32_t))proxy->listener[0];
        capabilities_fn(proxy->listener_data, (struct wl_seat *)proxy, capabilities);
    } else if (proxy->interface == &wl_pointer_interface && opcode == 0 && proxy->listener != NULL) {
        uint32_t serial = get_u32(payload);
        uint32_t surface_id = get_u32(payload + 4);
        int32_t sx = (int32_t)get_u32(payload + 8);
        int32_t sy = (int32_t)get_u32(payload + 12);
        struct wl_proxy *surface = surface_id < MAX_OBJECTS ? display->objects[surface_id] : NULL;
        void (*enter_fn)(void *, struct wl_pointer *, uint32_t, struct wl_surface *, wl_fixed_t, wl_fixed_t) =
                (void (*)(void *, struct wl_pointer *, uint32_t, struct wl_surface *, wl_fixed_t, wl_fixed_t))proxy->listener[0];
        enter_fn(proxy->listener_data, (struct wl_pointer *)proxy, serial, (struct wl_surface *)surface, sx, sy);
    } else if (proxy->interface == &wl_pointer_interface && opcode == 2 && proxy->listener != NULL) {
        uint32_t time = get_u32(payload);
        int32_t sx = (int32_t)get_u32(payload + 4);
        int32_t sy = (int32_t)get_u32(payload + 8);
        void (*motion_fn)(void *, struct wl_pointer *, uint32_t, wl_fixed_t, wl_fixed_t) =
                (void (*)(void *, struct wl_pointer *, uint32_t, wl_fixed_t, wl_fixed_t))proxy->listener[2];
        motion_fn(proxy->listener_data, (struct wl_pointer *)proxy, time, sx, sy);
    } else if (proxy->interface == &wl_keyboard_interface && opcode == 0 && proxy->listener != NULL) {
        uint32_t format = get_u32(payload);
        uint32_t keymap_size = get_u32(payload + 4);
        void (*keymap_fn)(void *, struct wl_keyboard *, uint32_t, int32_t, uint32_t) =
                (void (*)(void *, struct wl_keyboard *, uint32_t, int32_t, uint32_t))proxy->listener[0];
        keymap_fn(proxy->listener_data, (struct wl_keyboard *)proxy, format, received_fd, keymap_size);
        received_fd = -1;
    } else if (proxy->interface == &wl_keyboard_interface && opcode == 1 && proxy->listener != NULL) {
        uint32_t serial = get_u32(payload);
        uint32_t surface_id = get_u32(payload + 4);
        uint32_t keys_size = get_u32(payload + 8);
        struct wl_proxy *surface = surface_id < MAX_OBJECTS ? display->objects[surface_id] : NULL;
        struct wl_array keys;
        keys.size = keys_size;
        keys.alloc = keys_size;
        keys.data = keys_size == 0 ? NULL : payload + 12;
        void (*enter_fn)(void *, struct wl_keyboard *, uint32_t, struct wl_surface *, struct wl_array *) =
                (void (*)(void *, struct wl_keyboard *, uint32_t, struct wl_surface *, struct wl_array *))proxy->listener[1];
        enter_fn(proxy->listener_data, (struct wl_keyboard *)proxy, serial, (struct wl_surface *)surface, &keys);
    } else if (proxy->interface == &wl_keyboard_interface && opcode == 3 && proxy->listener != NULL) {
        uint32_t serial = get_u32(payload);
        uint32_t time = get_u32(payload + 4);
        uint32_t key = get_u32(payload + 8);
        uint32_t state = get_u32(payload + 12);
        void (*key_fn)(void *, struct wl_keyboard *, uint32_t, uint32_t, uint32_t, uint32_t) =
                (void (*)(void *, struct wl_keyboard *, uint32_t, uint32_t, uint32_t, uint32_t))proxy->listener[3];
        key_fn(proxy->listener_data, (struct wl_keyboard *)proxy, serial, time, key, state);
    } else if (proxy->interface == &wl_keyboard_interface && opcode == 4 && proxy->listener != NULL) {
        uint32_t serial = get_u32(payload);
        uint32_t mods_depressed = get_u32(payload + 4);
        uint32_t mods_latched = get_u32(payload + 8);
        uint32_t mods_locked = get_u32(payload + 12);
        uint32_t group = get_u32(payload + 16);
        void (*modifiers_fn)(void *, struct wl_keyboard *, uint32_t, uint32_t, uint32_t, uint32_t, uint32_t) =
                (void (*)(void *, struct wl_keyboard *, uint32_t, uint32_t, uint32_t, uint32_t, uint32_t))proxy->listener[4];
        modifiers_fn(proxy->listener_data, (struct wl_keyboard *)proxy, serial, mods_depressed, mods_latched, mods_locked, group);
    } else if (proxy->interface == &wl_keyboard_interface && opcode == 5 && proxy->listener != NULL) {
        int32_t rate = (int32_t)get_u32(payload);
        int32_t delay = (int32_t)get_u32(payload + 4);
        void (*repeat_fn)(void *, struct wl_keyboard *, int32_t, int32_t) =
                (void (*)(void *, struct wl_keyboard *, int32_t, int32_t))proxy->listener[5];
        repeat_fn(proxy->listener_data, (struct wl_keyboard *)proxy, rate, delay);
    } else if (proxy->interface == &wl_pointer_interface && opcode == 3 && proxy->listener != NULL) {
        uint32_t serial = get_u32(payload);
        uint32_t time = get_u32(payload + 4);
        uint32_t button = get_u32(payload + 8);
        uint32_t state = get_u32(payload + 12);
        void (*button_fn)(void *, struct wl_pointer *, uint32_t, uint32_t, uint32_t, uint32_t) =
                (void (*)(void *, struct wl_pointer *, uint32_t, uint32_t, uint32_t, uint32_t))proxy->listener[3];
        button_fn(proxy->listener_data, (struct wl_pointer *)proxy, serial, time, button, state);
    } else if (proxy->interface == &wl_seat_interface && opcode == 1 && proxy->listener != NULL) {
        const char *name = (const char *)(payload + 4);
        void (*name_fn)(void *, struct wl_seat *, const char *) =
                (void (*)(void *, struct wl_seat *, const char *))proxy->listener[1];
        name_fn(proxy->listener_data, (struct wl_seat *)proxy, name);
    } else if (proxy->interface == &xdg_toplevel_interface && opcode == 0 && proxy->listener != NULL) {
        int32_t width = (int32_t)get_u32(payload);
        int32_t height = (int32_t)get_u32(payload + 4);
        struct wl_array states;
        memset(&states, 0, sizeof(states));
        void (*configure_fn)(void *, struct xdg_toplevel *, int32_t, int32_t, struct wl_array *) =
                (void (*)(void *, struct xdg_toplevel *, int32_t, int32_t, struct wl_array *))proxy->listener[0];
        configure_fn(proxy->listener_data, (struct xdg_toplevel *)proxy, width, height, &states);
    } else if (proxy->interface == &xdg_surface_interface && opcode == 0 && proxy->listener != NULL) {
        uint32_t serial = get_u32(payload);
        void (*configure_fn)(void *, struct xdg_surface *, uint32_t) =
                (void (*)(void *, struct xdg_surface *, uint32_t))proxy->listener[0];
        configure_fn(proxy->listener_data, (struct xdg_surface *)proxy, serial);
    } else if (proxy->interface == &wl_buffer_interface && opcode == 0 && proxy->listener != NULL) {
        void (*release_fn)(void *, struct wl_buffer *) =
                (void (*)(void *, struct wl_buffer *))proxy->listener[0];
        release_fn(proxy->listener_data, (struct wl_buffer *)proxy);
    } else if (proxy->interface == &wl_callback_interface && opcode == 0) {
        uint32_t callback_data = size >= 12 ? get_u32(payload) : 0;
        if (proxy->listener != NULL) {
            void (*done_fn)(void *, struct wl_callback *, uint32_t) =
                    (void (*)(void *, struct wl_callback *, uint32_t))proxy->listener[0];
            done_fn(proxy->listener_data, (struct wl_callback *)proxy, callback_data);
        }
        if (object_id == stop_callback_id) *done = 1;
    }
    if (received_fd >= 0) close(received_fd);
    return 0;
}

int wl_display_roundtrip(struct wl_display *display) {
    struct wl_proxy *callback = NULL;
    if (send_sync(display, &callback) != 0) return -1;
    int done = 0;
    while (!done) {
        if (dispatch_event(display, callback->id, &done) != 0) return -1;
    }
    wl_proxy_destroy(callback);
    return 0;
}

int wl_display_dispatch(struct wl_display *display) {
    int done = 0;
    if (dispatch_event(display, 0, &done) != 0) return -1;
    return 1;
}
int wl_display_flush(struct wl_display *display) {
    (void)display;
    return 0;
}

int wl_proxy_add_listener(struct wl_proxy *proxy, void (**implementation)(void), void *data) {
    if (proxy == NULL) return -1;
    proxy->listener = implementation;
    proxy->listener_data = data;
    return 0;
}

const void *wl_proxy_get_listener(struct wl_proxy *proxy) {
    return proxy == NULL ? NULL : proxy->listener;
}

uint32_t wl_proxy_get_version(struct wl_proxy *proxy) {
    return proxy == NULL ? 0 : proxy->version;
}

uint32_t wl_proxy_get_id(struct wl_proxy *proxy) {
    return proxy == NULL ? 0 : proxy->id;
}

void wl_proxy_set_user_data(struct wl_proxy *proxy, void *user_data) {
    if (proxy != NULL) proxy->user_data = user_data;
}

void *wl_proxy_get_user_data(struct wl_proxy *proxy) {
    return proxy == NULL ? NULL : proxy->user_data;
}

void wl_proxy_destroy(struct wl_proxy *proxy) {
    if (proxy == NULL) return;
    struct wl_display *display = display_from_proxy(proxy);
    if (display != NULL && proxy->id < MAX_OBJECTS && display->objects[proxy->id] == proxy) {
        display->objects[proxy->id] = NULL;
    }
    if (proxy->id != 1) free(proxy);
}

struct wl_proxy *wl_proxy_marshal_flags(struct wl_proxy *proxy, uint32_t opcode,
        const struct wl_interface *interface, uint32_t version, uint32_t flags, ...) {
    (void)flags;
    struct wl_display *display = display_from_proxy(proxy);
    if (display == NULL) return NULL;

    struct wl_proxy *created = NULL;
    va_list ap;
    va_start(ap, flags);
    if (proxy->interface == &wl_display_interface && opcode == 0) {
        created = new_proxy(display, interface, version);
        if (created != NULL) {
            uint8_t payload[4];
            put_u32(payload, created->id);
            send_message(display->proxy.fd, proxy->id, 0, payload, sizeof(payload));
        }
    } else if (proxy->interface == &wl_display_interface && opcode == 1) {
        created = new_proxy(display, interface, version);
        if (created != NULL) {
            uint8_t payload[4];
            put_u32(payload, created->id);
            send_message(display->proxy.fd, proxy->id, 1, payload, sizeof(payload));
        }
    } else if (proxy->interface == &wl_registry_interface && opcode == 0) {
        uint32_t name = va_arg(ap, uint32_t);
        const char *iface = va_arg(ap, const char *);
        uint32_t bind_version = va_arg(ap, uint32_t);
        (void)va_arg(ap, void *);
        created = new_proxy(display, interface, bind_version);
        if (created != NULL) {
            uint32_t len = (uint32_t)strlen(iface) + 1U;
            uint32_t padded = (len + 3U) & ~3U;
            uint8_t payload[512];
            memset(payload, 0, sizeof(payload));
            put_u32(payload, name);
            put_u32(payload + 4, len);
            memcpy(payload + 8, iface, len);
            put_u32(payload + 8 + padded, bind_version);
            put_u32(payload + 12 + padded, created->id);
            send_message(display->proxy.fd, proxy->id, 0, payload, (uint16_t)(16U + padded));
        }
    } else if (proxy->interface == &wl_shm_interface && opcode == 0) {
        (void)va_arg(ap, void *);
        int fd_arg = va_arg(ap, int);
        int32_t size = va_arg(ap, int32_t);
        created = new_proxy(display, interface, version);
        if (created != NULL) {
            uint8_t payload[8];
            put_u32(payload, created->id);
            put_u32(payload + 4, (uint32_t)size);
            send_message_with_fd(display->proxy.fd, proxy->id, 0, payload, sizeof(payload), fd_arg);
        }
    } else if (proxy->interface == &wl_shm_pool_interface && opcode == 0) {
        (void)va_arg(ap, void *);
        int32_t offset = va_arg(ap, int32_t);
        int32_t width = va_arg(ap, int32_t);
        int32_t height = va_arg(ap, int32_t);
        int32_t stride = va_arg(ap, int32_t);
        uint32_t format = va_arg(ap, uint32_t);
        created = new_proxy(display, interface, version);
        if (created != NULL) {
            uint8_t payload[24];
            put_u32(payload, created->id);
            put_u32(payload + 4, (uint32_t)offset);
            put_u32(payload + 8, (uint32_t)width);
            put_u32(payload + 12, (uint32_t)height);
            put_u32(payload + 16, (uint32_t)stride);
            put_u32(payload + 20, format);
            send_message(display->proxy.fd, proxy->id, 0, payload, sizeof(payload));
        }
    } else if (proxy->interface == &wl_compositor_interface && opcode == 0) {
        (void)va_arg(ap, void *);
        created = new_proxy(display, interface, version);
        if (created != NULL) {
            uint8_t payload[4];
            put_u32(payload, created->id);
            send_message(display->proxy.fd, proxy->id, 0, payload, sizeof(payload));
        }
    } else if (proxy->interface == &wl_seat_interface && opcode == 1) {
        (void)va_arg(ap, void *);
        created = new_proxy(display, interface, version);
        if (created != NULL) {
            uint8_t payload[4];
            put_u32(payload, created->id);
            send_message(display->proxy.fd, proxy->id, 1, payload, sizeof(payload));
        }    } else if (proxy->interface == &wl_seat_interface && opcode == 0) {
        (void)va_arg(ap, void *);
        created = new_proxy(display, interface, version);
        if (created != NULL) {
            uint8_t payload[4];
            put_u32(payload, created->id);
            send_message(display->proxy.fd, proxy->id, 0, payload, sizeof(payload));
        }
    } else if (proxy->interface == &wl_surface_interface && opcode == 1) {
        struct wl_proxy *buffer = va_arg(ap, struct wl_proxy *);
        int32_t x = va_arg(ap, int32_t);
        int32_t y = va_arg(ap, int32_t);
        uint8_t payload[12];
        put_u32(payload, buffer == NULL ? 0 : buffer->id);
        put_u32(payload + 4, (uint32_t)x);
        put_u32(payload + 8, (uint32_t)y);
        send_message(display->proxy.fd, proxy->id, 1, payload, sizeof(payload));
    } else if (proxy->interface == &wl_surface_interface && opcode == 2) {
        int32_t x = va_arg(ap, int32_t);
        int32_t y = va_arg(ap, int32_t);
        int32_t width = va_arg(ap, int32_t);
        int32_t height = va_arg(ap, int32_t);
        uint8_t payload[16];
        put_u32(payload, (uint32_t)x);
        put_u32(payload + 4, (uint32_t)y);
        put_u32(payload + 8, (uint32_t)width);
        put_u32(payload + 12, (uint32_t)height);
        send_message(display->proxy.fd, proxy->id, 2, payload, sizeof(payload));
    } else if (proxy->interface == &wl_surface_interface && opcode == 3) {
        (void)va_arg(ap, void *);
        created = new_proxy(display, interface, version);
        if (created != NULL) {
            uint8_t payload[4];
            put_u32(payload, created->id);
            send_message(display->proxy.fd, proxy->id, 3, payload, sizeof(payload));
        }
    } else if (proxy->interface == &wl_surface_interface && opcode == 6) {
        send_message(display->proxy.fd, proxy->id, 6, NULL, 0);
    } else if (proxy->interface == &xdg_wm_base_interface && opcode == 2) {
        (void)va_arg(ap, void *);
        struct wl_proxy *surface = va_arg(ap, struct wl_proxy *);
        created = new_proxy(display, interface, version);
        if (created != NULL) {
            uint8_t payload[8];
            put_u32(payload, created->id);
            put_u32(payload + 4, surface == NULL ? 0 : surface->id);
            send_message(display->proxy.fd, proxy->id, 2, payload, sizeof(payload));
        }
    } else if (proxy->interface == &xdg_surface_interface && opcode == 1) {
        (void)va_arg(ap, void *);
        created = new_proxy(display, interface, version);
        if (created != NULL) {
            uint8_t payload[4];
            put_u32(payload, created->id);
            send_message(display->proxy.fd, proxy->id, 1, payload, sizeof(payload));
        }
    } else if (proxy->interface == &xdg_surface_interface && opcode == 4) {
        uint32_t serial = va_arg(ap, uint32_t);
        uint8_t payload[4];
        put_u32(payload, serial);
        send_message(display->proxy.fd, proxy->id, 4, payload, sizeof(payload));
    } else {
        if ((flags & WL_MARSHAL_FLAG_DESTROY) != 0) {
            send_message(display->proxy.fd, proxy->id, opcode, NULL, 0);
            wl_proxy_destroy(proxy);
        }
    }
    va_end(ap);
    return created;
}

struct wl_proxy *wl_proxy_marshal_array_flags(struct wl_proxy *proxy, uint32_t opcode,
        const struct wl_interface *interface, uint32_t version, uint32_t flags, union wl_argument *args) {
    (void)args;
    return wl_proxy_marshal_flags(proxy, opcode, interface, version, flags, NULL);
}

void wl_proxy_marshal(struct wl_proxy *proxy, uint32_t opcode, ...) {
    (void)wl_proxy_marshal_flags(proxy, opcode, NULL, wl_proxy_get_version(proxy), 0, NULL);
}

void wl_proxy_marshal_array(struct wl_proxy *proxy, uint32_t opcode, union wl_argument *args) {
    (void)args;
    (void)wl_proxy_marshal_flags(proxy, opcode, NULL, wl_proxy_get_version(proxy), 0, NULL);
}




