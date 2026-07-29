int archphene_dlopen_fixture(void);

int archphene_transitive_fixture(void) {
    return archphene_dlopen_fixture();
}
