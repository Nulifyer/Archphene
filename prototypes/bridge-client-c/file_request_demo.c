#include "archphene_bridge.h"

#include <stdio.h>

int main(void) {
    const char *request_id = "file-open-text-c-1";
    ArchpheneBridgeResult result;

    puts("c payload needs user-selected text document");
    if (archphene_open_document(
                request_id,
                "text/plain",
                "open user selected text document",
                &result) != 0) {
        printf("c payload file portal bridge error: %s\n", result.reason);
        return 2;
    }

    printf("c payload received file portal response: %s\n", result.raw);
    if (!archphene_result_matches_id(&result, request_id)) {
        puts("c payload file portal decision: mismatched response id");
        return 3;
    }
    printf("c payload file portal decision: %s\n", result.granted ? "granted" : "denied");
    if (result.granted) {
        printf("c payload selected uri: %s\n", result.uri);
        printf("c payload selected text:\n%s", result.text);
    }
    return 0;
}
