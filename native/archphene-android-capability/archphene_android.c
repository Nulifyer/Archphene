#define _GNU_SOURCE

#include "archphene_android.h"

#include <errno.h>
#include <fcntl.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/uio.h>
#include <sys/un.h>
#include <unistd.h>

#define MAX_REQUEST 16384
#define MAX_FIELD 8192

static int base64url_value(
        const char *input, char *output, size_t output_size, bool allow_empty) {
    static const char alphabet[] =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    size_t length = input == NULL ? 0 : strlen(input);
    if ((!allow_empty && length == 0) || length > MAX_FIELD) {
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

static int base64url(const char *input, char *output, size_t output_size) {
    return base64url_value(input, output, output_size, false);
}

static int broker_request_with_fd(
        const char *request, int send_fd, char *response, size_t response_size) {
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
    char wire[MAX_REQUEST + 1];
    memcpy(wire, request, request_length);
    wire[request_length++] = '\n';
    struct iovec iov = {.iov_base = wire, .iov_len = request_length};
    char control[CMSG_SPACE(sizeof(int))] = {0};
    struct msghdr message = {0};
    message.msg_iov = &iov;
    message.msg_iovlen = 1;
    if (send_fd >= 0) {
        message.msg_control = control;
        message.msg_controllen = sizeof(control);
        struct cmsghdr *header = CMSG_FIRSTHDR(&message);
        header->cmsg_level = SOL_SOCKET;
        header->cmsg_type = SCM_RIGHTS;
        header->cmsg_len = CMSG_LEN(sizeof(int));
        memcpy(CMSG_DATA(header), &send_fd, sizeof(send_fd));
    }
    ssize_t first;
    do {
        first = sendmsg(socket_fd, &message, MSG_NOSIGNAL);
    } while (first < 0 && errno == EINTR);
    if (first <= 0) {
        int saved = first == 0 ? EPIPE : errno;
        close(socket_fd);
        errno = saved;
        return -1;
    }
    size_t written = (size_t)first;
    while (written < request_length) {
        ssize_t count = send(socket_fd, wire + written,
                request_length - written, MSG_NOSIGNAL);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) {
            int saved = count == 0 ? EPIPE : errno;
            close(socket_fd);
            errno = saved;
            return -1;
        }
        written += (size_t)count;
    }
    size_t received = 0;
    bool complete = false;
    while (received + 1 < response_size) {
        char value;
        ssize_t count = read(socket_fd, &value, 1);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) break;
        if (value == '\n') {
            complete = true;
            break;
        }
        if (value != '\r') response[received++] = value;
    }
    response[received] = '\0';
    close(socket_fd);
    if (!complete || received == 0) {
        errno = received + 1 >= response_size ? ENOSPC : EPROTO;
        return -1;
    }
    return strncmp(response, "OK", 2) == 0
            && (response[2] == '\0' || response[2] == '\t') ? 0 : 1;
}

