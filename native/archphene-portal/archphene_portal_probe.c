#include <dbus/dbus.h>

#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define PORTAL_NAME "org.freedesktop.portal.Desktop"
#define PORTAL_PATH "/org/freedesktop/portal/desktop"
#define NOTIFICATIONS_NAME "org.freedesktop.Notifications"
#define NOTIFICATIONS_PATH "/org/freedesktop/Notifications"

static void fail_error(const char *label, DBusError *error) {
    fprintf(stderr, "FAIL %s: %s\n", label,
            error->message == NULL ? "D-Bus request failed" : error->message);
    dbus_error_free(error);
    exit(1);
}

static DBusMessage *call(DBusConnection *connection, DBusMessage *request,
        const char *label) {
    if (request == NULL) {
        fprintf(stderr, "FAIL %s: allocation failed\n", label);
        exit(1);
    }
    DBusError error = DBUS_ERROR_INIT;
    DBusMessage *reply = dbus_connection_send_with_reply_and_block(
            connection, request, 5000, &error);
    dbus_message_unref(request);
    if (reply == NULL) fail_error(label, &error);
    return reply;
}

static dbus_bool_t append_empty_dict(DBusMessageIter *parent) {
    DBusMessageIter dict;
    return dbus_message_iter_open_container(parent, DBUS_TYPE_ARRAY, "{sv}", &dict)
            && dbus_message_iter_close_container(parent, &dict);
}

static dbus_bool_t append_string_property(DBusMessageIter *dict,
        const char *key, const char *value) {
    DBusMessageIter entry;
    DBusMessageIter variant;
    return dbus_message_iter_open_container(dict, DBUS_TYPE_DICT_ENTRY, NULL, &entry)
            && dbus_message_iter_append_basic(&entry, DBUS_TYPE_STRING, &key)
            && dbus_message_iter_open_container(&entry, DBUS_TYPE_VARIANT, "s", &variant)
            && dbus_message_iter_append_basic(&variant, DBUS_TYPE_STRING, &value)
            && dbus_message_iter_close_container(&entry, &variant)
            && dbus_message_iter_close_container(dict, &entry);
}

static uint32_t wait_request_response(DBusConnection *connection,
        const char *path, const char *label) {
    for (int attempt = 0; attempt < 50; attempt++) {
        dbus_connection_read_write(connection, 100);
        DBusMessage *message;
        while ((message = dbus_connection_pop_message(connection)) != NULL) {
            const char *message_path = dbus_message_get_path(message);
            if (message_path != NULL
                    && dbus_message_is_signal(message,
                            "org.freedesktop.portal.Request", "Response")
                    && strcmp(message_path, path) == 0) {
                uint32_t response = 2;
                DBusMessageIter arguments;
                if (!dbus_message_iter_init(message, &arguments)
                        || dbus_message_iter_get_arg_type(&arguments)
                                != DBUS_TYPE_UINT32) {
                    dbus_message_unref(message);
                    fprintf(stderr, "FAIL %s: malformed response signal\n", label);
                    exit(1);
                }
                dbus_message_iter_get_basic(&arguments, &response);
                dbus_message_unref(message);
                return response;
            }
            dbus_message_unref(message);
        }
    }
    fprintf(stderr, "FAIL %s: response signal timeout\n", label);
    exit(1);
}

static uint32_t portal_version(DBusConnection *connection,
        const char *interface, const char *label) {
    const char *property = "version";
    DBusMessage *request = dbus_message_new_method_call(
            PORTAL_NAME, PORTAL_PATH, "org.freedesktop.DBus.Properties", "Get");
    if (request == NULL || !dbus_message_append_args(request,
            DBUS_TYPE_STRING, &interface, DBUS_TYPE_STRING, &property,
            DBUS_TYPE_INVALID)) {
        if (request != NULL) dbus_message_unref(request);
        fprintf(stderr, "FAIL %s: arguments\n", label);
        exit(1);
    }
    DBusMessage *reply = call(connection, request, label);
    DBusMessageIter value;
    if (!dbus_message_iter_init(reply, &value)
            || dbus_message_iter_get_arg_type(&value) != DBUS_TYPE_VARIANT) {
        dbus_message_unref(reply);
        fprintf(stderr, "FAIL %s: wrong reply\n", label);
        exit(1);
    }
    DBusMessageIter variant;
    dbus_message_iter_recurse(&value, &variant);
    uint32_t version = 0;
    if (dbus_message_iter_get_arg_type(&variant) != DBUS_TYPE_UINT32) {
        dbus_message_unref(reply);
        fprintf(stderr, "FAIL %s: wrong type\n", label);
        exit(1);
    }
    dbus_message_iter_get_basic(&variant, &version);
    dbus_message_unref(reply);
    return version;
}

