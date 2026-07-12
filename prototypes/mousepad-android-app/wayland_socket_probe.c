#include <errno.h>
#include <stdio.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

int main(void) {
    const char *runtime_dir = getenv("XDG_RUNTIME_DIR");
    const char *display = getenv("WAYLAND_DISPLAY");
    const char *abstract_socket = getenv("ARCHPHENE_WAYLAND_ABSTRACT");
    if (display == NULL || display[0] == '\0') {
        fputs("WAYLAND_DISPLAY is not set\n", stderr);
        return 3;
    }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    socklen_t addr_len;
    char path[sizeof(addr.sun_path)];

    if (abstract_socket != NULL && strcmp(abstract_socket, "1") == 0) {
        size_t display_len = strlen(display);
        if (display_len + 1 > sizeof(addr.sun_path)) {
            fprintf(stderr, "abstract Wayland socket name is too long: %s\n", display);
            return 4;
        }
        addr.sun_path[0] = '\0';
        memcpy(addr.sun_path + 1, display, display_len);
        addr_len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + display_len);
        snprintf(path, sizeof(path), "abstract:%s", display);
    } else {
        if (runtime_dir == NULL || runtime_dir[0] == '\0') {
            fputs("XDG_RUNTIME_DIR is not set\n", stderr);
            return 2;
        }
        int written = snprintf(path, sizeof(path), "%s/%s", runtime_dir, display);
        if (written < 0 || (size_t)written >= sizeof(path)) {
            fprintf(stderr, "Wayland socket path is too long: %s/%s\n", runtime_dir, display);
            return 4;
        }
        memcpy(addr.sun_path, path, strlen(path) + 1);
        addr_len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + strlen(path) + 1);
    }

    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        fprintf(stderr, "socket(AF_UNIX) failed: %s\n", strerror(errno));
        return 5;
    }

    if (connect(fd, (struct sockaddr *)&addr, addr_len) != 0) {
        fprintf(stderr, "connect(%s) failed: %s\n", path, strerror(errno));
        close(fd);
        return 6;
    }

    const char hello[] = "ARCHPHENE_WAYLAND_PROBE\n";
    if (write(fd, hello, sizeof(hello) - 1) != (ssize_t)(sizeof(hello) - 1)) {
        fprintf(stderr, "write probe failed: %s\n", strerror(errno));
        close(fd);
        return 7;
    }

    char reply[128];
    ssize_t n = read(fd, reply, sizeof(reply) - 1);
    if (n < 0) {
        fprintf(stderr, "read reply failed: %s\n", strerror(errno));
        close(fd);
        return 8;
    }
    reply[n] = '\0';
    printf("connected to %s\nbridge replied: %s", path, reply);
    close(fd);
    return 0;
}