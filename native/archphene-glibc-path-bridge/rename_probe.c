#include <errno.h>
#include <stdio.h>
#include <string.h>

int main(int argc, char **argv) {
    if (argc != 3) return 2;
    if (rename(argv[1], argv[2]) != 0) {
        fprintf(stderr, "rename failed: %s\n", strerror(errno));
        return 3;
    }
    return 0;
}