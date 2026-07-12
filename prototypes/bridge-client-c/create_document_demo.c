#include "archphene_bridge.h"

#include <stdio.h>

int main(void) {
    const char *request_id = "file-create-text-c-1";
    const char *text =
            "Archphene create document portal test\n"
            "written by Android ContentResolver\n"
            "requested by Linux payload\n";
    ArchpheneBridgeResult result;

    puts("c payload needs to create a user-selected text document");
    if (archphene_create_document(
                request_id,
                "text/plain",
                "archphene-created-by-bridge.txt",
                text,
                "create user selected text document",
                &result) != 0) {
        printf("c payload create document bridge error: %s\n", result.reason);
        return 2;
    }

    printf("c payload received create document response: %s\n", result.raw);
    if (!archphene_result_matches_id(&result, request_id)) {
        puts("c payload create document decision: mismatched response id");
        return 3;
    }
    printf("c payload create document decision: %s\n", result.granted ? "granted" : "denied");
    if (result.granted) {
        printf("c payload created uri: %s\n", result.uri);
    }
    return 0;
}
