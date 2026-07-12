#include "archphene_bridge.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void copy_bounded(char *dst, size_t dst_size, const char *src, size_t len) {
    if (dst_size == 0) {
        return;
    }
    if (len >= dst_size) {
        len = dst_size - 1;
    }
    memcpy(dst, src, len);
    dst[len] = '\0';
}

static void json_escape(char *dst, size_t dst_size, const char *src) {
    size_t out = 0;
    if (dst_size == 0) {
        return;
    }
    for (size_t i = 0; src[i] != '\0' && out + 1 < dst_size; i++) {
        char c = src[i];
        if ((c == '\\' || c == '"') && out + 2 < dst_size) {
            dst[out++] = '\\';
            dst[out++] = c;
        } else if (c == '\n' && out + 2 < dst_size) {
            dst[out++] = '\\';
            dst[out++] = 'n';
        } else if (c == '\r' && out + 2 < dst_size) {
            dst[out++] = '\\';
            dst[out++] = 'r';
        } else if (c == '\t' && out + 2 < dst_size) {
            dst[out++] = '\\';
            dst[out++] = 't';
        } else {
            dst[out++] = c;
        }
    }
    dst[out] = '\0';
}

static int json_get_string(const char *json, const char *key, char *dst, size_t dst_size) {
    char needle[96];
    snprintf(needle, sizeof(needle), "\"%s\":\"", key);
    const char *start = strstr(json, needle);
    if (start == NULL) {
        if (dst_size > 0) {
            dst[0] = '\0';
        }
        return 0;
    }
    start += strlen(needle);

    char tmp[8192];
    size_t out = 0;
    int escape = 0;
    for (const char *p = start; *p != '\0' && out + 1 < sizeof(tmp); p++) {
        if (escape) {
            switch (*p) {
                case 'n':
                    tmp[out++] = '\n';
                    break;
                case 'r':
                    tmp[out++] = '\r';
                    break;
                case 't':
                    tmp[out++] = '\t';
                    break;
                default:
                    tmp[out++] = *p;
                    break;
            }
            escape = 0;
        } else if (*p == '\\') {
            escape = 1;
        } else if (*p == '"') {
            break;
        } else {
            tmp[out++] = *p;
        }
    }
    tmp[out] = '\0';
    copy_bounded(dst, dst_size, tmp, out);
    return 1;
}

static int json_get_bool(const char *json, const char *key) {
    char needle[96];
    snprintf(needle, sizeof(needle), "\"%s\":true", key);
    return strstr(json, needle) != NULL;
}

static int send_request_and_read_response(const char *request_json, ArchpheneBridgeResult *result) {
    memset(result, 0, sizeof(*result));

    printf("ARCHPHENE_BRIDGE_JSON %s\n", request_json);
    fflush(stdout);

    if (fgets(result->raw, sizeof(result->raw), stdin) == NULL) {
        snprintf(result->reason, sizeof(result->reason), "stdin_eof");
        return -1;
    }

    size_t len = strlen(result->raw);
    while (len > 0 && (result->raw[len - 1] == '\n' || result->raw[len - 1] == '\r')) {
        result->raw[--len] = '\0';
    }

    json_get_string(result->raw, "id", result->id, sizeof(result->id));
    json_get_string(result->raw, "type", result->type, sizeof(result->type));
    json_get_string(result->raw, "reason", result->reason, sizeof(result->reason));
    json_get_string(result->raw, "uri", result->uri, sizeof(result->uri));
    json_get_string(result->raw, "text", result->text, sizeof(result->text));
    result->granted = json_get_bool(result->raw, "granted");
    result->ok = 1;
    return 0;
}

int archphene_request_permission(
        const char *id,
        const char *permission,
        const char *reason,
        ArchpheneBridgeResult *result) {
    char escaped_id[128];
    char escaped_permission[256];
    char escaped_reason[512];
    char request[1024];

    json_escape(escaped_id, sizeof(escaped_id), id);
    json_escape(escaped_permission, sizeof(escaped_permission), permission);
    json_escape(escaped_reason, sizeof(escaped_reason), reason);
    snprintf(
            request,
            sizeof(request),
            "{\"id\":\"%s\",\"type\":\"permission.request\",\"permission\":\"%s\",\"reason\":\"%s\"}",
            escaped_id,
            escaped_permission,
            escaped_reason);
    return send_request_and_read_response(request, result);
}

