#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

extern ssize_t __readlink_chk(
        const char *path, char *buffer, size_t size, size_t buffer_size);
extern ssize_t __readlinkat_chk(int directory, const char *path, char *buffer,
        size_t size, size_t buffer_size);
extern char **environ;

static int expect_target(
        const char *label, ssize_t length, char *target, const char *expected) {
    if (length < 0) {
        perror(label);
        return errno == 0 ? 1 : errno;
    }
    target[length] = '\0';
    if (target[0] != '/' || (expected != NULL && strcmp(target, expected) != 0)) {
        fprintf(stderr, "%s returned unexpected target: %s\n", label, target);
        return 1;
    }
    return 0;
}

static int cross_process_target(const char *self, const char *expected) {
    int ready[2];
    if (pipe(ready) != 0) {
        perror("pipe");
        return 1;
    }
    pid_t child = fork();
    if (child < 0) {
        perror("fork");
        return 1;
    }
    if (child == 0) {
        close(ready[0]);
        char descriptor[32];
        int written = snprintf(
                descriptor, sizeof(descriptor), "%d", ready[1]);
        if (written <= 0 || (size_t)written >= sizeof(descriptor)) _exit(2);
        char *arguments[] = {
            (char *)self, "--cross-process-child", descriptor, NULL
        };
        const char *program = getenv("ARCHPHENE_RUNTIME_PROGRAM_PATH");
        const char *executable =
                self[0] == '/' ? self
                : program != NULL && program[0] == '/' ? program
                : self;
        syscall(__NR_execve, executable, arguments, environ);
        _exit(3);
    }
    close(ready[1]);
    char marker;
    ssize_t ready_count;
    do {
        ready_count = read(ready[0], &marker, 1);
    } while (ready_count < 0 && errno == EINTR);
    close(ready[0]);
    if (ready_count != 1) {
        (void)waitpid(child, NULL, 0);
        return 1;
    }

    char path[64];
    int written = snprintf(path, sizeof(path), "/proc/%ld/exe", (long)child);
    char target[4096];
    int result = written <= 0 || (size_t)written >= sizeof(path);
    if (!result) {
        ssize_t length = readlink(path, target, sizeof(target) - 1);
        result = expect_target("cross-process readlink", length, target, expected);
    }
    if (!result) {
        ssize_t length = __readlink_chk(
                path, target, sizeof(target) - 1, sizeof(target));
        result = expect_target(
                "cross-process fortified readlink", length, target, expected);
    }
    if (!result) {
        ssize_t length = syscall(
                __NR_readlinkat, AT_FDCWD, path, target, sizeof(target) - 1);
        result = expect_target(
                "cross-process raw readlinkat", length, target, expected);
    }
    (void)kill(child, SIGTERM);
    int status;
    if (waitpid(child, &status, 0) != child) result = 1;
    return result;
}

int main(int argument_count, char **arguments) {
    if (argument_count == 3
            && strcmp(arguments[1], "--cross-process-child") == 0) {
        char *end = NULL;
        long descriptor = strtol(arguments[2], &end, 10);
        if (end == arguments[2] || *end != '\0'
                || descriptor < 0 || descriptor > 1024) {
            return 2;
        }
        char marker = 'R';
        if (write((int)descriptor, &marker, 1) != 1) return 3;
        close((int)descriptor);
        for (;;) pause();
    }
    char target[4096];
    ssize_t length = readlink("/proc/self/exe", target, sizeof(target) - 1);
    int result = expect_target("readlink", length, target, NULL);
    if (result != 0) return result;
    puts(target);
    char expected[4096];
    memcpy(expected, target, (size_t)length + 1);

    length = __readlink_chk(
            "/proc/self/exe", target, sizeof(target) - 1, sizeof(target));
    result = expect_target("fortified readlink", length, target, expected);
    if (result != 0) return result;

    length = __readlinkat_chk(
            AT_FDCWD, "/proc/self/exe", target,
            sizeof(target) - 1, sizeof(target));
    result = expect_target("fortified readlinkat", length, target, expected);
    if (result != 0) return result;

    length = syscall(
            __NR_readlinkat, AT_FDCWD, "/proc/self/exe",
            target, sizeof(target) - 1);
    result = expect_target("raw readlinkat", length, target, expected);
    if (result != 0) return result;

#ifdef __NR_readlink
    length = syscall(__NR_readlink, "/proc/self/exe", target, sizeof(target) - 1);
    result = expect_target("raw readlink", length, target, expected);
    if (result != 0) return result;
#endif
    return cross_process_target(arguments[0], expected);
}
