#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>

int main(int argc, char **argv) {
    if (argc != 2) return 2;
    if (mkdir(argv[1], 0700) != 0) {
        fprintf(stderr, "mkdir failed: %s\n", strerror(errno));
        return 3;
    }
    return 0;
}