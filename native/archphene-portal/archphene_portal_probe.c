#include <dbus/dbus.h>

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

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

static void check_portal_version(DBusConnection *connection) {
    const char *interface = "org.freedesktop.portal.OpenURI";
    const char *property = "version";
    DBusMessage *request = dbus_message_new_method_call(
            PORTAL_NAME, PORTAL_PATH, "org.freedesktop.DBus.Properties", "Get");
    if (request == NULL || !dbus_message_append_args(request,
            DBUS_TYPE_STRING, &interface, DBUS_TYPE_STRING, &property,
            DBUS_TYPE_INVALID)) {
        if (request != NULL) dbus_message_unref(request);
        fprintf(stderr, "FAIL portal version: arguments\n");
        exit(1);
    }
    DBusMessage *reply = call(connection, request, "portal version");
    DBusMessageIter value;
    if (!dbus_message_iter_init(reply, &value)
            || dbus_message_iter_get_arg_type(&value) != DBUS_TYPE_VARIANT) {
        dbus_message_unref(reply);
        fprintf(stderr, "FAIL portal version: wrong reply\n");
        exit(1);
    }
    DBusMessageIter variant;
    dbus_message_iter_recurse(&value, &variant);
    uint32_t version = 0;
    if (dbus_message_iter_get_arg_type(&variant) != DBUS_TYPE_UINT32) {
        dbus_message_unref(reply);
        fprintf(stderr, "FAIL portal version: wrong type\n");
        exit(1);
    }
    dbus_message_iter_get_basic(&variant, &version);
    dbus_message_unref(reply);
    if (version < 5) {
        fprintf(stderr, "FAIL portal version: %u\n", version);
        exit(1);
    }
    printf("PASS portal OpenURI version=%u\n", version);
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
    check_portal_version(connection);
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

int main(int argc, char **argv) {
    if (argc < 2 || argc > 3) {
        fprintf(stderr, "usage: %s contract|notify|withdraw [classic-id] | open URI\n",
                argv[0]);
        return 2;
    }
    DBusError error = DBUS_ERROR_INIT;
    DBusConnection *connection = dbus_bus_get_private(DBUS_BUS_SESSION, &error);
    if (connection == NULL) fail_error("connect", &error);
    dbus_connection_set_exit_on_disconnect(connection, FALSE);

    if (strcmp(argv[1], "contract") == 0 && argc == 2) {
        check_contract(connection);
    } else if (strcmp(argv[1], "notify") == 0 && argc == 2) {
        notify(connection);
    } else if (strcmp(argv[1], "withdraw") == 0) {
        uint32_t id = argc == 3 ? (uint32_t)strtoul(argv[2], NULL, 10) : 1;
        withdraw(connection, id);
    } else if (strcmp(argv[1], "open") == 0 && argc == 3) {
        open_uri(connection, argv[2]);
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