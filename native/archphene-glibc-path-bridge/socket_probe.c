#define _GNU_SOURCE

#include <errno.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>

static void fail(const char *operation) {
    perror(operation);
    exit(EXIT_FAILURE);
}

static struct sockaddr_un address_for(const char *path, socklen_t *length) {
    struct sockaddr_un address;
    memset(&address, 0, sizeof(address));
    address.sun_family = AF_UNIX;
    size_t path_length = strlen(path);
    if (path_length >= sizeof(address.sun_path)) {
        errno = ENAMETOOLONG;
        fail("socket path");
    }
    memcpy(address.sun_path, path, path_length + 1);
    *length = (socklen_t)(offsetof(struct sockaddr_un, sun_path)
            + path_length + 1);
    return address;
}

int main(void) {
    const char path[] = "/run/archphene-path-bridge-test.sock";
    socklen_t address_length;
    struct sockaddr_un address = address_for(path, &address_length);
    int server = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (server < 0) fail("server socket");
    if (bind(server, (const struct sockaddr *)&address, address_length) != 0) {
        fail("bind");
    }
    if (listen(server, 1) != 0) fail("listen");

    pid_t child = fork();
    if (child < 0) fail("fork");
    if (child == 0) {
        int client = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (client < 0) fail("client socket");
        if (connect(client, (const struct sockaddr *)&address,
                    address_length) != 0) {
            fail("connect");
        }
        const char message[] = "wayland";
        if (write(client, message, sizeof(message)) != (ssize_t)sizeof(message)) {
            fail("write");
        }
        if (close(client) != 0) fail("close client");
        _exit(EXIT_SUCCESS);
    }

    int client = accept4(server, NULL, NULL, SOCK_CLOEXEC);
    if (client < 0) fail("accept");
    char message[8];
    if (read(client, message, sizeof(message)) != (ssize_t)sizeof(message)
            || memcmp(message, "wayland", sizeof(message)) != 0) {
        fputs("socket payload mismatch\n", stderr);
        return EXIT_FAILURE;
    }
    if (close(client) != 0 || close(server) != 0) fail("close server");
    int status;
    if (waitpid(child, &status, 0) != child
            || !WIFEXITED(status) || WEXITSTATUS(status) != 0) {
        fputs("socket client failed\n", stderr);
        return EXIT_FAILURE;
    }
    puts("unix-socket-bridge-passed");
    return EXIT_SUCCESS;
}
