#define _LARGEFILE64_SOURCE

#include <fcntl.h>
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
    int descriptor = creat("/tmp/.archphene-creat", 0600);
    if (descriptor < 0) {
        perror("creat");
        return 1;
    }
    if (close(descriptor) != 0
            || unlink("/tmp/.archphene-creat") != 0) {
        perror("creat cleanup");
        return 1;
    }
    const char *root = getenv("ARCHPHENE_RUNTIME_ROOT");
    char physical_file[4096];
    if (root == NULL
            || snprintf(physical_file, sizeof(physical_file),
                    "%s/tmp/.archphene-physical", root)
                    >= (int)sizeof(physical_file)) {
        fprintf(stderr, "invalid physical temporary root\n");
        return 1;
    }
    descriptor = creat(physical_file, 0600);
    if (descriptor < 0) {
        perror("physical creat");
        return 1;
    }
    if (close(descriptor) != 0 || unlink(physical_file) != 0) {
        perror("physical creat cleanup");
        return 1;
    }
    char file[] = "/tmp/.archphene-file.XXXXXX";
    descriptor = mkstemp64(file);
    if (descriptor < 0) {
        perror("mkstemp64");
        return 1;
    }
    static const char file_prefix[] = "/tmp/.archphene-file.";
    if (strncmp(file, file_prefix, sizeof(file_prefix) - 1) != 0
            || write(descriptor, "ok", 2) != 2
            || close(descriptor) != 0
            || unlink(file) != 0) {
        perror("mkstemp64 cleanup");
        return 1;
    }
    puts("temporary-directory-ok");
    return 0;
}
