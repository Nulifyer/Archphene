#include <errno.h>
#include <stdio.h>
#include <unistd.h>

int main(void) {
    char target[4096];
    ssize_t length = readlink("/proc/self/exe", target, sizeof(target) - 1);
    if (length < 0) {
        perror("readlink");
        return errno == 0 ? 1 : errno;
    }
    target[length] = '\0';
    puts(target);
    return 0;
}
