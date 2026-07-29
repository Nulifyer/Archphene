#include <stdio.h>

int archphene_transitive_fixture(void);

int main(void) {
    if (archphene_transitive_fixture() != 42) {
        fputs("transitive runpath fixture returned the wrong value\n", stderr);
        return 1;
    }
    puts("transitive-absolute-runpath-ok");
    return 0;
}
