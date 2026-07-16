#include <dbus/dbus.h>

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define NAME "org.freedesktop.secrets"
#define SERVICE "/org/freedesktop/secrets"
#define COLLECTION "/org/freedesktop/secrets/collection/login"
#define SERVICE_IFACE "org.freedesktop.Secret.Service"
#define COLLECTION_IFACE "org.freedesktop.Secret.Collection"
#define ITEM_IFACE "org.freedesktop.Secret.Item"
#define SESSION_IFACE "org.freedesktop.Secret.Session"

static void fail(const char *step, const char *detail) {
    fprintf(stderr, "FAIL %s: %s\n", step, detail);
    exit(1);
}

static DBusMessage *call(DBusConnection *connection, DBusMessage *request,
        const char *step) {
    if (request == NULL) fail(step, "allocation");
    DBusError error = DBUS_ERROR_INIT;
    DBusMessage *reply = dbus_connection_send_with_reply_and_block(
            connection, request, 5000, &error);
    dbus_message_unref(request);
    if (reply == NULL) {
        const char *detail = error.message == NULL ? "D-Bus failure" : error.message;
        fprintf(stderr, "FAIL %s: %s (%s)\n", step, detail,
                error.name == NULL ? "unknown" : error.name);
        dbus_error_free(&error);
        exit(1);
    }
    return reply;
}

static void expect_error(DBusConnection *connection, DBusMessage *request,
        const char *name, const char *step) {
    DBusError error = DBUS_ERROR_INIT;
    DBusMessage *reply = dbus_connection_send_with_reply_and_block(
            connection, request, 5000, &error);
    dbus_message_unref(request);
    if (reply != NULL) {
        dbus_message_unref(reply);
        fail(step, "request unexpectedly succeeded");
    }
    if (error.name == NULL || strcmp(error.name, name) != 0) {
        fprintf(stderr, "FAIL %s: expected %s, got %s\n", step, name,
                error.name == NULL ? "none" : error.name);
        dbus_error_free(&error);
        exit(1);
    }
    dbus_error_free(&error);
}

static char *open_session(DBusConnection *connection) {
    DBusMessage *request = dbus_message_new_method_call(
            NAME, SERVICE, SERVICE_IFACE, "OpenSession");
    DBusMessageIter output, variant;
    const char *algorithm = "plain";
    const char *empty = "";
    dbus_message_iter_init_append(request, &output);
    if (!dbus_message_iter_append_basic(&output, DBUS_TYPE_STRING, &algorithm)
            || !dbus_message_iter_open_container(&output, DBUS_TYPE_VARIANT, "s", &variant)
            || !dbus_message_iter_append_basic(&variant, DBUS_TYPE_STRING, &empty)
            || !dbus_message_iter_close_container(&output, &variant))
        fail("OpenSession", "arguments");
    DBusMessage *reply = call(connection, request, "OpenSession");
    DBusMessageIter input;
    if (!dbus_message_iter_init(reply, &input)
            || dbus_message_iter_get_arg_type(&input) != DBUS_TYPE_VARIANT
            || !dbus_message_iter_next(&input)
            || dbus_message_iter_get_arg_type(&input) != DBUS_TYPE_OBJECT_PATH)
        fail("OpenSession", "reply signature");
    const char *path = NULL;
    dbus_message_iter_get_basic(&input, &path);
    char *copy = path == NULL ? NULL : strdup(path);
    dbus_message_unref(reply);
    if (copy == NULL) fail("OpenSession", "session path");
    return copy;
}

static dbus_bool_t append_attributes(DBusMessageIter *parent) {
    DBusMessageIter array, entry;
    const char *key = "application";
    const char *value = "archphene-secret-service-probe";
    return dbus_message_iter_open_container(parent, DBUS_TYPE_ARRAY, "{ss}", &array)
            && dbus_message_iter_open_container(&array, DBUS_TYPE_DICT_ENTRY, NULL, &entry)
            && dbus_message_iter_append_basic(&entry, DBUS_TYPE_STRING, &key)
            && dbus_message_iter_append_basic(&entry, DBUS_TYPE_STRING, &value)
            && dbus_message_iter_close_container(&array, &entry)
            && dbus_message_iter_close_container(parent, &array);
}

static dbus_bool_t append_secret(DBusMessageIter *parent, const char *session,
        const uint8_t *value, int length) {
    DBusMessageIter structure, parameters, bytes;
    const uint8_t *empty = value;
    const char *content = "text/plain; charset=utf-8";
    return dbus_message_iter_open_container(parent, DBUS_TYPE_STRUCT, NULL, &structure)
            && dbus_message_iter_append_basic(&structure, DBUS_TYPE_OBJECT_PATH, &session)
            && dbus_message_iter_open_container(&structure, DBUS_TYPE_ARRAY, "y", &parameters)
            && dbus_message_iter_append_fixed_array(&parameters, DBUS_TYPE_BYTE, &empty, 0)
            && dbus_message_iter_close_container(&structure, &parameters)
            && dbus_message_iter_open_container(&structure, DBUS_TYPE_ARRAY, "y", &bytes)
            && dbus_message_iter_append_fixed_array(&bytes, DBUS_TYPE_BYTE, &value, length)
            && dbus_message_iter_close_container(&structure, &bytes)
            && dbus_message_iter_append_basic(&structure, DBUS_TYPE_STRING, &content)
            && dbus_message_iter_close_container(parent, &structure);
}

