#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

extern ssize_t __readlink_chk(
        const char *path, char *buffer, size_t size, size_t buffer_size);
extern ssize_t __readlinkat_chk(int directory, const char *path, char *buffer,
        size_t size, size_t buffer_size);

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

int main(void) {
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
    return 0;
}
