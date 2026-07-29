#define _GNU_SOURCE

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/inotify.h>
#include <sys/stat.h>
#include <unistd.h>

static int has_entry(const char *path, const char *expected) {
    struct dirent64 **entries = NULL;
    int count = scandir64(path, &entries, NULL, alphasort64);
    if (count < 0) {
        perror("scandir64");
        return 0;
    }
    int found = 0;
    for (int index = 0; index < count; index++) {
        if (strcmp(entries[index]->d_name, expected) == 0) found = 1;
        free(entries[index]);
    }
    free(entries);
    return found;
}

int main(void) {
    const char *directory = "/usr/share/archphene-test";
    if (!has_entry(directory, "value")) {
        fputs("scandir64 did not expose the logical directory\n", stderr);
        return 1;
    }
    int descriptor = inotify_init1(IN_CLOEXEC);
    if (descriptor < 0) {
        perror("inotify_init1");
        return 2;
    }
    int watch = inotify_add_watch(descriptor, directory, IN_CREATE | IN_DELETE);
    if (watch < 0) {
        perror("inotify_add_watch");
        close(descriptor);
        return 3;
    }
    if (inotify_rm_watch(descriptor, watch) != 0 && errno != EINVAL) {
        perror("inotify_rm_watch");
        close(descriptor);
        return 4;
    }
    close(descriptor);
    const char *value = "/usr/share/archphene-test/value";
    const struct timespec times[2] = {
        {.tv_sec = 1234, .tv_nsec = 5678},
        {.tv_sec = 2345, .tv_nsec = 6789},
    };
    if (utimensat(AT_FDCWD, value, times, 0) != 0) {
        perror("utimensat");
        return 5;
    }
    struct stat metadata;
    if (stat(value, &metadata) != 0
            || metadata.st_atim.tv_sec != times[0].tv_sec
            || metadata.st_mtim.tv_sec != times[1].tv_sec) {
        fputs("utimensat did not update the logical file\n", stderr);
        return 6;
    }
    struct stat64 metadata64;
    if (fstatat64(AT_FDCWD, value, &metadata64, 0) != 0
            || metadata64.st_atim.tv_sec != times[0].tv_sec
            || metadata64.st_mtim.tv_sec != times[1].tv_sec) {
        fputs("fstatat64 did not expose the logical file\n", stderr);
        return 7;
    }
    if (chmod(value, 0600) != 0) {
        perror("chmod");
        return 8;
    }
    if (stat(value, &metadata) != 0
            || (metadata.st_mode & 0777) != 0600) {
        fputs("chmod did not update the logical file\n", stderr);
        return 9;
    }
    puts("directory-apis-ok");
    return 0;
}