static char *create_item(DBusConnection *connection, const char *session,
        const char *secret, dbus_bool_t replace) {
    DBusMessage *request = dbus_message_new_method_call(
            NAME, COLLECTION, COLLECTION_IFACE, "CreateItem");
    DBusMessageIter output, properties, entry, variant;
    const char *label_name = ITEM_IFACE ".Label";
    const char *attributes_name = ITEM_IFACE ".Attributes";
    const char *label = "Archphene Secret Service Probe";
    dbus_message_iter_init_append(request, &output);
    if (!dbus_message_iter_open_container(&output, DBUS_TYPE_ARRAY, "{sv}", &properties)
            || !dbus_message_iter_open_container(&properties, DBUS_TYPE_DICT_ENTRY, NULL, &entry)
            || !dbus_message_iter_append_basic(&entry, DBUS_TYPE_STRING, &label_name)
            || !dbus_message_iter_open_container(&entry, DBUS_TYPE_VARIANT, "s", &variant)
            || !dbus_message_iter_append_basic(&variant, DBUS_TYPE_STRING, &label)
            || !dbus_message_iter_close_container(&entry, &variant)
            || !dbus_message_iter_close_container(&properties, &entry)
            || !dbus_message_iter_open_container(&properties, DBUS_TYPE_DICT_ENTRY, NULL, &entry)
            || !dbus_message_iter_append_basic(&entry, DBUS_TYPE_STRING, &attributes_name)
            || !dbus_message_iter_open_container(&entry, DBUS_TYPE_VARIANT, "a{ss}", &variant)
            || !append_attributes(&variant)
            || !dbus_message_iter_close_container(&entry, &variant)
            || !dbus_message_iter_close_container(&properties, &entry)
            || !dbus_message_iter_close_container(&output, &properties)
            || !append_secret(&output, session, (const uint8_t *)secret, (int)strlen(secret))
            || !dbus_message_iter_append_basic(&output, DBUS_TYPE_BOOLEAN, &replace))
        fail("CreateItem", "arguments");
    DBusMessage *reply = call(connection, request, "CreateItem");
    DBusError error = DBUS_ERROR_INIT;
    const char *item = NULL;
    const char *prompt = NULL;
    if (!dbus_message_get_args(reply, &error, DBUS_TYPE_OBJECT_PATH, &item,
            DBUS_TYPE_OBJECT_PATH, &prompt, DBUS_TYPE_INVALID))
        fail("CreateItem", error.message == NULL ? "reply" : error.message);
    char *copy = strdup(item);
    dbus_message_unref(reply);
    if (copy == NULL || strcmp(prompt, "/") != 0) fail("CreateItem", "paths");
    return copy;
}

static size_t search(DBusConnection *connection, char **first_path) {
    DBusMessage *request = dbus_message_new_method_call(
            NAME, COLLECTION, COLLECTION_IFACE, "SearchItems");
    DBusMessageIter output;
    dbus_message_iter_init_append(request, &output);
    if (!append_attributes(&output)) fail("SearchItems", "arguments");
    DBusMessage *reply = call(connection, request, "SearchItems");
    DBusMessageIter input, array;
    if (!dbus_message_iter_init(reply, &input)
            || dbus_message_iter_get_arg_type(&input) != DBUS_TYPE_ARRAY)
        fail("SearchItems", "reply");
    dbus_message_iter_recurse(&input, &array);
    size_t count = 0;
    while (dbus_message_iter_get_arg_type(&array) != DBUS_TYPE_INVALID) {
        if (dbus_message_iter_get_arg_type(&array) != DBUS_TYPE_OBJECT_PATH)
            fail("SearchItems", "item type");
        const char *path = NULL;
        dbus_message_iter_get_basic(&array, &path);
        if (count == 0 && first_path != NULL) *first_path = strdup(path);
        count++;
        dbus_message_iter_next(&array);
    }
    dbus_message_unref(reply);
    return count;
}

static void get_secret(DBusConnection *connection, const char *item,
        const char *session, const char *expected) {
    DBusMessage *request = dbus_message_new_method_call(
            NAME, item, ITEM_IFACE, "GetSecret");
    if (!dbus_message_append_args(request, DBUS_TYPE_OBJECT_PATH,
            &session, DBUS_TYPE_INVALID)) fail("GetSecret", "arguments");
    DBusMessage *reply = call(connection, request, "GetSecret");
    DBusMessageIter input, structure;
    if (!dbus_message_iter_init(reply, &input)
            || dbus_message_iter_get_arg_type(&input) != DBUS_TYPE_STRUCT)
        fail("GetSecret", "reply");
    dbus_message_iter_recurse(&input, &structure);
    const char *returned_session = NULL;
    dbus_message_iter_get_basic(&structure, &returned_session);
    if (!dbus_message_iter_next(&structure) || !dbus_message_iter_next(&structure)
            || dbus_message_iter_get_arg_type(&structure) != DBUS_TYPE_ARRAY)
        fail("GetSecret", "secret fields");
    DBusMessageIter bytes;
    dbus_message_iter_recurse(&structure, &bytes);
    const uint8_t *value = NULL;
    int length = 0;
    dbus_message_iter_get_fixed_array(&bytes, &value, &length);
    if (returned_session == NULL || strcmp(returned_session, session) != 0
            || length != (int)strlen(expected)
            || memcmp(value, expected, (size_t)length) != 0)
        fail("GetSecret", "payload mismatch");
    dbus_message_unref(reply);
}