static dbus_bool_t bool_property(DBusConnection *connection,
        const char *interface, const char *property, const char *label) {
    DBusMessage *request = dbus_message_new_method_call(
            PORTAL_NAME, PORTAL_PATH, "org.freedesktop.DBus.Properties", "Get");
    if (request == NULL || !dbus_message_append_args(request,
            DBUS_TYPE_STRING, &interface, DBUS_TYPE_STRING, &property,
            DBUS_TYPE_INVALID)) {
        if (request != NULL) dbus_message_unref(request);
        fprintf(stderr, "FAIL %s: arguments\n", label);
        exit(1);
    }
    DBusMessage *reply = call(connection, request, label);
    DBusMessageIter value;
    DBusMessageIter variant;
    dbus_bool_t result = FALSE;
    if (!dbus_message_iter_init(reply, &value)
            || dbus_message_iter_get_arg_type(&value) != DBUS_TYPE_VARIANT) {
        dbus_message_unref(reply);
        fprintf(stderr, "FAIL %s: wrong reply\n", label);
        exit(1);
    }
    dbus_message_iter_recurse(&value, &variant);
    if (dbus_message_iter_get_arg_type(&variant) != DBUS_TYPE_BOOLEAN) {
        dbus_message_unref(reply);
        fprintf(stderr, "FAIL %s: wrong type\n", label);
        exit(1);
    }
    dbus_message_iter_get_basic(&variant, &result);
    dbus_message_unref(reply);
    return result;
}

static dbus_bool_t scheme_supported(DBusConnection *connection, const char *scheme) {
    DBusMessage *request = dbus_message_new_method_call(
            PORTAL_NAME, PORTAL_PATH, "org.freedesktop.portal.OpenURI",
            "SchemeSupported");
    DBusMessageIter arguments;
    if (request == NULL) {
        fprintf(stderr, "FAIL scheme: allocation\n");
        exit(1);
    }
    dbus_message_iter_init_append(request, &arguments);
    if (!dbus_message_iter_append_basic(&arguments, DBUS_TYPE_STRING, &scheme)
            || !append_empty_dict(&arguments)) {
        dbus_message_unref(request);
        fprintf(stderr, "FAIL scheme: arguments\n");
        exit(1);
    }
    DBusMessage *reply = call(connection, request, "scheme support");
    DBusError error = DBUS_ERROR_INIT;
    dbus_bool_t supported = FALSE;
    if (!dbus_message_get_args(reply, &error,
            DBUS_TYPE_BOOLEAN, &supported, DBUS_TYPE_INVALID)) {
        dbus_message_unref(reply);
        fail_error("scheme reply", &error);
    }
    dbus_message_unref(reply);
    return supported;
}

