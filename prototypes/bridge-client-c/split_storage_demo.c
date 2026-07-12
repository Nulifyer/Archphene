#include "archphene_bridge.h"

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int write_text(const char *path, const char *text) {
    FILE *file = fopen(path, "w");
    if (file == NULL) {
        printf("split private write open failed: %s: %s\n", path, strerror(errno));
        return -1;
    }
    if (fputs(text, file) == EOF) {
        printf("split private write failed: %s: %s\n", path, strerror(errno));
        fclose(file);
        return -1;
    }
    if (fclose(file) != 0) {
        printf("split private write close failed: %s: %s\n", path, strerror(errno));
        return -1;
    }
    return 0;
}

static int read_text(const char *path) {
    char buffer[512];
    FILE *file = fopen(path, "r");
    if (file == NULL) {
        printf("split private read open failed: %s: %s\n", path, strerror(errno));
        return -1;
    }
    size_t count = fread(buffer, 1, sizeof(buffer) - 1, file);
    if (ferror(file)) {
        printf("split private read failed: %s: %s\n", path, strerror(errno));
        fclose(file);
        return -1;
    }
    buffer[count] = '\0';
    fclose(file);
    printf("split private readback:\n%s", buffer);
    return 0;
}

static int run_private_side(void) {
    const char *home = getenv("HOME");
    if (home == NULL || home[0] == '\0') {
        printf("split private test failed: HOME is not set\n");
        return 2;
    }

    char path[512];
    snprintf(path, sizeof(path), "%s/.cache/archphene-split-private.txt", home);

    const char *text =
            "Archphene split-storage private side\n"
            "background cache write in generated app sandbox\n"
            "no Android storage prompt expected\n";

    printf("split private HOME=%s\n", home);
    if (write_text(path, text) != 0) {
        return 3;
    }
    printf("split private wrote %s\n", path);
    if (read_text(path) != 0) {
        return 4;
    }
    return 0;
}

static int require_matching_result(const ArchpheneBridgeResult *result, const char *request_id, const char *label) {
    printf("split %s response: %s\n", label, result->raw);
    if (!archphene_result_matches_id(result, request_id)) {
        printf("split %s decision: mismatched response id\n", label);
        return 0;
    }
    printf("split %s decision: %s\n", label, result->granted ? "granted" : "denied");
    return result->granted;
}

static int run_user_visible_side(void) {
    const char *tree_request_id = "split-tree-open-1";
    const char *write_request_id = "split-tree-write-1";
    const char *read_request_id = "split-tree-read-1";
    const char *relative_path = "archphene-split-user-visible.txt";
    const char *text =
            "Archphene split-storage user-visible side\n"
            "background project write after Android tree grant\n"
            "ContentResolver owns the actual file access\n";
    ArchpheneBridgeResult result;

    puts("split user-visible side requests project folder grant");
    if (archphene_open_tree(tree_request_id, "grant split-storage project folder", &result) != 0) {
        printf("split tree grant bridge error: %s\n", result.reason);
        return 5;
    }
    if (!require_matching_result(&result, tree_request_id, "tree grant")) {
        return 6;
    }
    printf("split tree uri: %s\n", result.uri);

    puts("split user-visible side writes project file through persisted tree grant");
    if (archphene_tree_write_file(
                write_request_id,
                relative_path,
                "text/plain",
                text,
                "split-storage background project write",
                &result) != 0) {
        printf("split tree write bridge error: %s\n", result.reason);
        return 7;
    }
    if (!require_matching_result(&result, write_request_id, "tree write")) {
        return 8;
    }
    printf("split written uri: %s\n", result.uri);

    puts("split user-visible side reads project file through persisted tree grant");
    if (archphene_tree_read_file(
                read_request_id,
                relative_path,
                "split-storage background project read",
                &result) != 0) {
        printf("split tree read bridge error: %s\n", result.reason);
        return 9;
    }
    if (!require_matching_result(&result, read_request_id, "tree read")) {
        return 10;
    }
    printf("split tree readback:\n%s", result.text);
    return 0;
}

int main(void) {
    int private_result = run_private_side();
    if (private_result != 0) {
        return private_result;
    }
    return run_user_visible_side();
}
