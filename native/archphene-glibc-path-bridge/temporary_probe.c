#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

int main(void) {
    char directory[] = "/tmp/.archphene.XXXXXX";
    char *result = mkdtemp(directory);
    if (result == NULL) {
        perror("mkdtemp");
        return 1;
    }
    static const char prefix[] = "/tmp/.archphene.";
    if (result != directory
            || strncmp(directory, prefix, sizeof(prefix) - 1) != 0) {
        fprintf(stderr, "unexpected temporary directory: %s\n", directory);
        return 1;
    }
    struct stat metadata;
    if (stat(directory, &metadata) != 0 || !S_ISDIR(metadata.st_mode)) {
        perror("temporary directory stat");
        return 1;
    }
    if (rmdir(directory) != 0) {
        perror("temporary directory removal");
        return 1;
    }
    puts("temporary-directory-ok");
    return 0;
}
