#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int main(void) {
    char resolved[PATH_MAX];
    errno = 0;
    if (realpath("", resolved) != NULL || errno != ENOENT) {
        fprintf(stderr, "empty realpath errno: %d\n", errno);
        return 1;
    }
    errno = 0;
    if (open("", O_RDONLY) >= 0 || errno != ENOENT) {
        fprintf(stderr, "empty open errno: %d\n", errno);
        return 1;
    }
    if (realpath("/var/lib/pacman", resolved) == NULL) {
        perror("realpath");
        return 1;
    }
    if (strcmp(resolved, "/var/lib/pacman") != 0) {
        fprintf(stderr, "unexpected path: %s\n", resolved);
        return 1;
    }
    const char *expected_program = getenv("ARCHPHENE_EXPECT_PROGRAM_PATH");
    if (expected_program != NULL) {
        if (realpath("/proc/self/exe", resolved) == NULL) {
            perror("program realpath");
            return 1;
        }
        if (strcmp(resolved, expected_program) != 0) {
            fprintf(stderr, "unexpected program path: %s\n", resolved);
            return 1;
        }
    }
    puts("fortified-realpath-ok");
    return 0;
}
