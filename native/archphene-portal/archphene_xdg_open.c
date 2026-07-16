#include "archphene_android.h"

#include <stdio.h>

int main(int argc, char **argv) {
    if (argc != 2) {
        fprintf(stderr, "usage: xdg-open HTTP_OR_HTTPS_URI\n");
        return 64;
    }
    char response[256];
    int result = archphene_android_open_uri(argv[1], response, sizeof(response));
    if (result < 0) {
        perror("xdg-open Android bridge");
        return 70;
    }
    if (result != 0) {
        fprintf(stderr, "xdg-open Android bridge: %s\n", response);
        return 1;
    }
    return 0;
}