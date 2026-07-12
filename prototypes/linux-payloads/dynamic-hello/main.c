#include <stdio.h>
#include <unistd.h>

int main(void) {
    printf("hello from dynamic glibc elf\n");
    printf("pid=%ld\n", (long)getpid());
    return 0;
}
