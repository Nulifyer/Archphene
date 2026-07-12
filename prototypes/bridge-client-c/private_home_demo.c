#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int write_text(const char *path, const char *text) {
    FILE *file = fopen(path, "w");
    if (file == NULL) {
        printf("private home write open failed: %s: %s\n", path, strerror(errno));
        return -1;
    }
    if (fputs(text, file) == EOF) {
        printf("private home write failed: %s: %s\n", path, strerror(errno));
        fclose(file);
        return -1;
    }
    if (fclose(file) != 0) {
        printf("private home write close failed: %s: %s\n", path, strerror(errno));
        return -1;
    }
    return 0;
}

static int read_text(const char *path) {
    char buffer[512];
    FILE *file = fopen(path, "r");
    if (file == NULL) {
        printf("private home read open failed: %s: %s\n", path, strerror(errno));
        return -1;
    }
    size_t count = fread(buffer, 1, sizeof(buffer) - 1, file);
    if (ferror(file)) {
        printf("private home read failed: %s: %s\n", path, strerror(errno));
        fclose(file);
        return -1;
    }
    buffer[count] = '\0';
    fclose(file);
    printf("private home readback:\n%s", buffer);
    return 0;
}

int main(void) {
    const char *home = getenv("HOME");
    if (home == NULL || home[0] == '\0') {
        printf("private home test failed: HOME is not set\n");
        return 2;
    }

    printf("private home test HOME=%s\n", home);

    char file_path[512];
    snprintf(file_path, sizeof(file_path), "%s/.cache/archphene-private-background.txt", home);

    const char *text =
            "Archphene app-private HOME test\n"
            "background write without Android storage prompt\n"
            "scoped to generated app sandbox\n";

    if (write_text(file_path, text) != 0) {
        return 3;
    }
    printf("private home wrote %s\n", file_path);

    if (read_text(file_path) != 0) {
        return 4;
    }

    return 0;
}
