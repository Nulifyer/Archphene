#include "greet.h"
#include <stdio.h>
#include <unistd.h>

int main(void) {
    printf("musl executable with shared library\n");
    archphene_greet("android-app-process");
    printf("pid=%ld\n", (long)getpid());
    return 0;
}