static void set_secret(DBusConnection *connection, const char *item,
        const char *session, const char *value) {
    DBusMessage *request = dbus_message_new_method_call(
            NAME, item, ITEM_IFACE, "SetSecret");
    DBusMessageIter output;
    dbus_message_iter_init_append(request, &output);
    if (!append_secret(&output, session, (const uint8_t *)value, (int)strlen(value)))
        fail("SetSecret", "arguments");
    dbus_message_unref(call(connection, request, "SetSecret"));
}

static void close_session(DBusConnection *connection, const char *session) {
    DBusMessage *request = dbus_message_new_method_call(
            NAME, session, SESSION_IFACE, "Close");
    dbus_message_unref(call(connection, request, "Close session"));
}

static void delete_item(DBusConnection *connection, const char *item) {
    DBusMessage *request = dbus_message_new_method_call(
            NAME, item, ITEM_IFACE, "Delete");
    DBusMessage *reply = call(connection, request, "Delete item");
    DBusError error = DBUS_ERROR_INIT;
    const char *prompt = NULL;
    if (!dbus_message_get_args(reply, &error, DBUS_TYPE_OBJECT_PATH,
            &prompt, DBUS_TYPE_INVALID) || strcmp(prompt, "/") != 0)
        fail("Delete item", "prompt");
    dbus_message_unref(reply);
}

static void unsupported_session(DBusConnection *connection) {
    DBusMessage *request = dbus_message_new_method_call(
            NAME, SERVICE, SERVICE_IFACE, "OpenSession");
    DBusMessageIter output, variant;
    const char *algorithm = "dh-ietf1024-sha256-aes128-cbc-pkcs7";
    const char *empty = "";
    dbus_message_iter_init_append(request, &output);
    dbus_message_iter_append_basic(&output, DBUS_TYPE_STRING, &algorithm);
    dbus_message_iter_open_container(&output, DBUS_TYPE_VARIANT, "s", &variant);
    dbus_message_iter_append_basic(&variant, DBUS_TYPE_STRING, &empty);
    dbus_message_iter_close_container(&output, &variant);
    expect_error(connection, request, DBUS_ERROR_INVALID_ARGS,
            "malformed encrypted session");
}

int main(void) {
    DBusError error = DBUS_ERROR_INIT;
    DBusConnection *connection = dbus_bus_get_private(DBUS_BUS_SESSION, &error);
    if (connection == NULL) fail("connect", error.message == NULL ? "session bus" : error.message);
    dbus_connection_set_exit_on_disconnect(connection, FALSE);

    unsupported_session(connection);
    char *session = open_session(connection);
    const char *initial = "archphene-dbus-secret-184052";
    const char *updated = "archphene-dbus-updated-953104";
    char *item = create_item(connection, session, initial, FALSE);
    char *search_path = NULL;
    if (search(connection, &search_path) != 1 || search_path == NULL
            || strcmp(search_path, item) != 0) fail("SearchItems", "created item missing");
    free(search_path);
    get_secret(connection, item, session, initial);
    set_secret(connection, item, session, updated);
    get_secret(connection, item, session, updated);
    char *replaced = create_item(connection, session, initial, TRUE);
    if (strcmp(replaced, item) != 0 || search(connection, NULL) != 1)
        fail("replace", "replace did not preserve identity");
    free(replaced);
    get_secret(connection, item, session, initial);
    char *empty_item = create_item(connection, session, "", FALSE);
    get_secret(connection, empty_item, session, "");
    delete_item(connection, empty_item);
    free(empty_item);
    close_session(connection, session);

    DBusMessage *stale = dbus_message_new_method_call(NAME, item, ITEM_IFACE, "GetSecret");
    dbus_message_append_args(stale, DBUS_TYPE_OBJECT_PATH, &session, DBUS_TYPE_INVALID);
    expect_error(connection, stale, "org.freedesktop.Secret.Error.NoSession", "closed session");

    char *delete_session_path = open_session(connection);
    delete_item(connection, item);
    if (search(connection, NULL) != 0) fail("Delete item", "item still searchable");
    close_session(connection, delete_session_path);
    free(delete_session_path);
    free(item);
    free(session);
    dbus_connection_close(connection);
    dbus_connection_unref(connection);
    puts("PASS Secret Service: sessions, create, search, get, set, replace, empty, delete");
    return 0;
}
