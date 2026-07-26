#include <errno.h>
#include <stdio.h>
#include <sys/stat.h>

int main(void) {
    if (chmod("/home/archphene/.config/archphene-chmod-probe", 0600) != 0) {
        perror("chmod");
        return 1;
    }
    puts("libc-internal-chmod-ok");
    return 0;
}
