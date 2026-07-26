#define _GNU_SOURCE

#include <errno.h>
#include <pty.h>
#include <stdio.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>

static int read_expected(int descriptor, const char *expected) {
    char output[128];
    size_t length = 0;
    while (length < sizeof(output) - 1) {
        ssize_t count = read(descriptor, output + length,
                sizeof(output) - 1 - length);
        if (count > 0) {
            length += (size_t)count;
            if (memmem(output, length, expected, strlen(expected)) != NULL) {
                return 0;
            }
            continue;
        }
        if (count < 0 && errno == EINTR) continue;
        break;
    }
    output[length] = '\0';
    fprintf(stderr, "unexpected PTY output: %s\n", output);
    return -1;
}

int main(void) {
    int master;
    int slave;
    if (openpty(&master, &slave, NULL, NULL, NULL) != 0
            || !isatty(slave)
            || write(slave, "openpty-ok\n", 11) != 11
            || read_expected(master, "openpty-ok") != 0) {
        perror("openpty");
        return 1;
    }
    close(slave);
    close(master);

    pid_t child = forkpty(&master, NULL, NULL, NULL);
    if (child < 0) {
        perror("forkpty");
        return 1;
    }
    if (child == 0) {
        if (getsid(0) != getpid() || getpgrp() != getpid()) {
            _exit(2);
        }
        pid_t group_child = fork();
        if (group_child < 0) _exit(2);
        if (group_child == 0) {
            _exit(setpgid(0, 0) == 0 && getpgrp() == getpid() ? 0 : 2);
        }
        int group_status;
        while (waitpid(group_child, &group_status, 0) < 0 && errno == EINTR) {}
        if (!WIFEXITED(group_status) || WEXITSTATUS(group_status) != 0) {
            _exit(2);
        }
        static const char message[] = "forkpty-ok\n";
        ssize_t ignored = write(STDOUT_FILENO, message, sizeof(message) - 1);
        (void)ignored;
        _exit(0);
    }
    int read_result = read_expected(master, "forkpty-ok");
    close(master);
    int status;
    while (waitpid(child, &status, 0) < 0 && errno == EINTR) {}
    if (read_result != 0 || !WIFEXITED(status) || WEXITSTATUS(status) != 0) {
        return 1;
    }
    puts("pty-apis-ok");
    return 0;
}
