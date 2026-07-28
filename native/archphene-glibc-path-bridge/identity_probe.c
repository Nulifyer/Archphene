#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <linux/stat.h>
#include <pwd.h>
#include <stdio.h>
#include <string.h>
#include <sys/fsuid.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <unistd.h>

int main(int argc, char **argv) {
    uid_t expected = argc == 2 && strcmp(argv[1], "--root") == 0 ? 0 : 1000;
    const char *expected_name = expected == 0 ? "root" : "archphene";
    uid_t real_uid = (uid_t)-1;
    uid_t effective_uid = (uid_t)-1;
    uid_t saved_uid = (uid_t)-1;
    gid_t real_gid = (gid_t)-1;
    gid_t effective_gid = (gid_t)-1;
    gid_t saved_gid = (gid_t)-1;
    if (getuid() != expected || geteuid() != expected
            || getgid() != expected || getegid() != expected) {
        fputs("Linux identity was not virtualized\n", stderr);
        return 1;
    }
    if (getresuid(&real_uid, &effective_uid, &saved_uid) != 0
            || getresgid(&real_gid, &effective_gid, &saved_gid) != 0
            || real_uid != expected || effective_uid != expected
            || saved_uid != expected || real_gid != expected
            || effective_gid != expected || saved_gid != expected) {
        fputs("saved Linux identity was not virtualized\n", stderr);
        return 9;
    }
    if ((uid_t)setfsuid((uid_t)-1) != expected
            || (gid_t)setfsgid((gid_t)-1) != expected) {
        fputs("filesystem identity was not virtualized\n", stderr);
        return 2;
    }
    struct passwd storage;
    struct passwd *result = NULL;
    char buffer[128];
    if (getpwuid_r(expected, &storage, buffer, sizeof(buffer), &result) != 0
            || result != &storage
            || strcmp(result->pw_name, expected_name) != 0
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
            || metadata.st_uid != expected || metadata.st_gid != expected) {
        fputs("filesystem metadata identity was not virtualized\n", stderr);
        return 7;
    }
    if (stat("/usr/share/archphene-test/..", &metadata) != 0
            || !S_ISDIR(metadata.st_mode)) {
        fputs("contained parent path was not normalized\n", stderr);
        return 13;
    }
    errno = 0;
    if (lstat("/usr/share/archphene-test/../missing-archphene-probe",
                &metadata) != -1 || errno != ENOENT) {
        fputs("contained missing parent path did not report ENOENT\n", stderr);
        return 14;
    }
    int proc = open("/proc", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (proc < 0 || fstatat(proc, "self/task/", &metadata, 0) != 0
            || !S_ISDIR(metadata.st_mode)) {
        if (proc >= 0) close(proc);
        fputs("procfs directory descriptors were not preserved\n", stderr);
        return 10;
    }
    close(proc);
#ifdef __NR_statx
    struct statx extended_metadata;
    if (statx(AT_FDCWD, "/proc/self/exe", AT_STATX_SYNC_AS_STAT,
                STATX_BASIC_STATS, &extended_metadata) != 0
            || (extended_metadata.stx_mask & STATX_BASIC_STATS)
                != STATX_BASIC_STATS
            || extended_metadata.stx_uid != expected
            || extended_metadata.stx_gid != expected) {
        fputs("interposed statx fallback did not preserve metadata\n", stderr);
        return 12;
    }
    errno = 0;
    if (syscall(__NR_statx, AT_FDCWD, "/proc/self/exe",
                AT_STATX_SYNC_AS_STAT, STATX_BASIC_STATS,
                &extended_metadata) != -1
            || errno != ENOSYS) {
        fputs("raw statx did not select the translated fallback\n", stderr);
        return 11;
    }
#endif
    if (argc == 2 && strcmp(argv[1], "--supervised") == 0) {
        pid_t group = getpgrp();
        pid_t session = getsid(0);
        if (group <= 0 || session <= 0 || setsid() != session
                || setpgid(0, 0) != 0 || getpgrp() != group) {
            fputs("supervised process escaped its process group\n", stderr);
            return 8;
        }
    }
    return 0;
}