int archphene_open_document(
        const char *id,
        const char *mime,
        const char *reason,
        ArchpheneBridgeResult *result) {
    char escaped_id[128];
    char escaped_mime[128];
    char escaped_reason[512];
    char request[1024];

    json_escape(escaped_id, sizeof(escaped_id), id);
    json_escape(escaped_mime, sizeof(escaped_mime), mime);
    json_escape(escaped_reason, sizeof(escaped_reason), reason);
    snprintf(
            request,
            sizeof(request),
            "{\"id\":\"%s\",\"type\":\"file.open_document\",\"mime\":\"%s\",\"reason\":\"%s\"}",
            escaped_id,
            escaped_mime,
            escaped_reason);
    return send_request_and_read_response(request, result);
}

int archphene_create_document(
        const char *id,
        const char *mime,
        const char *display_name,
        const char *text,
        const char *reason,
        ArchpheneBridgeResult *result) {
    char escaped_id[128];
    char escaped_mime[128];
    char escaped_display_name[512];
    char escaped_text[4096];
    char escaped_reason[512];
    char request[6144];

    json_escape(escaped_id, sizeof(escaped_id), id);
    json_escape(escaped_mime, sizeof(escaped_mime), mime);
    json_escape(escaped_display_name, sizeof(escaped_display_name), display_name);
    json_escape(escaped_text, sizeof(escaped_text), text);
    json_escape(escaped_reason, sizeof(escaped_reason), reason);
    snprintf(
            request,
            sizeof(request),
            "{\"id\":\"%s\",\"type\":\"file.create_document\",\"mime\":\"%s\",\"display_name\":\"%s\",\"text\":\"%s\",\"reason\":\"%s\"}",
            escaped_id,
            escaped_mime,
            escaped_display_name,
            escaped_text,
            escaped_reason);
    return send_request_and_read_response(request, result);
}

int archphene_open_tree(
        const char *id,
        const char *reason,
        ArchpheneBridgeResult *result) {
    char escaped_id[128];
    char escaped_reason[512];
    char request[1024];

    json_escape(escaped_id, sizeof(escaped_id), id);
    json_escape(escaped_reason, sizeof(escaped_reason), reason);
    snprintf(
            request,
            sizeof(request),
            "{\"id\":\"%s\",\"type\":\"file.open_tree\",\"reason\":\"%s\"}",
            escaped_id,
            escaped_reason);
    return send_request_and_read_response(request, result);
}

int archphene_tree_write_file(
        const char *id,
        const char *relative_path,
        const char *mime,
        const char *text,
        const char *reason,
        ArchpheneBridgeResult *result) {
    char escaped_id[128];
    char escaped_relative_path[512];
    char escaped_mime[128];
    char escaped_text[4096];
    char escaped_reason[512];
    char request[6656];

    json_escape(escaped_id, sizeof(escaped_id), id);
    json_escape(escaped_relative_path, sizeof(escaped_relative_path), relative_path);
    json_escape(escaped_mime, sizeof(escaped_mime), mime);
    json_escape(escaped_text, sizeof(escaped_text), text);
    json_escape(escaped_reason, sizeof(escaped_reason), reason);
    snprintf(
            request,
            sizeof(request),
            "{\"id\":\"%s\",\"type\":\"tree.write_file\",\"relative_path\":\"%s\",\"mime\":\"%s\",\"text\":\"%s\",\"reason\":\"%s\"}",
            escaped_id,
            escaped_relative_path,
            escaped_mime,
            escaped_text,
            escaped_reason);
    return send_request_and_read_response(request, result);
}

int archphene_tree_read_file(
        const char *id,
        const char *relative_path,
        const char *reason,
        ArchpheneBridgeResult *result) {
    char escaped_id[128];
    char escaped_relative_path[512];
    char escaped_reason[512];
    char request[1536];

    json_escape(escaped_id, sizeof(escaped_id), id);
    json_escape(escaped_relative_path, sizeof(escaped_relative_path), relative_path);
    json_escape(escaped_reason, sizeof(escaped_reason), reason);
    snprintf(
            request,
            sizeof(request),
            "{\"id\":\"%s\",\"type\":\"tree.read_file\",\"relative_path\":\"%s\",\"reason\":\"%s\"}",
            escaped_id,
            escaped_relative_path,
            escaped_reason);
    return send_request_and_read_response(request, result);
}
int archphene_result_matches_id(const ArchpheneBridgeResult *result, const char *id) {
    return strcmp(result->id, id) == 0;
}
