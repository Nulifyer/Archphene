#include <dbus/dbus.h>

#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#define PORTAL_NAME "org.freedesktop.portal.Desktop"
#define PORTAL_PATH "/org/freedesktop/portal/desktop"

static int fail_error(const char *label, DBusError *error) {
    fprintf(stderr, "xdg-open Android bridge: %s: %s\n", label,
            error->message == NULL ? "D-Bus request failed" : error->message);
    dbus_error_free(error);
    return 70;
}

static dbus_bool_t append_handle_token(
        DBusMessageIter *dictionary, const char *token) {
    const char *key = "handle_token";
    DBusMessageIter entry;
    DBusMessageIter variant;
    return dbus_message_iter_open_container(
                   dictionary, DBUS_TYPE_DICT_ENTRY, NULL, &entry)
            && dbus_message_iter_append_basic(
                    &entry, DBUS_TYPE_STRING, &key)
            && dbus_message_iter_open_container(
                    &entry, DBUS_TYPE_VARIANT, "s", &variant)
            && dbus_message_iter_append_basic(
                    &variant, DBUS_TYPE_STRING, &token)
            && dbus_message_iter_close_container(&entry, &variant)
            && dbus_message_iter_close_container(dictionary, &entry);
}

static int wait_for_response(
        DBusConnection *connection, const char *request_path) {
    for (int attempt = 0; attempt < 50; attempt++) {
        dbus_connection_read_write(connection, 100);
        DBusMessage *message;
        while ((message = dbus_connection_pop_message(connection)) != NULL) {
            const char *path = dbus_message_get_path(message);
            if (path != NULL
                    && strcmp(path, request_path) == 0
                    && dbus_message_is_signal(message,
                            "org.freedesktop.portal.Request", "Response")) {
                const char *destination =
                        dbus_message_get_destination(message);
                const char *unique_name =
                        dbus_bus_get_unique_name(connection);
                if (destination == NULL || unique_name == NULL
                        || strcmp(destination, unique_name) != 0) {
                    dbus_message_unref(message);
                    fputs("xdg-open Android bridge: response was not "
                            "addressed to this caller\n", stderr);
                    return 70;
                }
                uint32_t response = 2;
                DBusMessageIter arguments;
                if (!dbus_message_iter_init(message, &arguments)
                        || dbus_message_iter_get_arg_type(&arguments)
                                != DBUS_TYPE_UINT32) {
                    dbus_message_unref(message);
                    fputs("xdg-open Android bridge: malformed response\n",
                            stderr);
                    return 70;
                }
                dbus_message_iter_get_basic(&arguments, &response);
                dbus_message_unref(message);
                if (response == 0) return 0;
                fprintf(stderr,
                        "xdg-open Android bridge: portal response=%u\n",
                        response);
                return 1;
            }
            dbus_message_unref(message);
        }
    }
    fputs("xdg-open Android bridge: response timeout\n", stderr);
    return 70;
}

int main(int argc, char **argv) {
    if (argc != 2) {
        fprintf(stderr, "usage: xdg-open HTTP_OR_HTTPS_URI\n");
        return 64;
    }
    DBusError error = DBUS_ERROR_INIT;
    DBusConnection *connection =
            dbus_bus_get_private(DBUS_BUS_SESSION, &error);
    if (connection == NULL) return fail_error("connect", &error);
    dbus_connection_set_exit_on_disconnect(connection, FALSE);

    DBusError match_error = DBUS_ERROR_INIT;
    dbus_bus_add_match(connection,
            "type='signal',interface='org.freedesktop.portal.Request',"
            "member='Response'",
            &match_error);
    if (dbus_error_is_set(&match_error)) {
        int result = fail_error("response match", &match_error);
        dbus_connection_close(connection);
        dbus_connection_unref(connection);
        return result;
    }
    dbus_connection_flush(connection);

    const char *parent = "";
    char token[64];
    int token_length = snprintf(
            token, sizeof(token), "archphene_xdg_%ld", (long)getpid());
    DBusMessage *request = dbus_message_new_method_call(
            PORTAL_NAME, PORTAL_PATH,
            "org.freedesktop.portal.OpenURI", "OpenURI");
    DBusMessageIter arguments;
    DBusMessageIter dictionary;
    if (token_length <= 0 || (size_t)token_length >= sizeof(token)
            || request == NULL) {
        if (request != NULL) dbus_message_unref(request);
        dbus_connection_close(connection);
        dbus_connection_unref(connection);
        fputs("xdg-open Android bridge: allocation failed\n", stderr);
        return 70;
    }
    dbus_message_iter_init_append(request, &arguments);
    if (!dbus_message_iter_append_basic(
                &arguments, DBUS_TYPE_STRING, &parent)
            || !dbus_message_iter_append_basic(
                    &arguments, DBUS_TYPE_STRING, &argv[1])
            || !dbus_message_iter_open_container(
                    &arguments, DBUS_TYPE_ARRAY, "{sv}", &dictionary)
            || !append_handle_token(&dictionary, token)
            || !dbus_message_iter_close_container(
                    &arguments, &dictionary)) {
        dbus_message_unref(request);
        dbus_connection_close(connection);
        dbus_connection_unref(connection);
        fputs("xdg-open Android bridge: invalid arguments\n", stderr);
        return 70;
    }
    DBusMessage *reply = dbus_connection_send_with_reply_and_block(
            connection, request, 5000, &error);
    dbus_message_unref(request);
    if (reply == NULL) {
        int result = fail_error("OpenURI", &error);
        dbus_connection_close(connection);
        dbus_connection_unref(connection);
        return result;
    }
    DBusMessageIter output;
    const char *request_path = NULL;
    if (!dbus_message_iter_init(reply, &output)
            || dbus_message_iter_get_arg_type(&output)
                    != DBUS_TYPE_OBJECT_PATH) {
        dbus_message_unref(reply);
        dbus_connection_close(connection);
        dbus_connection_unref(connection);
        fputs("xdg-open Android bridge: invalid OpenURI reply\n", stderr);
        return 70;
    }
    dbus_message_iter_get_basic(&output, &request_path);
    if (request_path == NULL || strlen(request_path) >= 256) {
        dbus_message_unref(reply);
        dbus_connection_close(connection);
        dbus_connection_unref(connection);
        fputs("xdg-open Android bridge: invalid request path\n", stderr);
        return 70;
    }
    char request_path_copy[256];
    memcpy(request_path_copy, request_path, strlen(request_path) + 1);
    dbus_message_unref(reply);
    int result = wait_for_response(connection, request_path_copy);
    dbus_connection_close(connection);
    dbus_connection_unref(connection);
    return result;
}