static void check_contract(DBusConnection *connection) {
    uint32_t open_uri_version = portal_version(connection,
            "org.freedesktop.portal.OpenURI", "portal OpenURI version");
    if (open_uri_version < 5) {
        fprintf(stderr, "FAIL portal OpenURI version: %u\n", open_uri_version);
        exit(1);
    }
    printf("PASS portal OpenURI version=%u\n", open_uri_version);
    uint32_t print_version = portal_version(connection,
            "org.freedesktop.portal.Print", "portal Print version");
    if (print_version < 4) {
        fprintf(stderr, "FAIL portal Print version: %u\n", print_version);
        exit(1);
    }
    printf("PASS portal Print version=%u\n", print_version);
    uint32_t camera_version = portal_version(connection,
            "org.freedesktop.portal.Camera", "portal Camera version");
    if (camera_version < 1) {
        fprintf(stderr, "FAIL portal Camera version: %u\n", camera_version);
        exit(1);
    }
    dbus_bool_t present = bool_property(connection,
            "org.freedesktop.portal.Camera", "IsCameraPresent",
            "portal camera presence");
    printf("PASS portal Camera version=%u present=%s\n",
            camera_version, present ? "true" : "false");
    if (!scheme_supported(connection, "https")
            || scheme_supported(connection, "file")) {
        fprintf(stderr, "FAIL portal scheme policy\n");
        exit(1);
    }
    printf("PASS portal scheme policy\n");

    DBusMessage *reply = call(connection, dbus_message_new_method_call(
            NOTIFICATIONS_NAME, NOTIFICATIONS_PATH,
            "org.freedesktop.Notifications", "GetServerInformation"),
            "notification server");
    DBusError error = DBUS_ERROR_INIT;
    const char *name = NULL;
    const char *vendor = NULL;
    const char *version = NULL;
    const char *spec = NULL;
    if (!dbus_message_get_args(reply, &error,
            DBUS_TYPE_STRING, &name, DBUS_TYPE_STRING, &vendor,
            DBUS_TYPE_STRING, &version, DBUS_TYPE_STRING, &spec,
            DBUS_TYPE_INVALID)) {
        dbus_message_unref(reply);
        fail_error("notification server reply", &error);
    }
    printf("PASS classic notification server=%s spec=%s\n", name, spec);
    dbus_message_unref(reply);
}

static void add_portal_notification(DBusConnection *connection) {
    const char *id = "archphene-probe";
    const char *title = "Archphene portal probe";
    const char *body = "Standard XDG portal notification reached Android";
    DBusMessage *request = dbus_message_new_method_call(
            PORTAL_NAME, PORTAL_PATH,
            "org.freedesktop.portal.Notification", "AddNotification");
    DBusMessageIter arguments;
    DBusMessageIter dict;
    if (request == NULL) {
        fprintf(stderr, "FAIL portal notification: allocation\n");
        exit(1);
    }
    dbus_message_iter_init_append(request, &arguments);
    if (!dbus_message_iter_append_basic(&arguments, DBUS_TYPE_STRING, &id)
            || !dbus_message_iter_open_container(
                    &arguments, DBUS_TYPE_ARRAY, "{sv}", &dict)
            || !append_string_property(&dict, "title", title)
            || !append_string_property(&dict, "body", body)
            || !dbus_message_iter_close_container(&arguments, &dict)) {
        dbus_message_unref(request);
        fprintf(stderr, "FAIL portal notification: arguments\n");
        exit(1);
    }
    dbus_message_unref(call(connection, request, "portal notification"));
    printf("PASS portal notification accepted\n");
}

static uint32_t add_classic_notification(DBusConnection *connection) {
    const char *app = "Archphene probe";
    uint32_t replaces = 0;
    const char *icon = "";
    const char *summary = "Archphene classic probe";
    const char *body = "freedesktop.org notification reached Android";
    int32_t timeout = -1;
    DBusMessage *request = dbus_message_new_method_call(
            NOTIFICATIONS_NAME, NOTIFICATIONS_PATH,
            "org.freedesktop.Notifications", "Notify");
    DBusMessageIter arguments;
    DBusMessageIter actions;
    if (request == NULL) {
        fprintf(stderr, "FAIL classic notification: allocation\n");
        exit(1);
    }
    dbus_message_iter_init_append(request, &arguments);
    if (!dbus_message_iter_append_basic(&arguments, DBUS_TYPE_STRING, &app)
            || !dbus_message_iter_append_basic(&arguments, DBUS_TYPE_UINT32, &replaces)
            || !dbus_message_iter_append_basic(&arguments, DBUS_TYPE_STRING, &icon)
            || !dbus_message_iter_append_basic(&arguments, DBUS_TYPE_STRING, &summary)
            || !dbus_message_iter_append_basic(&arguments, DBUS_TYPE_STRING, &body)
            || !dbus_message_iter_open_container(
                    &arguments, DBUS_TYPE_ARRAY, "s", &actions)
            || !dbus_message_iter_close_container(&arguments, &actions)
            || !append_empty_dict(&arguments)
            || !dbus_message_iter_append_basic(&arguments, DBUS_TYPE_INT32, &timeout)) {
        dbus_message_unref(request);
        fprintf(stderr, "FAIL classic notification: arguments\n");
        exit(1);
    }
    DBusMessage *reply = call(connection, request, "classic notification");
    DBusError error = DBUS_ERROR_INIT;
    uint32_t id = 0;
    if (!dbus_message_get_args(reply, &error,
            DBUS_TYPE_UINT32, &id, DBUS_TYPE_INVALID)) {
        dbus_message_unref(reply);
        fail_error("classic notification reply", &error);
    }
    dbus_message_unref(reply);
    printf("PASS classic notification accepted id=%u\n", id);
    return id;
}

