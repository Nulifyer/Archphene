#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

int main(void) {
    int directory = open("/home/archphene/link-fd", O_RDONLY | O_DIRECTORY);
    if (directory < 0) {
        perror("open directory");
        return 1;
    }
    if (linkat(directory, "source", directory, "hard-link", 0) != 0) {
        perror("linkat");
        close(directory);
        return 1;
    }
    if (symlinkat("source", directory, "symbolic-link") != 0) {
        perror("symlinkat");
        close(directory);
        return 1;
    }
    char target[32];
    ssize_t length =
            readlinkat(directory, "symbolic-link", target, sizeof(target) - 1);
    if (length != (ssize_t)strlen("source")) {
        perror("readlinkat");
        close(directory);
        return 1;
    }
    target[length] = '\0';
    if (strcmp(target, "source") != 0) {
        close(directory);
        return 1;
    }
    int nested = openat(directory, "nested",
            O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (nested < 0
            || symlinkat("../source", nested, "parent-link") != 0
            || readlinkat(nested, "parent-link", target, sizeof(target) - 1)
                != (ssize_t)strlen("../source")) {
        perror("contained parent symlink");
        if (nested >= 0) close(nested);
        close(directory);
        return 1;
    }
    errno = 0;
    if (symlinkat("../../../../../../etc/passwd", nested, "escape") != -1
            || errno != EACCES) {
        fputs("escaping relative symlink was accepted\n", stderr);
        close(nested);
        close(directory);
        return 1;
    }
    close(nested);
    close(directory);
    puts("directory-link-ok");
    return 0;
}
