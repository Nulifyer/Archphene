#define _GNU_SOURCE

#include <dirent.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

struct linux_dirent64 {
    uint64_t inode;
    int64_t offset;
    unsigned short record_length;
    unsigned char type;
    char name[];
};

static int numeric_process_name(const char *name) {
    if (name == NULL || name[0] == '\0') return 0;
    for (const unsigned char *cursor = (const unsigned char *)name;
            *cursor != '\0'; cursor++) {
        if (*cursor < (unsigned char)'0' || *cursor > (unsigned char)'9') {
            return 0;
        }
    }
    return 1;
}

static int acceptable_process_name(const char *name, const char *self) {
    if (strcmp(name, self) == 0) return 2;
    if (numeric_process_name(name)) {
        char status[64];
        int length = snprintf(status, sizeof(status), "/proc/%s/status", name);
        if (length <= 0 || (size_t)length >= sizeof(status)) return 0;
        int descriptor = open(status, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
        if (descriptor < 0) return 0;
        close(descriptor);
    }
    return 1;
}

static int check_readdir(const char *self, int use_64_bit) {
    DIR *directory = opendir("/proc");
    if (directory == NULL) return 0;
    int found_self = 0;
    if (use_64_bit) {
        struct dirent64 *entry;
        while ((entry = readdir64(directory)) != NULL) {
            int result = acceptable_process_name(entry->d_name, self);
            if (result == 0) {
                closedir(directory);
                return 0;
            }
            if (result == 2) found_self = 1;
        }
    } else {
        struct dirent *entry;
        while ((entry = readdir(directory)) != NULL) {
            int result = acceptable_process_name(entry->d_name, self);
            if (result == 0) {
                closedir(directory);
                return 0;
            }
            if (result == 2) found_self = 1;
        }
    }
    closedir(directory);
    return found_self;
}

static int check_scandir(const char *self, int use_64_bit) {
    int found_self = 0;
    if (use_64_bit) {
        struct dirent64 **entries = NULL;
        int count = scandir64("/proc", &entries, NULL, alphasort64);
        if (count < 0) return 0;
        for (int index = 0; index < count; index++) {
            int result = acceptable_process_name(entries[index]->d_name, self);
            if (result == 0) {
                for (int cleanup = index; cleanup < count; cleanup++) {
                    free(entries[cleanup]);
                }
                for (int cleanup = 0; cleanup < index; cleanup++) {
                    free(entries[cleanup]);
                }
                free(entries);
                return 0;
            }
            if (result == 2) found_self = 1;
            free(entries[index]);
        }
        free(entries);
    } else {
        struct dirent **entries = NULL;
        int count = scandir("/proc", &entries, NULL, alphasort);
        if (count < 0) return 0;
        for (int index = 0; index < count; index++) {
            int result = acceptable_process_name(entries[index]->d_name, self);
            if (result == 0) {
                for (int cleanup = index; cleanup < count; cleanup++) {
                    free(entries[cleanup]);
                }
                for (int cleanup = 0; cleanup < index; cleanup++) {
                    free(entries[cleanup]);
                }
                free(entries);
                return 0;
            }
            if (result == 2) found_self = 1;
            free(entries[index]);
        }
        free(entries);
    }
    return found_self;
}

static int check_getdents64(const char *self) {
#ifndef SYS_getdents64
    (void)self;
    return 1;
#else
    int directory = open("/proc", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (directory < 0) return 0;
    int found_self = 0;
    char buffer[4096];
    for (;;) {
        long length = syscall(SYS_getdents64, directory, buffer, sizeof(buffer));
        if (length < 0) {
            close(directory);
            return 0;
        }
        if (length == 0) break;
        size_t offset = 0;
        while (offset < (size_t)length) {
            struct linux_dirent64 *entry =
                    (struct linux_dirent64 *)(buffer + offset);
            if (entry->record_length == 0
                    || entry->record_length > (size_t)length - offset) {
                close(directory);
                return 0;
            }
            int result = acceptable_process_name(entry->name, self);
            if (result == 0) {
                close(directory);
                return 0;
            }
            if (result == 2) found_self = 1;
            offset += entry->record_length;
        }
    }
    close(directory);
    return found_self;
#endif
}

static int readable(const char *path) {
    int descriptor = open(path, O_RDONLY | O_CLOEXEC);
    if (descriptor < 0) return 0;
    close(descriptor);
    return 1;
}

int main(void) {
    char self[32];
    int length = snprintf(self, sizeof(self), "%ld", (long)getpid());
    if (length <= 0 || (size_t)length >= sizeof(self)
            || !check_readdir(self, 0)
            || !check_readdir(self, 1)
            || !check_scandir(self, 0)
            || !check_scandir(self, 1)
            || !check_getdents64(self)
            || !readable("/proc/self/status")
            || !readable("/proc/self/fd")
            || !readable("/sys/devices/system/cpu/online")
            || !readable("/dev/null")
            || !readable("/dev/zero")
            || !readable("/dev/urandom")) {
        fputs("sandboxed kernel view is incomplete or exposes a hidden PID\n", stderr);
        return 1;
    }
    puts("kernel-view-ok");
    return 0;
}