static void notify(DBusConnection *connection) {
    add_portal_notification(connection);
    (void)add_classic_notification(connection);
}

static void withdraw(DBusConnection *connection, uint32_t classic_id) {
    const char *portal_id = "archphene-probe";
    DBusMessage *request = dbus_message_new_method_call(
            PORTAL_NAME, PORTAL_PATH,
            "org.freedesktop.portal.Notification", "RemoveNotification");
    if (request == NULL || !dbus_message_append_args(request,
            DBUS_TYPE_STRING, &portal_id, DBUS_TYPE_INVALID)) {
        if (request != NULL) dbus_message_unref(request);
        fprintf(stderr, "FAIL portal withdrawal: arguments\n");
        exit(1);
    }
    dbus_message_unref(call(connection, request, "portal withdrawal"));

    request = dbus_message_new_method_call(
            NOTIFICATIONS_NAME, NOTIFICATIONS_PATH,
            "org.freedesktop.Notifications", "CloseNotification");
    if (request == NULL || !dbus_message_append_args(request,
            DBUS_TYPE_UINT32, &classic_id, DBUS_TYPE_INVALID)) {
        if (request != NULL) dbus_message_unref(request);
        fprintf(stderr, "FAIL classic withdrawal: arguments\n");
        exit(1);
    }
    dbus_message_unref(call(connection, request, "classic withdrawal"));
    printf("PASS notification withdrawal\n");
}

static void open_uri(DBusConnection *connection, const char *uri) {
    const char *parent = "";
    const char *token_key = "handle_token";
    const char *token = "archphene_probe";
    DBusMessage *request = dbus_message_new_method_call(
            PORTAL_NAME, PORTAL_PATH,
            "org.freedesktop.portal.OpenURI", "OpenURI");
    DBusMessageIter arguments;
    DBusMessageIter dict;
    if (request == NULL) {
        fprintf(stderr, "FAIL portal OpenURI: allocation\n");
        exit(1);
    }
    dbus_message_iter_init_append(request, &arguments);
    if (!dbus_message_iter_append_basic(&arguments, DBUS_TYPE_STRING, &parent)
            || !dbus_message_iter_append_basic(&arguments, DBUS_TYPE_STRING, &uri)
            || !dbus_message_iter_open_container(
                    &arguments, DBUS_TYPE_ARRAY, "{sv}", &dict)
            || !append_string_property(&dict, token_key, token)
            || !dbus_message_iter_close_container(&arguments, &dict)) {
        dbus_message_unref(request);
        fprintf(stderr, "FAIL portal OpenURI: arguments\n");
        exit(1);
    }
    DBusMessage *reply = call(connection, request, "portal OpenURI");
    DBusMessageIter output;
    if (!dbus_message_iter_init(reply, &output)
            || dbus_message_iter_get_arg_type(&output) != DBUS_TYPE_OBJECT_PATH) {
        dbus_message_unref(reply);
        fprintf(stderr, "FAIL portal OpenURI: wrong reply\n");
        exit(1);
    }
    dbus_message_unref(reply);
    printf("PASS portal OpenURI accepted\n");
}

