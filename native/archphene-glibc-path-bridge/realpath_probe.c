#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int main(void) {
    char resolved[PATH_MAX];
    if (realpath("/var/lib/pacman", resolved) == NULL) {
        perror("realpath");
        return 1;
    }
    if (strcmp(resolved, "/var/lib/pacman") != 0) {
        fprintf(stderr, "unexpected path: %s\n", resolved);
        return 1;
    }
    puts("fortified-realpath-ok");
    return 0;
}
