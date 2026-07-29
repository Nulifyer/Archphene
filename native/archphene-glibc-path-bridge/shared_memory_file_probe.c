#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

static int fail(const char *stage) {
    fprintf(stderr, "%s failed: errno=%d (%s)\n", stage, errno,
            strerror(errno));
    return 1;
}

int main(int argument_count, char **arguments) {
    if (argument_count != 2 || arguments[1][0] != '/') {
        fputs("usage: shared-memory-file-probe ABSOLUTE_TMPDIR\n", stderr);
        return 2;
    }

    const char *directory = arguments[1];
    if (access(directory, W_OK | X_OK) != 0) return fail("access");

    char path[4096];
    int written = snprintf(path, sizeof(path),
            "%s/.org.chromium.Chromium.XXXXXX", directory);
    if (written <= 0 || (size_t)written >= sizeof(path)) {
        errno = ENAMETOOLONG;
        return fail("template");
    }

    int writable = mkstemp(path);
    if (writable < 0) return fail("mkstemp");

    int readonly = open(path, O_RDONLY | O_CLOEXEC);
    if (readonly < 0) {
        int status = fail("open-readonly");
        unlink(path);
        close(writable);
        return status;
    }

    struct stat writable_metadata;
    struct stat readonly_metadata;
    if (fstat(writable, &writable_metadata) != 0
            || fstat(readonly, &readonly_metadata) != 0) {
        int status = fail("fstat");
        unlink(path);
        close(readonly);
        close(writable);
        return status;
    }
    if (writable_metadata.st_dev != readonly_metadata.st_dev
            || writable_metadata.st_ino != readonly_metadata.st_ino) {
        fputs("descriptor identities differ\n", stderr);
        unlink(path);
        close(readonly);
        close(writable);
        return 1;
    }

    const off_t allocation_size = 256 * 1024;
    if (ftruncate(writable, allocation_size) != 0) {
        int status = fail("ftruncate");
        unlink(path);
        close(readonly);
        close(writable);
        return status;
    }

    if (fallocate(writable, 0, 0, allocation_size) != 0) {
        /*
         * This is Chromium's portable fallback when the backing filesystem
         * does not implement fallocate(2): touch one byte per realized block.
         */
        const off_t block_size =
                writable_metadata.st_blksize > 0
                ? writable_metadata.st_blksize
                : 512;
        for (off_t offset = 0; offset < allocation_size; offset += block_size) {
            unsigned char value;
            if (pread(writable, &value, 1, offset) != 1
                    || (value == 0 && pwrite(writable, &value, 1, offset) != 1)) {
                int status = fail("realize-file-region");
                unlink(path);
                close(readonly);
                close(writable);
                return status;
            }
        }
    }

    void *mapping = mmap(NULL, allocation_size, PROT_READ | PROT_WRITE,
            MAP_SHARED, writable, 0);
    if (mapping == MAP_FAILED) {
        int status = fail("mmap");
        unlink(path);
        close(readonly);
        close(writable);
        return status;
    }
    memset(mapping, 0x5a, 4096);

    if (unlink(path) != 0) {
        int status = fail("unlink");
        munmap(mapping, allocation_size);
        close(readonly);
        close(writable);
        return status;
    }

    munmap(mapping, allocation_size);
    close(readonly);
    close(writable);
    puts("shared-memory-file-ok");
    return 0;
}