static void prepare_print(DBusConnection *connection) {
    const char *parent = "";
    const char *title = "Archphene prepare print probe";
    const char *token_key = "handle_token";
    const char *token = "archphene_prepare_probe";
    DBusMessage *request = dbus_message_new_method_call(
            PORTAL_NAME, PORTAL_PATH, "org.freedesktop.portal.Print", "PreparePrint");
    DBusMessageIter arguments;
    DBusMessageIter options;
    if (request == NULL) {
        fprintf(stderr, "FAIL portal PreparePrint: allocation\n");
        exit(1);
    }
    dbus_message_iter_init_append(request, &arguments);
    if (!dbus_message_iter_append_basic(&arguments, DBUS_TYPE_STRING, &parent)
            || !dbus_message_iter_append_basic(&arguments, DBUS_TYPE_STRING, &title)
            || !append_empty_dict(&arguments)
            || !append_empty_dict(&arguments)
            || !dbus_message_iter_open_container(
                    &arguments, DBUS_TYPE_ARRAY, "{sv}", &options)
            || !append_string_property(&options, token_key, token)
            || !dbus_message_iter_close_container(&arguments, &options)) {
        dbus_message_unref(request);
        fprintf(stderr, "FAIL portal PreparePrint: arguments\n");
        exit(1);
    }
    DBusMessage *reply = call(connection, request, "portal PreparePrint");
    DBusMessageIter output;
    const char *response_path = NULL;
    if (!dbus_message_iter_init(reply, &output)
            || dbus_message_iter_get_arg_type(&output) != DBUS_TYPE_OBJECT_PATH) {
        dbus_message_unref(reply);
        fprintf(stderr, "FAIL portal PreparePrint: wrong reply\n");
        exit(1);
    }
    dbus_message_iter_get_basic(&output, &response_path);
    char path_copy[256];
    if (response_path == NULL || strlen(response_path) >= sizeof(path_copy)) {
        dbus_message_unref(reply);
        fprintf(stderr, "FAIL portal PreparePrint: invalid response path\n");
        exit(1);
    }
    strcpy(path_copy, response_path);
    dbus_message_unref(reply);
    uint32_t response = wait_request_response(
            connection, path_copy, "portal PreparePrint");
    if (response != 0) {
        fprintf(stderr, "FAIL portal PreparePrint: response=%u\n", response);
        exit(1);
    }
    printf("PASS portal PreparePrint accepted\n");
}

static void print_pdf(DBusConnection *connection, const char *path, uint32_t expected_response);
static void print_pipe(DBusConnection *connection) {
    int descriptors[2];
    if (pipe(descriptors) != 0) {
        perror("FAIL create print pipe");
        exit(1);
    }
    if (fcntl(descriptors[0], F_SETFD, FD_CLOEXEC) != 0
            || fcntl(descriptors[1], F_SETFD, FD_CLOEXEC) != 0) {
        close(descriptors[0]);
        close(descriptors[1]);
        perror("FAIL secure print pipe");
        exit(1);
    }
    const char header[] = "%PDF-";
    ssize_t written = write(descriptors[1], header, sizeof(header) - 1);
    close(descriptors[1]);
    if (written != (ssize_t)(sizeof(header) - 1)) {
        close(descriptors[0]);
        fprintf(stderr, "FAIL write print pipe\n");
        exit(1);
    }
    char path[64];
    snprintf(path, sizeof(path), "/proc/self/fd/%d", descriptors[0]);
    print_pdf(connection, path, 2);
    close(descriptors[0]);
}

