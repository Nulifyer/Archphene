#include <pwd.h>
#include <stdio.h>
#include <string.h>
#include <sys/fsuid.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

int main(void) {
    if (getuid() != 0 || geteuid() != 0 || getgid() != 0 || getegid() != 0) {
        fputs("root identity was not virtualized\n", stderr);
        return 1;
    }
    if (setfsuid((uid_t)-1) != 0 || setfsgid((gid_t)-1) != 0) {
        fputs("filesystem identity was not virtualized\n", stderr);
        return 2;
    }
    struct passwd storage;
    struct passwd *result = NULL;
    char buffer[128];
    if (getpwuid_r(0, &storage, buffer, sizeof(buffer), &result) != 0
            || result != &storage
            || strcmp(result->pw_name, "archphene") != 0
            || strcmp(result->pw_dir, "/home/archphene") != 0) {
        fputs("current Linux user was not virtualized\n", stderr);
        return 3;
    }
    if (getpwnam_r("root", &storage, buffer, sizeof(buffer), &result) != 0
            || result != &storage || strcmp(result->pw_name, "root") != 0) {
        fputs("root Linux user was not retained\n", stderr);
        return 4;
    }
    if (getpwnam_r("alpm", &storage, buffer, sizeof(buffer), &result) != 0
            || result != &storage || strcmp(result->pw_name, "alpm") != 0
            || strcmp(result->pw_shell, "/usr/bin/nologin") != 0) {
        fputs("package-manager Linux user was not retained\n", stderr);
        return 5;
    }
    if (getpwuid_r(1, &storage, buffer, sizeof(buffer), &result) != 0
            || result != NULL) {
        fputs("unknown Linux user was fabricated\n", stderr);
        return 6;
    }
    struct stat metadata;
    if (stat("/proc/self/exe", &metadata) != 0
            || metadata.st_uid != 0 || metadata.st_gid != 0) {
        fputs("filesystem metadata identity was not virtualized\n", stderr);
        return 7;
    }
    return 0;
}