static int broker_request(
        const char *request, char *response, size_t response_size) {
    return broker_request_with_fd(request, -1, response, response_size);
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

int archphene_android_print_pdf(
        int pdf_fd, const char *title, char *response, size_t response_size) {
    char encoded[MAX_FIELD * 2];
    char request[MAX_REQUEST];
    if (pdf_fd < 0 || base64url(title, encoded, sizeof(encoded)) != 0) {
        errno = EINVAL;
        return -1;
    }
    int length = snprintf(request, sizeof(request),
            "ARCHPHENE/1\tPRINT_PDF\t%s", encoded);
    if (length <= 0 || (size_t)length >= sizeof(request)) {
        errno = ENOSPC;
        return -1;
    }
    return broker_request_with_fd(request, pdf_fd, response, response_size);
}

int archphene_android_request_audio_input(char *response, size_t response_size) {
    return broker_request("ARCHPHENE/1\tREQUEST_AUDIO_INPUT", response, response_size);
}

int archphene_android_check_audio_input(char *response, size_t response_size) {
    return broker_request("ARCHPHENE/1\tCHECK_AUDIO_INPUT", response, response_size);
}

int archphene_android_request_camera(char *response, size_t response_size) {
    return broker_request("ARCHPHENE/1\tREQUEST_CAMERA", response, response_size);
}

int archphene_android_check_camera(char *response, size_t response_size) {
    return broker_request("ARCHPHENE/1\tCHECK_CAMERA", response, response_size);
}

int archphene_android_capture_camera_jpeg(
        int output_fd, int width, int height, int front_facing,
        char *response, size_t response_size) {
    if (output_fd < 0 || width < 1 || height < 1) {
        errno = EINVAL;
        return -1;
    }
    char request[MAX_REQUEST];
    int length = snprintf(request, sizeof(request),
            "ARCHPHENE/1\tCAPTURE_CAMERA_JPEG\t%d\t%d\t%s",
            width, height, front_facing ? "front" : "back");
    if (length <= 0 || (size_t)length >= sizeof(request)) {
        errno = ENOSPC;
        return -1;
    }
    return broker_request_with_fd(request, output_fd, response, response_size);
}
int archphene_android_stream_camera_i420(
        int output_fd, int width, int height, int front_facing,
        char *response, size_t response_size) {
    if (output_fd < 0 || width < 1 || height < 1
            || (width & 1) != 0 || (height & 1) != 0) {
        errno = EINVAL;
        return -1;
    }
    char request[MAX_REQUEST];
    int length = snprintf(request, sizeof(request),
            "ARCHPHENE/1\tSTREAM_CAMERA_I420\t%d\t%d\t%s",
            width, height, front_facing ? "front" : "back");
    if (length <= 0 || (size_t)length >= sizeof(request)) {
        errno = ENOSPC;
        return -1;
    }
    return broker_request_with_fd(request, output_fd, response, response_size);
}

int archphene_android_publish_accessibility_tree(
        int tree_fd, char *response, size_t response_size) {
    if (tree_fd < 0) {
        errno = EINVAL;
        return -1;
    }
    return broker_request_with_fd(
            "ARCHPHENE/1\tPUBLISH_ACCESSIBILITY_TREE",
            tree_fd, response, response_size);
}

int archphene_android_accessibility_event(
        int node_id, const char *type, char *response, size_t response_size) {
    if (node_id < 0 || node_id > 1000000 || type == NULL
            || strlen(type) < 1 || strlen(type) > 32) {
        errno = EINVAL;
        return -1;
    }
    char request[MAX_REQUEST];
    int length = snprintf(request, sizeof(request),
            "ARCHPHENE/1\tACCESSIBILITY_EVENT\t%d\t%s", node_id, type);
    if (length <= 0 || (size_t)length >= sizeof(request)) {
        errno = ENOSPC;
        return -1;
    }
    return broker_request(request, response, response_size);
}

int archphene_android_take_accessibility_action(
        int timeout_millis, char *response, size_t response_size) {
    if (timeout_millis < 0 || timeout_millis > 250) {
        errno = EINVAL;
        return -1;
    }
    char request[MAX_REQUEST];
    int length = snprintf(request, sizeof(request),
            "ARCHPHENE/1\tTAKE_ACCESSIBILITY_ACTION\t%d", timeout_millis);
    if (length <= 0 || (size_t)length >= sizeof(request)) {
        errno = ENOSPC;
        return -1;
    }
    return broker_request(request, response, response_size);
}

int archphene_android_accessibility_menu_fallback(
        char *response, size_t response_size) {
    return broker_request("ARCHPHENE/1\tACCESSIBILITY_MENU_FALLBACK",
            response, response_size);
}

int archphene_android_store_secret(
        int secret_fd, const char *id, const char *label, const char *attributes_json,
        char *response, size_t response_size) {
    return archphene_android_store_secret_typed(secret_fd, id, label, attributes_json,
            "text/plain", response, response_size);
}

int archphene_android_store_secret_typed(
        int secret_fd, const char *id, const char *label, const char *attributes_json,
        const char *content_type, char *response, size_t response_size) {
    char encoded_id[MAX_FIELD * 2];
    char encoded_label[MAX_FIELD * 2];
    char encoded_attributes[MAX_FIELD * 2];
    char encoded_content_type[MAX_FIELD * 2];
    char request[MAX_REQUEST];
    if (secret_fd < 0 || label == NULL
            || base64url(id, encoded_id, sizeof(encoded_id)) != 0
            || base64url_value(label, encoded_label, sizeof(encoded_label), true) != 0
            || base64url(attributes_json, encoded_attributes,
                    sizeof(encoded_attributes)) != 0
            || base64url(content_type, encoded_content_type,
                    sizeof(encoded_content_type)) != 0) {
        errno = EINVAL;
        return -1;
    }
    int length = snprintf(request, sizeof(request),
            "ARCHPHENE/1\tSTORE_SECRET\t%s\t%s\t%s\t%s",
            encoded_id, encoded_label, encoded_attributes, encoded_content_type);
    if (length <= 0 || (size_t)length >= sizeof(request)) {
        errno = ENOSPC;
        return -1;
    }
    return broker_request_with_fd(request, secret_fd, response, response_size);
}

int archphene_android_read_secret(
        int output_fd, const char *id, char *response, size_t response_size) {
    char encoded_id[MAX_FIELD * 2];
    char request[MAX_REQUEST];
    if (output_fd < 0 || base64url(id, encoded_id, sizeof(encoded_id)) != 0) {
        errno = EINVAL;
        return -1;
    }
    int length = snprintf(request, sizeof(request),
            "ARCHPHENE/1\tREAD_SECRET\t%s", encoded_id);
    if (length <= 0 || (size_t)length >= sizeof(request)) {
        errno = ENOSPC;
        return -1;
    }
    return broker_request_with_fd(request, output_fd, response, response_size);
}

int archphene_android_delete_secret(
        const char *id, char *response, size_t response_size) {
    char encoded_id[MAX_FIELD * 2];
    char request[MAX_REQUEST];
    if (base64url(id, encoded_id, sizeof(encoded_id)) != 0) return -1;
    int length = snprintf(request, sizeof(request),
            "ARCHPHENE/1\tDELETE_SECRET\t%s", encoded_id);
    if (length <= 0 || (size_t)length >= sizeof(request)) {
        errno = ENOSPC;
        return -1;
    }
    return broker_request(request, response, response_size);
}

int archphene_android_list_secrets(
        int output_fd, char *response, size_t response_size) {
    if (output_fd < 0) {
        errno = EINVAL;
        return -1;
    }
    return broker_request_with_fd(
            "ARCHPHENE/1\tLIST_SECRETS", output_fd, response, response_size);
}

int archphene_android_catalog_secrets(
        int output_fd, char *response, size_t response_size) {
    if (output_fd < 0) {
        errno = EINVAL;
        return -1;
    }
    return broker_request_with_fd(
            "ARCHPHENE/1\tCATALOG_SECRETS", output_fd, response, response_size);
}

#ifdef ARCHPHENE_CAPABILITY_PROBE_MAIN
int main(int argc, char **argv) {
    char response[MAX_REQUEST];
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
    } else if (remaining == 3 && strcmp(argv[argument], "print") == 0) {
        int pdf_fd = open(argv[argument + 1], O_RDONLY | O_CLOEXEC);
        if (pdf_fd < 0) {
            perror("open print PDF");
            return 66;
        }
        result = archphene_android_print_pdf(
                pdf_fd, argv[argument + 2], response, sizeof(response));
        close(pdf_fd);
    } else if (remaining == 1
            && strcmp(argv[argument], "request-audio-input") == 0) {
        result = archphene_android_request_audio_input(response, sizeof(response));
    } else if (remaining == 1
            && strcmp(argv[argument], "check-audio-input") == 0) {
        result = archphene_android_check_audio_input(response, sizeof(response));
    } else if (remaining == 1
            && strcmp(argv[argument], "request-camera") == 0) {
        result = archphene_android_request_camera(response, sizeof(response));
    } else if (remaining == 1
            && strcmp(argv[argument], "check-camera") == 0) {
        result = archphene_android_check_camera(response, sizeof(response));
    } else if (remaining == 5
            && strcmp(argv[argument], "capture-camera-jpeg") == 0) {
        char *end_width = NULL;
        char *end_height = NULL;
        long width = strtol(argv[argument + 2], &end_width, 10);
        long height = strtol(argv[argument + 3], &end_height, 10);
        if (end_width == NULL || *end_width != '\0' || end_height == NULL
                || *end_height != '\0' || width < 1 || width > INT32_MAX
                || height < 1 || height > INT32_MAX
                || (strcmp(argv[argument + 4], "front") != 0
                    && strcmp(argv[argument + 4], "back") != 0)) {
            fprintf(stderr, "invalid camera capture arguments\n");
            return 64;
        }
        int output_fd = open(argv[argument + 1],
                O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
        if (output_fd < 0) {
            perror("open camera output");
            return 66;
        }
        result = archphene_android_capture_camera_jpeg(output_fd,
                (int)width, (int)height,
                strcmp(argv[argument + 4], "front") == 0,
                response, sizeof(response));
        close(output_fd);
    } else if (remaining == 2
            && strcmp(argv[argument], "publish-accessibility-tree") == 0) {
        int tree_fd = open(argv[argument + 1], O_RDONLY | O_CLOEXEC);
        if (tree_fd < 0) {
            perror("open accessibility tree");
            return 66;
        }
        result = archphene_android_publish_accessibility_tree(
                tree_fd, response, sizeof(response));
        close(tree_fd);
    } else if (remaining == 3
            && strcmp(argv[argument], "accessibility-event") == 0) {
        char *end = NULL;
        long node_id = strtol(argv[argument + 1], &end, 10);
        if (end == NULL || *end != '\0' || node_id < 0 || node_id > 1000000) {
            fprintf(stderr, "invalid accessibility node\n");
            return 64;
        }
        result = archphene_android_accessibility_event(
                (int)node_id, argv[argument + 2], response, sizeof(response));
    } else if (remaining == 2
            && strcmp(argv[argument], "take-accessibility-action") == 0) {
        char *end = NULL;
        long timeout = strtol(argv[argument + 1], &end, 10);
        if (end == NULL || *end != '\0' || timeout < 0 || timeout > 250) {
            fprintf(stderr, "invalid accessibility timeout\n");
            return 64;
        }
        result = archphene_android_take_accessibility_action(
                (int)timeout, response, sizeof(response));
    } else if (remaining == 5 && strcmp(argv[argument], "store-secret") == 0) {
        int secret_fd = open(argv[argument + 1], O_RDONLY | O_CLOEXEC);
        if (secret_fd < 0) {
            perror("open secret input");
            return 66;
        }
        result = archphene_android_store_secret(
                secret_fd, argv[argument + 2], argv[argument + 3], argv[argument + 4],
                response, sizeof(response));
        close(secret_fd);
    } else if (remaining == 3 && strcmp(argv[argument], "read-secret") == 0) {
        int output_fd = open(argv[argument + 1],
                O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
        if (output_fd < 0) {
            perror("open secret output");
            return 66;
        }
        result = archphene_android_read_secret(
                output_fd, argv[argument + 2], response, sizeof(response));
        close(output_fd);
    } else if (remaining == 2 && strcmp(argv[argument], "delete-secret") == 0) {
        result = archphene_android_delete_secret(
                argv[argument + 1], response, sizeof(response));
    } else if (remaining == 2 && strcmp(argv[argument], "list-secrets") == 0) {
        int output_fd = open(argv[argument + 1],
                O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
        if (output_fd < 0) {
            perror("open secret index output");
            return 66;
        }
        result = archphene_android_list_secrets(output_fd, response, sizeof(response));
        close(output_fd);
    } else if (remaining == 2 && strcmp(argv[argument], "catalog-secrets") == 0) {
        int output_fd = open(argv[argument + 1],
                O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
        if (output_fd < 0) {
            perror("open secret catalog output");
            return 66;
        }
        result = archphene_android_catalog_secrets(output_fd, response, sizeof(response));
        close(output_fd);
    } else {
        fprintf(stderr, "usage: %s [--socket @NAME] open-uri URI | "
                "notify ID TITLE BODY | withdraw ID | print PDF TITLE | "
                "request-audio-input | check-audio-input | request-camera | "
                "check-camera | capture-camera-jpeg FILE WIDTH HEIGHT front|back | "
                "publish-accessibility-tree FILE | accessibility-event NODE TYPE | "
                "take-accessibility-action TIMEOUT_MS | "
                "store-secret FILE ID LABEL ATTRIBUTES_JSON | "
                "read-secret OUTPUT ID | delete-secret ID | list-secrets OUTPUT | "
                "catalog-secrets OUTPUT\n", argv[0]);
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
