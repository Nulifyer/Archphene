#include "archphene_bridge.h"

#include <stdio.h>

int main(void) {
    const char *request_id = "perm-notifications-c-1";
    ArchpheneBridgeResult result;

    puts("c payload needs notification access");
    if (archphene_request_permission(
                request_id,
                "android.permission.POST_NOTIFICATIONS",
                "desktop notifications",
                &result) != 0) {
        printf("c payload permission bridge error: %s\n", result.reason);
        return 2;
    }

    printf("c payload received bridge response: %s\n", result.raw);
    if (!archphene_result_matches_id(&result, request_id)) {
        puts("c payload permission decision: mismatched response id");
        return 3;
    }
    printf("c payload permission decision: %s\n", result.granted ? "granted" : "denied");
    return 0;
}
