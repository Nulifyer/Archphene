#include <stdio.h>

int archphene_dlopen_fixture(void);

int main(void) {
    if (archphene_dlopen_fixture() != 42) {
        fputs("runpath fixture returned the wrong value\n", stderr);
        return 1;
    }
    puts("absolute-runpath-ok");
    return 0;
}
