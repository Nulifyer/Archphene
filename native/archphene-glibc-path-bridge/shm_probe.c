#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

int main(void) {
    const char *name = "/archphene-shm-test";
    int first = shm_open(name, O_RDWR | O_CREAT | O_EXCL, 0600);
    if (first < 0) return 10;
    if (ftruncate(first, 4096) != 0) return 11;
    char *memory = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_SHARED, first, 0);
    if (memory == MAP_FAILED) return 12;
    memcpy(memory, "shared", 7);

    int second = shm_open(name, O_RDWR, 0600);
    if (second < 0) return 13;
    char *other = mmap(NULL, 4096, PROT_READ, MAP_SHARED, second, 0);
    if (other == MAP_FAILED || memcmp(other, "shared", 7) != 0) return 14;
    if (shm_unlink(name) != 0) return 15;
    errno = 0;
    int missing = shm_open(name, O_RDWR, 0600);
    if (missing >= 0 || errno != ENOENT) return 16;
    errno = 0;
    if (shm_open("/../escape", O_RDWR | O_CREAT, 0600) >= 0 || errno != EINVAL) {
        return 17;
    }
    const char *conventional = "/dev/shm/.archphene-file-api-test";
    int file_api = open(conventional, O_RDWR | O_CREAT | O_EXCL, 0600);
    if (file_api < 0 || write(file_api, "file-api", 8) != 8
            || lseek(file_api, 0, SEEK_SET) != 0) {
        return 18;
    }
    char contents[8];
    if (read(file_api, contents, sizeof(contents)) != (ssize_t)sizeof(contents)
            || memcmp(contents, "file-api", sizeof(contents)) != 0
            || unlink(conventional) != 0) {
        return 19;
    }
    close(file_api);
    const char *raw_name = "/dev/shm/.archphene-raw-openat-test";
    int raw = (int)syscall(
            SYS_openat, AT_FDCWD, raw_name, O_RDWR | O_CREAT | O_EXCL, 0600);
    if (raw < 0 || unlink(raw_name) != 0 || syscall(SYS_getpid) != getpid()) {
        return 20;
    }
    close(raw);
    munmap(other, 4096);
    munmap(memory, 4096);
    close(second);
    close(first);
    puts("shm-bridge-tests-passed");
    return 0;
}
