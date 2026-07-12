#include "archphene_bridge.h"

#include <stdio.h>

static int require_matching_result(const ArchpheneBridgeResult *result, const char *request_id, const char *label) {
    printf("c payload %s response: %s\n", label, result->raw);
    if (!archphene_result_matches_id(result, request_id)) {
        printf("c payload %s decision: mismatched response id\n", label);
        return 0;
    }
    printf("c payload %s decision: %s\n", label, result->granted ? "granted" : "denied");
    return result->granted;
}

int main(void) {
    const char *tree_request_id = "tree-open-c-1";
    const char *write_request_id = "tree-write-c-1";
    const char *read_request_id = "tree-read-c-1";
    const char *relative_path = "archphene-background-bridge.txt";
    const char *text =
            "Archphene persisted tree grant test\n"
            "background write via Android ContentResolver\n"
            "background read via Android ContentResolver\n";
    ArchpheneBridgeResult result;

    puts("c payload needs a persistent project folder grant");
    if (archphene_open_tree(tree_request_id, "grant project folder access", &result) != 0) {
        printf("c payload tree grant bridge error: %s\n", result.reason);
        return 2;
    }
    if (!require_matching_result(&result, tree_request_id, "tree grant")) {
        return 3;
    }
    printf("c payload tree uri: %s\n", result.uri);

    puts("c payload performs background write using persisted tree grant");
    if (archphene_tree_write_file(
                write_request_id,
                relative_path,
                "text/plain",
                text,
                "background project file write",
                &result) != 0) {
        printf("c payload tree write bridge error: %s\n", result.reason);
        return 4;
    }
    if (!require_matching_result(&result, write_request_id, "tree write")) {
        return 5;
    }
    printf("c payload written uri: %s\n", result.uri);

    puts("c payload performs background read using persisted tree grant");
    if (archphene_tree_read_file(
                read_request_id,
                relative_path,
                "background project file read",
                &result) != 0) {
        printf("c payload tree read bridge error: %s\n", result.reason);
        return 6;
    }
    if (!require_matching_result(&result, read_request_id, "tree read")) {
        return 7;
    }
    printf("c payload background read text:\n%s", result.text);
    return 0;
}
