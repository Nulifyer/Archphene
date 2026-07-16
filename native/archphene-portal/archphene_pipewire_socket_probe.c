#include <errno.h>
#include <signal.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

static volatile sig_atomic_t running = 1;
static const char *socket_path;

static void stop_running(int signal_number) {
    (void)signal_number;
    running = 0;
}

int main(int argc, char **argv) {
    if (argc != 2 || argv[1][0] != '/' ||
            strlen(argv[1]) >= sizeof(((struct sockaddr_un *)0)->sun_path)) {
        fprintf(stderr, "usage: %s /absolute/socket/path\n", argv[0]);
        return 2;
    }
    socket_path = argv[1];
    signal(SIGINT, stop_running);
    signal(SIGTERM, stop_running);
    int server = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (server < 0) {
        perror("socket");
        return 70;
    }
    struct sockaddr_un address = {0};
    address.sun_family = AF_UNIX;
    strcpy(address.sun_path, socket_path);
    unlink(socket_path);
    if (bind(server, (struct sockaddr *)&address,
            (socklen_t)(offsetof(struct sockaddr_un, sun_path)
                    + strlen(socket_path) + 1)) != 0
            || listen(server, 4) != 0) {
        perror("bind/listen");
        close(server);
        unlink(socket_path);
        return 70;
    }
    while (running) {
        int client = accept4(server, NULL, NULL, SOCK_CLOEXEC);
        if (client < 0) {
            if (errno == EINTR) continue;
            perror("accept");
            break;
        }
        char input[4];
        size_t received = 0;
        while (received < sizeof(input)) {
            ssize_t count = read(client, input + received, sizeof(input) - received);
            if (count < 0 && errno == EINTR) continue;
            if (count <= 0) break;
            received += (size_t)count;
        }
        if (received == sizeof(input) && memcmp(input, "PING", 4) == 0) {
            const char response[] = {'P', 'O', 'N', 'G'};
            size_t sent = 0;
            while (sent < 4) {
                ssize_t count = write(client, response + sent, 4 - sent);
                if (count < 0 && errno == EINTR) continue;
                if (count <= 0) break;
                sent += (size_t)count;
            }
        }
        close(client);
    }
    close(server);
    unlink(socket_path);
    return 0;
}
