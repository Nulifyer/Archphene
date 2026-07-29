#define _GNU_SOURCE

#include <dlfcn.h>
#include <stdio.h>

int main(int argc, char **argv) {
    if (argc != 2 || argv[1][0] == '\0') {
        fputs("usage: dlopen-name-probe LIBRARY\n", stderr);
        return 2;
    }
    dlerror();
    void *library = dlopen(argv[1], RTLD_LAZY | RTLD_GLOBAL);
    if (library == NULL) {
        const char *error = dlerror();
        fprintf(stderr, "dlopen: %s\n",
                error == NULL ? "no diagnostic" : error);
        return 1;
    }
    const char *error = dlerror();
    if (error != NULL) {
        fprintf(stderr, "successful dlopen left stale error: %s\n", error);
        return 4;
    }
    if (dlclose(library) != 0) {
        fprintf(stderr, "dlclose: %s\n", dlerror());
        return 3;
    }
    puts("dlopen-name-ok");
    return 0;
}