static void print_pdf(DBusConnection *connection, const char *path, uint32_t expected_response) {
    int pdf_fd = open(path, O_RDONLY | O_CLOEXEC);
    if (pdf_fd < 0) {
        perror("FAIL open print PDF");
        exit(1);
    }
    const char *parent = "";
    const char *title = "Archphene portal print probe";
    const char *token_key = "handle_token";
    const char *token = "archphene_print_probe";
    DBusMessage *request = dbus_message_new_method_call(
            PORTAL_NAME, PORTAL_PATH, "org.freedesktop.portal.Print", "Print");
    DBusMessageIter arguments;
    DBusMessageIter dict;
    if (request == NULL) {
        close(pdf_fd);
        fprintf(stderr, "FAIL portal Print: allocation\n");
        exit(1);
    }
    dbus_message_iter_init_append(request, &arguments);
    if (!dbus_message_iter_append_basic(&arguments, DBUS_TYPE_STRING, &parent)
            || !dbus_message_iter_append_basic(&arguments, DBUS_TYPE_STRING, &title)
            || !dbus_message_iter_append_basic(&arguments, DBUS_TYPE_UNIX_FD, &pdf_fd)
            || !dbus_message_iter_open_container(
                    &arguments, DBUS_TYPE_ARRAY, "{sv}", &dict)
            || !append_string_property(&dict, token_key, token)
            || !dbus_message_iter_close_container(&arguments, &dict)) {
        dbus_message_unref(request);
        close(pdf_fd);
        fprintf(stderr, "FAIL portal Print: arguments\n");
        exit(1);
    }
    DBusMessage *reply = call(connection, request, "portal Print");
    close(pdf_fd);
    DBusMessageIter output;
    const char *response_path = NULL;
    if (!dbus_message_iter_init(reply, &output)
            || dbus_message_iter_get_arg_type(&output) != DBUS_TYPE_OBJECT_PATH) {
        dbus_message_unref(reply);
        fprintf(stderr, "FAIL portal Print: wrong reply\n");
        exit(1);
    }
    dbus_message_iter_get_basic(&output, &response_path);
    char path_copy[256];
    if (response_path == NULL || strlen(response_path) >= sizeof(path_copy)) {
        dbus_message_unref(reply);
        fprintf(stderr, "FAIL portal Print: invalid response path\n");
        exit(1);
    }
    strcpy(path_copy, response_path);
    dbus_message_unref(reply);
    uint32_t response = wait_request_response(connection, path_copy, "portal Print");
    if (response != expected_response) {
        fprintf(stderr, "FAIL portal Print: response=%u expected=%u\n",
                response, expected_response);
        exit(1);
    }
    printf(expected_response == 0
            ? "PASS portal Print accepted\n"
            : "PASS portal Print rejected non-regular descriptor\n");
}

static void camera_access(DBusConnection *connection) {
    const char *key = "handle_token";
    const char *token = "archphene_camera_probe";
    DBusMessage *request = dbus_message_new_method_call(
            PORTAL_NAME, PORTAL_PATH,
            "org.freedesktop.portal.Camera", "AccessCamera");
    DBusMessageIter arguments;
    DBusMessageIter dict;
    if (request == NULL) {
        fprintf(stderr, "FAIL camera access: allocation\n");
        exit(1);
    }
    dbus_message_iter_init_append(request, &arguments);
    if (!dbus_message_iter_open_container(
                &arguments, DBUS_TYPE_ARRAY, "{sv}", &dict)
            || !append_string_property(&dict, key, token)
            || !dbus_message_iter_close_container(&arguments, &dict)) {
        dbus_message_unref(request);
        fprintf(stderr, "FAIL camera access: arguments\n");
        exit(1);
    }
    DBusMessage *reply = call(connection, request, "camera access");
    DBusMessageIter output;
    const char *response_path = NULL;
    if (!dbus_message_iter_init(reply, &output)
            || dbus_message_iter_get_arg_type(&output) != DBUS_TYPE_OBJECT_PATH) {
        dbus_message_unref(reply);
        fprintf(stderr, "FAIL camera access: wrong reply\n");
        exit(1);
    }
    dbus_message_iter_get_basic(&output, &response_path);
    char path_copy[256];
    if (response_path == NULL || strlen(response_path) >= sizeof(path_copy)) {
        dbus_message_unref(reply);
        fprintf(stderr, "FAIL camera access: invalid response path\n");
        exit(1);
    }
    strcpy(path_copy, response_path);
    dbus_message_unref(reply);
    uint32_t response = wait_request_response(connection, path_copy, "camera access");
    if (response != 0) {
        fprintf(stderr, "FAIL camera access: response=%u\n", response);
        exit(1);
    }
    printf("PASS portal camera access accepted\n");
}

