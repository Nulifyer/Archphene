#define _GNU_SOURCE

#include <dlfcn.h>

__attribute__((constructor))
static void leave_optional_symbol_unresolved(void) {
    (void)dlopen(
            "libarchphene-deliberately-missing-optional-library.so",
            RTLD_LAZY | RTLD_LOCAL);
}

int archphene_dlopen_fixture(void) {
    return 42;
}
