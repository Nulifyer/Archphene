#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <pty.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
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

    if (openpty(&master, &slave, NULL, NULL, NULL) != 0) {
        perror("manual openpty");
        return 1;
    }
    pid_t manual_child = fork();
    if (manual_child < 0) {
        perror("manual fork");
        return 1;
    }
    if (manual_child == 0) {
        close(master);
        if (setsid() < 0 || ioctl(slave, TIOCSCTTY, 0) != 0
                || dup2(slave, STDIN_FILENO) < 0
                || dup2(slave, STDOUT_FILENO) < 0
                || dup2(slave, STDERR_FILENO) < 0) {
            _exit(2);
        }
        if (slave > STDERR_FILENO) close(slave);
        static const char message[] = "manual-pty-session-ok\n";
        ssize_t ignored = write(STDOUT_FILENO, message, sizeof(message) - 1);
        (void)ignored;
        _exit(0);
    }
    close(slave);
    int manual_read_result =
            read_expected(master, "manual-pty-session-ok");
    close(master);
    int manual_status;
    while (waitpid(manual_child, &manual_status, 0) < 0
            && errno == EINTR) {}
    if (manual_read_result != 0 || !WIFEXITED(manual_status)
            || WEXITSTATUS(manual_status) != 0) {
        return 1;
    }

    int foot_pipe[2];
    int foot_ptmx = posix_openpt(O_RDWR | O_NOCTTY);
    if (foot_ptmx < 0 || pipe(foot_pipe) != 0) {
        perror("foot-style pipe");
        return 1;
    }
    pid_t foot_child = fork();
    if (foot_child < 0) {
        perror("foot-style fork");
        return 1;
    }
    if (foot_child == 0) {
        close(foot_pipe[0]);
        const char *resolved_name = ptsname(foot_ptmx);
        char slave_name[128];
        if (resolved_name == NULL
                || strlen(resolved_name) >= sizeof(slave_name)) {
            _exit(10);
        }
        strcpy(slave_name, resolved_name);
        if (grantpt(foot_ptmx) != 0) _exit(11);
        if (unlockpt(foot_ptmx) != 0) _exit(12);
        if (close(foot_ptmx) != 0) _exit(13);
        if (setsid() < 0) _exit(14);
        int foot_slave = open(slave_name, O_RDWR);
        if (foot_slave < 0) _exit(15);
        if (ioctl(foot_slave, TIOCSCTTY, 0) != 0) _exit(16);
        static const char message[] = "foot-style-pty-session-ok";
        if (write(foot_pipe[1], message, sizeof(message) - 1)
                != sizeof(message) - 1) {
            _exit(2);
        }
        close(foot_slave);
        close(foot_pipe[1]);
        _exit(0);
    }
    close(foot_pipe[1]);
    int foot_read_result =
            read_expected(foot_pipe[0], "foot-style-pty-session-ok");
    close(foot_pipe[0]);
    close(foot_ptmx);
    int foot_status;
    while (waitpid(foot_child, &foot_status, 0) < 0 && errno == EINTR) {}
    if (foot_read_result != 0 || !WIFEXITED(foot_status)
            || WEXITSTATUS(foot_status) != 0) {
        fprintf(stderr, "foot-style PTY child status=%d\n", foot_status);
        return 1;
    }

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
