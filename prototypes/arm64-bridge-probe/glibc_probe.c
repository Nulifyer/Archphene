#include <gnu/libc-version.h>
#include <stdio.h>
#include <sys/utsname.h>
#include <unistd.h>

int main(void) {
    struct utsname value;
    if (uname(&value) != 0) {
        perror("uname");
        return 2;
    }
    printf("ARCHPHENE_GLIBC_PASS version=%s machine=%s uid=%ld pid=%ld\n",
            gnu_get_libc_version(), value.machine, (long)getuid(), (long)getpid());
    return 0;
}
