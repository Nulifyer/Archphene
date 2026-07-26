#define _GNU_SOURCE

#include <dlfcn.h>
#include <stdio.h>

int main(void) {
    void *library = dlopen(
            "/usr/lib/archphene-example/libdlopen-fixture.so",
            RTLD_NOW | RTLD_LOCAL);
    if (library == NULL) {
        fprintf(stderr, "dlopen: %s\n", dlerror());
        return 1;
    }
    dlerror();
    int (*fixture)(void) =
            (int (*)(void))dlsym(library, "archphene_dlopen_fixture");
    const char *error = dlerror();
    if (error != NULL || fixture == NULL || fixture() != 42) {
        fprintf(stderr, "dlsym: %s\n", error == NULL ? "invalid result" : error);
        dlclose(library);
        return 2;
    }
    if (dlclose(library) != 0) {
        fputs("dlclose failed\n", stderr);
        return 3;
    }
    puts("dynamic-loading-ok");
    return 0;
}
