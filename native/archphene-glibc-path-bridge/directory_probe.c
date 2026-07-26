#define _GNU_SOURCE

#include <dirent.h>
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/inotify.h>
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
    puts("directory-apis-ok");
    return 0;
}
