#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

extern char **environ;

static int read_argument(int descriptor, char *value, size_t capacity) {
    if (capacity == 0) return -1;
    size_t length = 0;
    while (length < capacity) {
        ssize_t count = read(descriptor, value + length, 1);
        if (count < 0 && errno == EINTR) continue;
        if (count == 0) return length == 0 ? 0 : -1;
        if (count != 1) return -1;
        if (value[length++] == '\0') return 1;
    }
    return -1;
}

static int expect_first_argument(int descriptor, const char *expected) {
    char value[256];
    return read_argument(descriptor, value, sizeof(value)) != 1
            || strcmp(value, expected) != 0;
}

static int expect_remaining_arguments(int descriptor) {
    char value[256];
    if (read_argument(descriptor, value, sizeof(value)) != 1
            || strcmp(value, "--child") != 0
            || read_argument(descriptor, value, sizeof(value)) != 1) {
        return 1;
    }
    char *end = NULL;
    long ready_descriptor = strtol(value, &end, 10);
    if (end == value || *end != '\0'
            || ready_descriptor < 0 || ready_descriptor > 1024
            || read_argument(descriptor, value, sizeof(value)) != 1
            || strcmp(value, "marker-arg") != 0
            || read_argument(descriptor, value, sizeof(value)) != 0) {
        return 1;
    }
    return 0;
}

static int inspect_child(pid_t child, const char *expected_program) {
    char path[64];
    int written = snprintf(path, sizeof(path), "/proc/%ld/exe", (long)child);
    if (written <= 0 || (size_t)written >= sizeof(path)) return 1;
    char target[4096];
    ssize_t length = readlink(path, target, sizeof(target) - 1);
    if (length <= 0 || (size_t)length >= sizeof(target)) return 1;
    target[length] = '\0';
    if (strcmp(target, expected_program) != 0) return 1;

    written = snprintf(path, sizeof(path), "/proc/%ld/cmdline", (long)child);
    if (written <= 0 || (size_t)written >= sizeof(path)) return 1;
    FILE *stream = fopen64(path, "r");
    if (stream == NULL) return 1;
    int descriptor = fileno(stream);
    int result = descriptor < 0
            || expect_first_argument(descriptor, "process-identity-probe")
            || expect_remaining_arguments(descriptor);
    fclose(stream);
    if (result != 0) return result;

    descriptor = (int)syscall(__NR_openat, AT_FDCWD, path, O_RDONLY | O_CLOEXEC);
    if (descriptor < 0) return 1;
    result = expect_first_argument(descriptor, "process-identity-probe");
    close(descriptor);
    return result;
}

int main(int argument_count, char **arguments) {
    if (argument_count == 4 && strcmp(arguments[1], "--child") == 0) {
        char *end = NULL;
        long descriptor = strtol(arguments[2], &end, 10);
        if (end == arguments[2] || *end != '\0'
                || descriptor < 0 || descriptor > 1024
                || strcmp(arguments[3], "marker-arg") != 0) {
            return 2;
        }
        char marker = 'R';
        if (write((int)descriptor, &marker, 1) != 1) return 3;
        close((int)descriptor);
        for (;;) pause();
    }
    if (argument_count != 4) return 2;
    const char *program = arguments[1];
    const char *loader = arguments[2];
    const char *library_path = arguments[3];
    const char *root = getenv("ARCHPHENE_RUNTIME_ROOT");
    if (program[0] != '/' || loader[0] != '/' || library_path[0] != '/'
            || root == NULL || root[0] != '/') {
        return 2;
    }
    size_t root_length = strlen(root);
    if (strncmp(program, root, root_length) != 0
            || program[root_length] != '/') {
        return 2;
    }

    int ready[2];
    if (pipe(ready) != 0) return 1;
    pid_t child = fork();
    if (child < 0) return 1;
    if (child == 0) {
        close(ready[0]);
        char descriptor[32];
        int written = snprintf(descriptor, sizeof(descriptor), "%d", ready[1]);
        if (written <= 0 || (size_t)written >= sizeof(descriptor)
                || setenv("ARCHPHENE_RUNTIME_PROGRAM_PATH", program, 1) != 0) {
            _exit(3);
        }
        char *child_arguments[] = {
            (char *)loader,
            "--library-path",
            (char *)library_path,
            "--argv0",
            "process-identity-probe",
            (char *)program,
            "--child",
            descriptor,
            "marker-arg",
            NULL,
        };
        syscall(__NR_execve, loader, child_arguments, environ);
        _exit(4);
    }
    close(ready[1]);
    char marker;
    ssize_t count;
    do {
        count = read(ready[0], &marker, 1);
    } while (count < 0 && errno == EINTR);
    close(ready[0]);
    int result = count != 1
            || inspect_child(child, program + root_length) != 0;
    (void)kill(child, SIGTERM);
    if (waitpid(child, NULL, 0) != child) result = 1;
    if (result == 0) puts("process-identity-ok");
    return result;
}
