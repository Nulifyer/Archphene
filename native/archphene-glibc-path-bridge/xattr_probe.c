#define _GNU_SOURCE

#include <fcntl.h>
#include <linux/capability.h>
#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/xattr.h>
#include <unistd.h>

extern int capset(struct __user_cap_header_struct *header,
        const struct __user_cap_data_struct *data);

int main(int argc, char **argv) {
    static const char path[] = "/home/archphene/xattr-probe";
    bool capability = argc == 2 && strcmp(argv[1], "--capability") == 0;
    const char *name = capability
            ? "security.capability" : "user.archphene";
    static const char expected[] = "bridge";
    if (capability) {
        struct __user_cap_header_struct header = {
            .version = _LINUX_CAPABILITY_VERSION_3,
            .pid = 0,
        };
        struct __user_cap_data_struct data[2] = {{0}, {0}};
        if (syscall(SYS_capset, &header, data) != 0) {
            perror("capset");
            return 6;
        }
        if (capset(&header, data) != 0) {
            perror("capset symbol");
            return 7;
        }
    }
    FILE *file = fopen(path, "w");
    if (file == NULL || fclose(file) != 0) {
        perror("xattr fixture");
        return 1;
    }
    int descriptor = capability ? open(path, O_RDWR | O_CLOEXEC) : -1;
    if (capability && descriptor < 0) {
        perror("xattr descriptor");
        return 8;
    }
    int set_result = capability
            ? fsetxattr(descriptor, name, expected, sizeof(expected), 0)
            : setxattr(path, name, expected, sizeof(expected), 0);
    if (set_result != 0) {
        perror("setxattr");
        return 2;
    }
    char value[sizeof(expected)] = {0};
    ssize_t get_result = capability
            ? fgetxattr(descriptor, name, value, sizeof(value))
            : getxattr(path, name, value, sizeof(value));
    if (get_result != (ssize_t)sizeof(expected)
            || memcmp(value, expected, sizeof(expected)) != 0) {
        perror("getxattr");
        return 3;
    }
    char names[128] = {0};
    ssize_t names_length = listxattr(path, names, sizeof(names));
    if (!capability
            && (names_length <= 0
                || memmem(names, (size_t)names_length, name, strlen(name) + 1)
                        == NULL)) {
        fputs("listxattr omitted fixture\n", stderr);
        return 4;
    }
    int remove_result = capability
            ? fremovexattr(descriptor, name) : removexattr(path, name);
    if (remove_result != 0) {
        perror("removexattr");
        return 5;
    }
    if (descriptor >= 0) close(descriptor);
    puts("xattr-bridge-ok");
    return 0;
}
