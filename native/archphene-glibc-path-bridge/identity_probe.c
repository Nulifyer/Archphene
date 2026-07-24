#include <stdio.h>
#include <sys/fsuid.h>
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
    return 0;
}
