#define _GNU_SOURCE

#include "archphene_android.h"

#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#define MAX_REQUEST 16384
#define MAX_FIELD 8192

static int base64url(const char *input, char *output, size_t output_size) {
    static const char alphabet[] =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    size_t length = input == NULL ? 0 : strlen(input);
    if (length == 0 || length > MAX_FIELD) {
        errno = EINVAL;
        return -1;
    }
    size_t required = (length * 4 + 2) / 3;
    if (required + 1 > output_size) {
        errno = ENOSPC;
        return -1;
    }
    size_t source = 0;
    size_t target = 0;
    while (source < length) {
        uint32_t value = (uint8_t)input[source++] << 16;
        bool second = source < length;
        if (second) value |= (uint8_t)input[source++] << 8;
        bool third = source < length;
        if (third) value |= (uint8_t)input[source++];
        output[target++] = alphabet[(value >> 18) & 63];
        output[target++] = alphabet[(value >> 12) & 63];
        if (second) output[target++] = alphabet[(value >> 6) & 63];
        if (third) output[target++] = alphabet[value & 63];
    }
    output[target] = '\0';
    return 0;
}

static int broker_request(
        const char *request, char *response, size_t response_size) {
    const char *name = getenv("ARCHPHENE_ANDROID_BROKER");
    if (name == NULL || name[0] != '@' || name[1] == '\0'
            || strlen(name + 1) >= sizeof(((struct sockaddr_un *)0)->sun_path)) {
        errno = ENOTCONN;
        return -1;
    }
    if (request == NULL || strlen(request) == 0 || strlen(request) >= MAX_REQUEST
            || response == NULL || response_size < 2) {
        errno = EINVAL;
        return -1;
    }
    int socket_fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (socket_fd < 0) return -1;
    struct sockaddr_un address = {0};
    address.sun_family = AF_UNIX;
    size_t name_length = strlen(name + 1);
    memcpy(address.sun_path + 1, name + 1, name_length);
    socklen_t address_length = (socklen_t)(offsetof(struct sockaddr_un, sun_path)
            + 1 + name_length);
    if (connect(socket_fd, (struct sockaddr *)&address, address_length) != 0) {
        int saved = errno;
        close(socket_fd);
        errno = saved;
        return -1;
    }
    size_t request_length = strlen(request);
    size_t written = 0;
    while (written < request_length) {
        ssize_t count = write(socket_fd, request + written, request_length - written);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) {
            int saved = count == 0 ? EPIPE : errno;
            close(socket_fd);
            errno = saved;
            return -1;
        }
        written += (size_t)count;
    }
    if (write(socket_fd, "\n", 1) != 1) {
        int saved = errno;
        close(socket_fd);
        errno = saved;
        return -1;
    }
    size_t received = 0;
    while (received + 1 < response_size) {
        char value;
        ssize_t count = read(socket_fd, &value, 1);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0 || value == '\n') break;
        if (value != '\r') response[received++] = value;
    }
    response[received] = '\0';
    close(socket_fd);
    if (received == 0) {
        errno = EPROTO;
        return -1;
    }
    return strcmp(response, "OK") == 0 ? 0 : 1;
}

int archphene_android_open_uri(
        const char *uri, char *response, size_t response_size) {
    char encoded[MAX_FIELD * 2];
    char request[MAX_REQUEST];
    if (base64url(uri, encoded, sizeof(encoded)) != 0) return -1;
    int length = snprintf(request, sizeof(request),
            "ARCHPHENE/1\tOPEN_URI\t%s", encoded);
    if (length <= 0 || (size_t)length >= sizeof(request)) {
        errno = ENOSPC;
        return -1;
    }
    return broker_request(request, response, response_size);
}

int archphene_android_notify(
        const char *id, const char *title, const char *body,
        char *response, size_t response_size) {
    char encoded_id[MAX_FIELD * 2];
    char encoded_title[MAX_FIELD * 2];
    char encoded_body[MAX_FIELD * 2];
    char request[MAX_REQUEST];
    if (base64url(id, encoded_id, sizeof(encoded_id)) != 0
            || base64url(title, encoded_title, sizeof(encoded_title)) != 0
            || base64url(body, encoded_body, sizeof(encoded_body)) != 0) return -1;
    int length = snprintf(request, sizeof(request),
            "ARCHPHENE/1\tNOTIFY\t%s\t%s\t%s",
            encoded_id, encoded_title, encoded_body);
    if (length <= 0 || (size_t)length >= sizeof(request)) {
        errno = ENOSPC;
        return -1;
    }
    return broker_request(request, response, response_size);
}

int archphene_android_withdraw_notification(
        const char *id, char *response, size_t response_size) {
    char encoded[MAX_FIELD * 2];
    char request[MAX_REQUEST];
    if (base64url(id, encoded, sizeof(encoded)) != 0) return -1;
    int length = snprintf(request, sizeof(request),
            "ARCHPHENE/1\tWITHDRAW_NOTIFICATION\t%s", encoded);
    if (length <= 0 || (size_t)length >= sizeof(request)) {
        errno = ENOSPC;
        return -1;
    }
    return broker_request(request, response, response_size);
}

#ifdef ARCHPHENE_CAPABILITY_PROBE_MAIN
int main(int argc, char **argv) {
    char response[256];
    int result;
    int argument = 1;
    if (argc > 3 && strcmp(argv[1], "--socket") == 0) {
        if (setenv("ARCHPHENE_ANDROID_BROKER", argv[2], 1) != 0) {
            perror("setenv");
            return 70;
        }
        argument = 3;
    }
    int remaining = argc - argument;
    if (remaining == 2 && strcmp(argv[argument], "open-uri") == 0) {
        result = archphene_android_open_uri(
                argv[argument + 1], response, sizeof(response));
    } else if (remaining == 4 && strcmp(argv[argument], "notify") == 0) {
        result = archphene_android_notify(
                argv[argument + 1], argv[argument + 2], argv[argument + 3],
                response, sizeof(response));
    } else if (remaining == 2 && strcmp(argv[argument], "withdraw") == 0) {
        result = archphene_android_withdraw_notification(
                argv[argument + 1], response, sizeof(response));
    } else {
        fprintf(stderr, "usage: %s [--socket @NAME] open-uri URI | "
                "notify ID TITLE BODY | withdraw ID\n", argv[0]);
        return 64;
    }
    if (result < 0) {
        perror("Archphene Android capability request");
        return 70;
    }
    puts(response);
    return result;
}
#endif
