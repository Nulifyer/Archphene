#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/stat.h>
#include <unistd.h>

#define RESOLVER_PATH "/etc/resolv.conf"
#define MAX_RESOLVER_BYTES 4096

static char original_configuration[MAX_RESOLVER_BYTES];
static size_t original_length;
static mode_t original_mode;
static bool restore_required;

static int write_all(int descriptor, const char *data, size_t length) {
    while (length > 0) {
        ssize_t written = write(descriptor, data, length);
        if (written < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        data += written;
        length -= (size_t)written;
    }
    return 0;
}

static int replace_configuration(
        const char *configuration, size_t length, mode_t mode) {
    char temporary[] = "/etc/.resolv.conf.reload-probe.XXXXXX";
    int descriptor = mkstemp(temporary);
    if (descriptor < 0) return -1;
    int saved_errno = 0;
    if (fchmod(descriptor, mode) != 0
            || write_all(descriptor, configuration, length) != 0
            || fsync(descriptor) != 0) {
        saved_errno = errno;
    }
    if (close(descriptor) != 0 && saved_errno == 0) saved_errno = errno;
    if (saved_errno == 0 && rename(temporary, RESOLVER_PATH) != 0) {
        saved_errno = errno;
    }
    if (saved_errno != 0) {
        unlink(temporary);
        errno = saved_errno;
        return -1;
    }
    return 0;
}

static void restore_configuration(void) {
    if (!restore_required) return;
    if (replace_configuration(
                original_configuration, original_length, original_mode)
            != 0) {
        perror("restore resolv.conf");
        return;
    }
    restore_required = false;
}

static int resolve(const char *host) {
    struct addrinfo hints = {
        .ai_family = AF_UNSPEC,
        .ai_socktype = SOCK_STREAM,
    };
    struct addrinfo *result = NULL;
    int status = getaddrinfo(host, NULL, &hints, &result);
    if (result != NULL) freeaddrinfo(result);
    return status;
}

int main(int argument_count, char **arguments) {
    if (argument_count != 2) {
        fputs("usage: resolver_reload_probe HOST\n", stderr);
        return 64;
    }

    int descriptor = open(RESOLVER_PATH, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (descriptor < 0) {
        perror("open resolv.conf");
        return 1;
    }
    struct stat metadata;
    if (fstat(descriptor, &metadata) != 0
            || !S_ISREG(metadata.st_mode)
            || metadata.st_size <= 0
            || metadata.st_size > MAX_RESOLVER_BYTES) {
        fputs("resolver configuration is not a bounded regular file\n", stderr);
        close(descriptor);
        return 1;
    }
    while (original_length < (size_t)metadata.st_size) {
        ssize_t count = read(descriptor, original_configuration + original_length,
                (size_t)metadata.st_size - original_length);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) {
            fputs("could not read complete resolver configuration\n", stderr);
            close(descriptor);
            return 1;
        }
        original_length += (size_t)count;
    }
    original_mode = metadata.st_mode & 0777;
    if (close(descriptor) != 0) {
        perror("close resolv.conf");
        return 1;
    }

    int initial_status = resolve(arguments[1]);
    if (initial_status != 0) {
        fprintf(stderr, "initial resolution failed: %s\n",
                gai_strerror(initial_status));
        return 2;
    }
    puts("resolver-initial-result-ready");

    static const char unavailable_configuration[] =
            "nameserver 192.0.2.53\noptions timeout:1 attempts:1\n";
    if (replace_configuration(unavailable_configuration,
                sizeof(unavailable_configuration) - 1, 0600)
            != 0) {
        perror("install unavailable resolver");
        return 1;
    }
    restore_required = true;
    if (atexit(restore_configuration) != 0) {
        fputs("could not register resolver restoration\n", stderr);
        restore_configuration();
        return 1;
    }

    int unavailable_status = resolve(arguments[1]);
    if (unavailable_status == 0) {
        fputs("resolution unexpectedly ignored unavailable resolver\n", stderr);
        return 3;
    }
    puts("resolver-unavailable-result-ready");

    restore_configuration();
    int restored_status = resolve(arguments[1]);
    if (restored_status != 0) {
        fprintf(stderr, "restored resolution failed: %s\n",
                gai_strerror(restored_status));
        return 4;
    }
    puts("resolver-reload-ready");
    return 0;
}