static void camera_open(DBusConnection *connection) {
    DBusMessage *request = dbus_message_new_method_call(
            PORTAL_NAME, PORTAL_PATH,
            "org.freedesktop.portal.Camera", "OpenPipeWireRemote");
    DBusMessageIter arguments;
    if (request == NULL) {
        fprintf(stderr, "FAIL camera remote: allocation\n");
        exit(1);
    }
    dbus_message_iter_init_append(request, &arguments);
    if (!append_empty_dict(&arguments)) {
        dbus_message_unref(request);
        fprintf(stderr, "FAIL camera remote: arguments\n");
        exit(1);
    }
    DBusMessage *reply = call(connection, request, "camera remote");
    DBusError error = DBUS_ERROR_INIT;
    int remote = -1;
    if (!dbus_message_get_args(reply, &error,
            DBUS_TYPE_UNIX_FD, &remote, DBUS_TYPE_INVALID)) {
        dbus_message_unref(reply);
        fail_error("camera remote reply", &error);
    }
    if (write(remote, "PING", 4) != 4) {
        dbus_message_unref(reply);
        close(remote);
        perror("FAIL camera remote write");
        exit(1);
    }
    char response[4];
    size_t received = 0;
    while (received < sizeof(response)) {
        ssize_t count = read(remote, response + received, sizeof(response) - received);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) break;
        received += (size_t)count;
    }
    close(remote);
    dbus_message_unref(reply);
    if (received != sizeof(response) || memcmp(response, "PONG", 4) != 0) {
        fprintf(stderr, "FAIL camera remote descriptor transport\n");
        exit(1);
    }
    printf("PASS portal PipeWire remote descriptor\n");
}

int main(int argc, char **argv) {
    if (argc < 2 || argc > 3) {
        fprintf(stderr, "usage: %s contract|notify|withdraw [classic-id] | open URI | print PDF | print-pipe | camera-access | camera-open\n",
                argv[0]);
        return 2;
    }
    DBusError error = DBUS_ERROR_INIT;
    DBusConnection *connection = dbus_bus_get_private(DBUS_BUS_SESSION, &error);
    if (connection == NULL) fail_error("connect", &error);
    dbus_connection_set_exit_on_disconnect(connection, FALSE);

    DBusError match_error = DBUS_ERROR_INIT;
    dbus_bus_add_match(connection,
            "type='signal',interface='org.freedesktop.portal.Request',member='Response'",
            &match_error);
    if (dbus_error_is_set(&match_error)) fail_error("request response match", &match_error);
    dbus_connection_flush(connection);

    if (strcmp(argv[1], "contract") == 0 && argc == 2) {
        check_contract(connection);
        prepare_print(connection);
    } else if (strcmp(argv[1], "notify") == 0 && argc == 2) {
        notify(connection);
    } else if (strcmp(argv[1], "withdraw") == 0) {
        uint32_t id = argc == 3 ? (uint32_t)strtoul(argv[2], NULL, 10) : 1;
        withdraw(connection, id);
    } else if (strcmp(argv[1], "open") == 0 && argc == 3) {
        open_uri(connection, argv[2]);
    } else if (strcmp(argv[1], "print") == 0 && argc == 3) {
        print_pdf(connection, argv[2], 0);
    } else if (strcmp(argv[1], "print-pipe") == 0 && argc == 2) {
        print_pipe(connection);
    } else if (strcmp(argv[1], "camera-access") == 0 && argc == 2) {
        camera_access(connection);
    } else if (strcmp(argv[1], "camera-open") == 0 && argc == 2) {
        camera_open(connection);
    } else {
        fprintf(stderr, "invalid probe arguments\n");
        dbus_connection_close(connection);
        dbus_connection_unref(connection);
        return 2;
    }

    dbus_connection_close(connection);
    dbus_connection_unref(connection);
    return 0;
}
