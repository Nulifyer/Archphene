#include <dbus/dbus.h>

#include "archphene_android.h"
#include "archphene_atspi_bridge.h"
#include "archphene_secret_service.h"

#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define PORTAL_PATH "/org/freedesktop/portal/desktop"
#define NOTIFICATIONS_PATH "/org/freedesktop/Notifications"
#define PORTAL_NAME "org.freedesktop.portal.Desktop"
#define PORTAL_OPEN_URI "org.freedesktop.portal.OpenURI"
#define PORTAL_FILE_CHOOSER "org.freedesktop.portal.FileChooser"
#define PORTAL_SETTINGS "org.freedesktop.portal.Settings"
#define PORTAL_NOTIFICATION "org.freedesktop.portal.Notification"
#define PORTAL_PRINT "org.freedesktop.portal.Print"
#define PORTAL_CAMERA "org.freedesktop.portal.Camera"
#define CLASSIC_NOTIFICATION "org.freedesktop.Notifications"
#define PROPERTIES "org.freedesktop.DBus.Properties"
#define INTROSPECTABLE "org.freedesktop.DBus.Introspectable"
#define MAX_TEXT 8192
#define MAX_ID 96
#define MAX_OPEN_RESPONSE (192 * 1024)
#define MAX_MIME_SPEC 2048
#define MAX_MIME_TYPE 127
#define MAX_MIME_TYPES 16
#define APPEARANCE_STATE_BYTES 11

static volatile sig_atomic_t running = 1;
static uint32_t next_id = 1;

struct appearance_state {
    uint32_t color_scheme;
    unsigned char accent[3];
    dbus_bool_t accent_available;
};

static struct appearance_state appearance = {0, {0, 0, 0}, FALSE};

static const char portal_xml[] =
        "<node>"
        "<interface name='org.freedesktop.portal.OpenURI'>"
        "<method name='OpenURI'><arg type='s' direction='in'/><arg type='s' direction='in'/>"
        "<arg type='a{sv}' direction='in'/><arg type='o' direction='out'/></method>"
        "<method name='SchemeSupported'><arg type='s' direction='in'/>"
        "<arg type='a{sv}' direction='in'/><arg type='b' direction='out'/></method>"
        "<property name='version' type='u' access='read'/></interface>"
        "<interface name='org.freedesktop.portal.FileChooser'>"
        "<method name='OpenFile'><arg type='s' direction='in'/>"
        "<arg type='s' direction='in'/><arg type='a{sv}' direction='in'/>"
        "<arg type='o' direction='out'/></method>"
        "<method name='SaveFile'><arg type='s' direction='in'/>"
        "<arg type='s' direction='in'/><arg type='a{sv}' direction='in'/>"
        "<arg type='o' direction='out'/></method>"
        "<property name='version' type='u' access='read'/></interface>"
        "<interface name='org.freedesktop.portal.Settings'>"
        "<method name='ReadAll'><arg type='as' direction='in'/>"
        "<arg type='a{sa{sv}}' direction='out'/></method>"
        "<method name='Read'><arg type='s' direction='in'/>"
        "<arg type='s' direction='in'/><arg type='v' direction='out'/></method>"
        "<method name='ReadOne'><arg type='s' direction='in'/>"
        "<arg type='s' direction='in'/><arg type='v' direction='out'/></method>"
        "<signal name='SettingChanged'><arg type='s'/><arg type='s'/>"
        "<arg type='v'/></signal>"
        "<property name='version' type='u' access='read'/></interface>"
        "<interface name='org.freedesktop.portal.Notification'>"
        "<method name='AddNotification'><arg type='s' direction='in'/>"
        "<arg type='a{sv}' direction='in'/></method>"
        "<method name='RemoveNotification'><arg type='s' direction='in'/></method>"
        "<property name='SupportedOptions' type='a{sv}' access='read'/>"
        "<property name='version' type='u' access='read'/></interface>"
        "<interface name='org.freedesktop.portal.Print'>"
        "<method name='PreparePrint'><arg type='s' direction='in'/>"
        "<arg type='s' direction='in'/><arg type='a{sv}' direction='in'/>"
        "<arg type='a{sv}' direction='in'/><arg type='a{sv}' direction='in'/>"
        "<arg type='o' direction='out'/></method>"
        "<method name='Print'><arg type='s' direction='in'/>"
        "<arg type='s' direction='in'/><arg type='h' direction='in'/>"
        "<arg type='a{sv}' direction='in'/><arg type='o' direction='out'/></method>"
        "<property name='version' type='u' access='read'/></interface>"
        "<interface name='org.freedesktop.portal.Camera'>"
        "<method name='AccessCamera'><arg type='a{sv}' direction='in'/>"
        "<arg type='o' direction='out'/></method>"
        "<method name='OpenPipeWireRemote'><arg type='a{sv}' direction='in'/>"
        "<arg type='h' direction='out'/></method>"
        "<property name='IsCameraPresent' type='b' access='read'/>"
        "<property name='version' type='u' access='read'/></interface>"
        "<interface name='org.freedesktop.DBus.Properties'>"
        "<method name='Get'><arg type='s' direction='in'/><arg type='s' direction='in'/>"
        "<arg type='v' direction='out'/></method>"
        "<method name='GetAll'><arg type='s' direction='in'/><arg type='a{sv}' direction='out'/>"
        "</method></interface>"
        "<interface name='org.freedesktop.DBus.Introspectable'>"
        "<method name='Introspect'><arg type='s' direction='out'/></method></interface>"
        "</node>";

static const char notifications_xml[] =
        "<node><interface name='org.freedesktop.Notifications'>"
        "<method name='GetCapabilities'><arg type='as' direction='out'/></method>"
        "<method name='GetServerInformation'><arg type='s' direction='out'/>"
        "<arg type='s' direction='out'/><arg type='s' direction='out'/>"
        "<arg type='s' direction='out'/></method>"
        "<method name='Notify'><arg type='s' direction='in'/><arg type='u' direction='in'/>"
        "<arg type='s' direction='in'/><arg type='s' direction='in'/>"
        "<arg type='s' direction='in'/><arg type='as' direction='in'/>"
        "<arg type='a{sv}' direction='in'/><arg type='i' direction='in'/>"
        "<arg type='u' direction='out'/></method>"
        "<method name='CloseNotification'><arg type='u' direction='in'/></method>"
        "</interface><interface name='org.freedesktop.DBus.Introspectable'>"
        "<method name='Introspect'><arg type='s' direction='out'/></method></interface></node>";

static void stop_running(int signal_number) {
    (void)signal_number;
    running = 0;
}

static dbus_bool_t send_message(DBusConnection *connection, DBusMessage *message) {
    if (message == NULL) return FALSE;
    dbus_bool_t sent = dbus_connection_send(connection, message, NULL);
    dbus_message_unref(message);
    dbus_connection_flush(connection);
    return sent;
}

static void send_error(DBusConnection *connection, DBusMessage *request,
        const char *name, const char *detail) {
    /*
     * D-Bus method calls may explicitly suppress replies. Sending even an
     * error for such a call is unsolicited and the bus correctly rejects it
     * as requested_reply=0.
     */
    if (dbus_message_get_no_reply(request)) return;
    send_message(connection, dbus_message_new_error(request, name, detail));
}

static void send_empty_reply(DBusConnection *connection, DBusMessage *request) {
    send_message(connection, dbus_message_new_method_return(request));
}

static dbus_bool_t append_empty_dict(DBusMessageIter *parent) {
    DBusMessageIter dict;
    return dbus_message_iter_open_container(parent, DBUS_TYPE_ARRAY, "{sv}", &dict)
            && dbus_message_iter_close_container(parent, &dict);
}

static dbus_bool_t append_version_property(DBusMessageIter *dict, uint32_t version) {
    const char *key = "version";
    DBusMessageIter entry;
    DBusMessageIter variant;
    return dbus_message_iter_open_container(dict, DBUS_TYPE_DICT_ENTRY, NULL, &entry)
            && dbus_message_iter_append_basic(&entry, DBUS_TYPE_STRING, &key)
            && dbus_message_iter_open_container(&entry, DBUS_TYPE_VARIANT, "u", &variant)
            && dbus_message_iter_append_basic(&variant, DBUS_TYPE_UINT32, &version)
            && dbus_message_iter_close_container(&entry, &variant)
            && dbus_message_iter_close_container(dict, &entry);
}

static dbus_bool_t append_supported_options_property(DBusMessageIter *dict) {
    const char *key = "SupportedOptions";
    DBusMessageIter entry;
    DBusMessageIter variant;
    DBusMessageIter options;
    return dbus_message_iter_open_container(dict, DBUS_TYPE_DICT_ENTRY, NULL, &entry)
            && dbus_message_iter_append_basic(&entry, DBUS_TYPE_STRING, &key)
            && dbus_message_iter_open_container(&entry, DBUS_TYPE_VARIANT, "a{sv}", &variant)
            && dbus_message_iter_open_container(&variant, DBUS_TYPE_ARRAY, "{sv}", &options)
            && dbus_message_iter_close_container(&variant, &options)
            && dbus_message_iter_close_container(&entry, &variant)
            && dbus_message_iter_close_container(dict, &entry);
}

static int copy_basic_string(DBusMessageIter *iter, char *output, size_t size) {
    if (dbus_message_iter_get_arg_type(iter) != DBUS_TYPE_STRING || size < 2) return -1;
    const char *value = NULL;
    dbus_message_iter_get_basic(iter, &value);
    if (value == NULL || strlen(value) >= size) return -1;
    memcpy(output, value, strlen(value) + 1);
    return 0;
}

static void read_vardict_strings(DBusMessageIter *array, char *title, size_t title_size,
        char *body, size_t body_size, char *token, size_t token_size) {
    if (dbus_message_iter_get_arg_type(array) != DBUS_TYPE_ARRAY) return;
    DBusMessageIter entries;
    dbus_message_iter_recurse(array, &entries);
    while (dbus_message_iter_get_arg_type(&entries) == DBUS_TYPE_DICT_ENTRY) {
        DBusMessageIter entry;
        dbus_message_iter_recurse(&entries, &entry);
        if (dbus_message_iter_get_arg_type(&entry) == DBUS_TYPE_STRING) {
            const char *key = NULL;
            dbus_message_iter_get_basic(&entry, &key);
            if (dbus_message_iter_next(&entry)
                    && dbus_message_iter_get_arg_type(&entry) == DBUS_TYPE_VARIANT) {
                DBusMessageIter value;
                dbus_message_iter_recurse(&entry, &value);
                if (dbus_message_iter_get_arg_type(&value) == DBUS_TYPE_STRING) {
                    if (title != NULL && strcmp(key, "title") == 0)
                        (void)copy_basic_string(&value, title, title_size);
                    else if (body != NULL && strcmp(key, "body") == 0)
                        (void)copy_basic_string(&value, body, body_size);
                    else if (token != NULL && strcmp(key, "handle_token") == 0)
                        (void)copy_basic_string(&value, token, token_size);
                }
            }
        }
        dbus_message_iter_next(&entries);
    }
}

static dbus_bool_t safe_mime_type(const char *value) {
    if (value == NULL) return FALSE;
    size_t length = strlen(value);
    if (length < 3 || length > MAX_MIME_TYPE) return FALSE;
    const char *separator = strchr(value, '/');
    if (separator == NULL || separator == value || separator[1] == '\0'
            || strchr(separator + 1, '/') != NULL) return FALSE;
    for (const unsigned char *cursor = (const unsigned char *)value;
            *cursor != '\0'; cursor++) {
        if (!((*cursor >= 'a' && *cursor <= 'z')
                || (*cursor >= 'A' && *cursor <= 'Z')
                || (*cursor >= '0' && *cursor <= '9')
                || *cursor == '!' || *cursor == '#' || *cursor == '$'
                || *cursor == '&' || *cursor == '^' || *cursor == '_'
                || *cursor == '.' || *cursor == '+' || *cursor == '-'
                || *cursor == '*' || *cursor == '/')) return FALSE;
    }
    size_t top_length = (size_t)(separator - value);
    if (memchr(value, '*', top_length) != NULL
            && !(top_length == 1 && value[0] == '*')) return FALSE;
    const char *subtype = separator + 1;
    if (strchr(subtype, '*') != NULL && strcmp(subtype, "*") != 0) return FALSE;
    return value[0] != '*' || strcmp(value, "*/*") == 0;
}

static dbus_bool_t mime_spec_contains(const char *spec, const char *mime) {
    size_t mime_length = strlen(mime);
    const char *cursor = spec;
    while (*cursor != '\0') {
        const char *end = strchr(cursor, ';');
        size_t length = end == NULL ? strlen(cursor) : (size_t)(end - cursor);
        if (length == mime_length && memcmp(cursor, mime, length) == 0) return TRUE;
        if (end == NULL) break;
        cursor = end + 1;
    }
    return FALSE;
}

static void append_filter_mime(
        char *spec, size_t spec_size, size_t *count, const char *value) {
    if (*count >= MAX_MIME_TYPES || !safe_mime_type(value)) return;
    char canonical[MAX_MIME_TYPE + 1];
    size_t length = strlen(value);
    for (size_t index = 0; index < length; index++) {
        unsigned char character = (unsigned char)value[index];
        canonical[index] = character >= 'A' && character <= 'Z'
                ? (char)(character + ('a' - 'A')) : (char)character;
    }
    canonical[length] = '\0';
    if (mime_spec_contains(spec, canonical)) return;
    size_t used = strlen(spec);
    size_t required = used + (used == 0 ? 0 : 1) + length + 1;
    if (required > spec_size) return;
    if (used != 0) spec[used++] = ';';
    memcpy(spec + used, canonical, length + 1);
    (*count)++;
}

static void read_filter_mimes(
        DBusMessageIter *filter, char *spec, size_t spec_size) {
    if (dbus_message_iter_get_arg_type(filter) != DBUS_TYPE_STRUCT) return;
    DBusMessageIter fields;
    dbus_message_iter_recurse(filter, &fields);
    if (dbus_message_iter_get_arg_type(&fields) != DBUS_TYPE_STRING
            || !dbus_message_iter_next(&fields)
            || dbus_message_iter_get_arg_type(&fields) != DBUS_TYPE_ARRAY) return;
    DBusMessageIter rules;
    dbus_message_iter_recurse(&fields, &rules);
    size_t count = 0;
    while (dbus_message_iter_get_arg_type(&rules) == DBUS_TYPE_STRUCT) {
        DBusMessageIter rule;
        dbus_message_iter_recurse(&rules, &rule);
        uint32_t type = UINT32_MAX;
        if (dbus_message_iter_get_arg_type(&rule) == DBUS_TYPE_UINT32) {
            dbus_message_iter_get_basic(&rule, &type);
            if (dbus_message_iter_next(&rule)
                    && dbus_message_iter_get_arg_type(&rule) == DBUS_TYPE_STRING
                    && type == 1) {
                const char *value = NULL;
                dbus_message_iter_get_basic(&rule, &value);
                append_filter_mime(spec, spec_size, &count, value);
            }
        }
        dbus_message_iter_next(&rules);
    }
}

static void read_file_options(
        DBusMessageIter *array,
        dbus_bool_t *multiple,
        dbus_bool_t *directory,
        char *name,
        size_t name_size,
        char *mime_spec,
        size_t mime_spec_size) {
    if (multiple != NULL) *multiple = FALSE;
    if (directory != NULL) *directory = FALSE;
    if (mime_spec != NULL && mime_spec_size > 0) mime_spec[0] = '\0';
    if (dbus_message_iter_get_arg_type(array) != DBUS_TYPE_ARRAY) return;
    char first_filter[MAX_MIME_SPEC + 1] = {0};
    char current_filter[MAX_MIME_SPEC + 1] = {0};
    DBusMessageIter entries;
    dbus_message_iter_recurse(array, &entries);
    while (dbus_message_iter_get_arg_type(&entries) == DBUS_TYPE_DICT_ENTRY) {
        DBusMessageIter entry;
        dbus_message_iter_recurse(&entries, &entry);
        if (dbus_message_iter_get_arg_type(&entry) == DBUS_TYPE_STRING) {
            const char *key = NULL;
            dbus_message_iter_get_basic(&entry, &key);
            if (dbus_message_iter_next(&entry)
                    && dbus_message_iter_get_arg_type(&entry) == DBUS_TYPE_VARIANT) {
                DBusMessageIter value;
                dbus_message_iter_recurse(&entry, &value);
                if (name != NULL && strcmp(key, "current_name") == 0) {
                    (void)copy_basic_string(&value, name, name_size);
                } else if ((multiple != NULL || directory != NULL)
                        && dbus_message_iter_get_arg_type(&value)
                                == DBUS_TYPE_BOOLEAN) {
                    dbus_bool_t enabled = FALSE;
                    dbus_message_iter_get_basic(&value, &enabled);
                    if (multiple != NULL && strcmp(key, "multiple") == 0)
                        *multiple = enabled;
                    else if (directory != NULL && strcmp(key, "directory") == 0)
                        *directory = enabled;
                } else if (mime_spec != NULL
                        && strcmp(key, "current_filter") == 0) {
                    read_filter_mimes(
                            &value, current_filter, sizeof(current_filter));
                } else if (mime_spec != NULL && strcmp(key, "filters") == 0
                        && dbus_message_iter_get_arg_type(&value)
                                == DBUS_TYPE_ARRAY) {
                    DBusMessageIter filters;
                    dbus_message_iter_recurse(&value, &filters);
                    read_filter_mimes(
                            &filters, first_filter, sizeof(first_filter));
                }
            }
        }
        dbus_message_iter_next(&entries);
    }
    if (mime_spec != NULL && mime_spec_size > 0) {
        const char *selected = current_filter[0] != '\0'
                ? current_filter
                : (first_filter[0] != '\0' ? first_filter : "*/*");
        snprintf(mime_spec, mime_spec_size, "%s", selected);
    }
}

static void read_save_options(
        DBusMessageIter *array, char *name, size_t name_size,
        char *mime_spec, size_t mime_spec_size) {
    read_file_options(
            array, NULL, NULL, name, name_size, mime_spec, mime_spec_size);
}

static void read_open_options(
        DBusMessageIter *array,
        dbus_bool_t *multiple,
        dbus_bool_t *directory,
        char *mime_spec,
        size_t mime_spec_size) {
    read_file_options(
            array, multiple, directory, NULL, 0, mime_spec, mime_spec_size);
}

static dbus_bool_t valid_path_element(const char *value) {
    if (value == NULL || value[0] == '\0') return FALSE;
    for (const unsigned char *p = (const unsigned char *)value; *p != '\0'; p++) {
        if (!((*p >= 'a' && *p <= 'z') || (*p >= 'A' && *p <= 'Z')
                || (*p >= '0' && *p <= '9') || *p == '_')) return FALSE;
    }
    return TRUE;
}

static void sender_element(const char *sender, char *output, size_t size) {
    size_t target = 0;
    if (sender != NULL && sender[0] == ':') sender++;
    while (sender != NULL && *sender != '\0' && target + 1 < size) {
        unsigned char c = (unsigned char)*sender++;
        output[target++] = ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_') ? (char)c : '_';
    }
    output[target] = '\0';
}

static dbus_bool_t broker_accepted(int result, const char *response) {
    return result == 0 || (result == 1 && response != NULL
            && strcmp(response, "ERROR\tPERMISSION_REQUESTED") == 0);
}

static dbus_bool_t camera_enabled(void) {
    const char *value = getenv("ARCHPHENE_ENABLE_CAMERA");
    return value != NULL && strcmp(value, "1") == 0;
}

static dbus_bool_t camera_present(void) {
    if (!camera_enabled()) return FALSE;
    char response[256] = {0};
    int result = archphene_android_check_camera(response, sizeof(response));
    return result == 0 || strcmp(response, "ERROR\tPERMISSION_NOT_REQUESTED") == 0
            || strcmp(response, "ERROR\tPERMISSION_REQUESTED") == 0
            || strcmp(response, "ERROR\tPERMISSION_DENIED") == 0;
}

static int connect_pipewire_remote(void) {
    const char *path = getenv("ARCHPHENE_PIPEWIRE_SOCKET");
    if (path == NULL || path[0] == '\0') {
        errno = ENOENT;
        return -1;
    }
    size_t length = strlen(path);
    dbus_bool_t abstract = path[0] == '@';
    const char *name = abstract ? path + 1 : path;
    size_t name_length = abstract ? length - 1 : length;
    if (name_length == 0 || name_length >= sizeof(((struct sockaddr_un *)0)->sun_path)) {
        errno = ENAMETOOLONG;
        return -1;
    }
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    struct sockaddr_un address = {0};
    address.sun_family = AF_UNIX;
    memcpy(address.sun_path + (abstract ? 1 : 0), name, name_length);
    socklen_t address_length = (socklen_t)(offsetof(struct sockaddr_un, sun_path)
            + name_length + (abstract ? 1 : 0));
    if (connect(fd, (struct sockaddr *)&address, address_length) != 0) {
        int saved = errno;
        close(fd);
        errno = saved;
        return -1;
    }
    return fd;
}

static void emit_portal_response(DBusConnection *connection, const char *path,
        const char *destination, uint32_t response) {
    DBusMessage *signal = dbus_message_new_signal(
            path, "org.freedesktop.portal.Request", "Response");
    if (signal == NULL) return;
    if (!dbus_message_set_destination(signal, destination)) {
        dbus_message_unref(signal);
        return;
    }
    DBusMessageIter args;
    dbus_message_iter_init_append(signal, &args);
    if (!dbus_message_iter_append_basic(&args, DBUS_TYPE_UINT32, &response)
            || !append_empty_dict(&args)) {
        dbus_message_unref(signal);
        return;
    }
    send_message(connection, signal);
}

static dbus_bool_t append_uri_result(
        DBusMessageIter *dict, const char *uris, size_t uri_stride,
        size_t uri_count) {
    const char *key = "uris";
    DBusMessageIter entry;
    DBusMessageIter variant;
    DBusMessageIter values;
    dbus_bool_t ok =
            dbus_message_iter_open_container(dict, DBUS_TYPE_DICT_ENTRY, NULL, &entry)
            && dbus_message_iter_append_basic(&entry, DBUS_TYPE_STRING, &key)
            && dbus_message_iter_open_container(&entry, DBUS_TYPE_VARIANT, "as", &variant)
            && dbus_message_iter_open_container(&variant, DBUS_TYPE_ARRAY, "s", &values);
    for (size_t index = 0; ok && index < uri_count; index++) {
        const char *uri = uris + index * uri_stride;
        ok = dbus_message_iter_append_basic(&values, DBUS_TYPE_STRING, &uri);
    }
    return ok
            && dbus_message_iter_close_container(&variant, &values)
            && dbus_message_iter_close_container(&entry, &variant)
            && dbus_message_iter_close_container(dict, &entry);
}

static dbus_bool_t emit_file_chooser_response(
        DBusConnection *connection, const char *path, const char *destination,
        uint32_t response, const char *uris, size_t uri_stride,
        size_t uri_count) {
    DBusMessage *signal = dbus_message_new_signal(
            path, "org.freedesktop.portal.Request", "Response");
    if (signal == NULL) return FALSE;
    if (!dbus_message_set_destination(signal, destination)) {
        dbus_message_unref(signal);
        return FALSE;
    }
    DBusMessageIter args;
    DBusMessageIter dict;
    dbus_message_iter_init_append(signal, &args);
    dbus_bool_t ok = dbus_message_iter_append_basic(
            &args, DBUS_TYPE_UINT32, &response)
            && dbus_message_iter_open_container(&args, DBUS_TYPE_ARRAY, "{sv}", &dict)
            /*
             * GTK 3.24 reads the "uris" member even for cancelled portal
             * responses. Include a well-typed empty array instead of omitting
             * it so legacy clients do not dereference an unset result.
             */
            && append_uri_result(
                    &dict, response == 0 ? uris : NULL,
                    uri_stride, response == 0 ? uri_count : 0)
            && dbus_message_iter_close_container(&args, &dict);
    if (!ok) {
        dbus_message_unref(signal);
        return FALSE;
    }
    return send_message(connection, signal);
}

static dbus_bool_t append_named_empty_dict(DBusMessageIter *dict, const char *key) {
    DBusMessageIter entry;
    DBusMessageIter variant;
    DBusMessageIter values;
    return dbus_message_iter_open_container(dict, DBUS_TYPE_DICT_ENTRY, NULL, &entry)
            && dbus_message_iter_append_basic(&entry, DBUS_TYPE_STRING, &key)
            && dbus_message_iter_open_container(&entry, DBUS_TYPE_VARIANT, "a{sv}", &variant)
            && dbus_message_iter_open_container(&variant, DBUS_TYPE_ARRAY, "{sv}", &values)
            && dbus_message_iter_close_container(&variant, &values)
            && dbus_message_iter_close_container(&entry, &variant)
            && dbus_message_iter_close_container(dict, &entry);
}

static dbus_bool_t append_named_bool(
        DBusMessageIter *dict, const char *key, dbus_bool_t value) {
    DBusMessageIter entry;
    DBusMessageIter variant;
    return dbus_message_iter_open_container(dict, DBUS_TYPE_DICT_ENTRY, NULL, &entry)
            && dbus_message_iter_append_basic(&entry, DBUS_TYPE_STRING, &key)
            && dbus_message_iter_open_container(&entry, DBUS_TYPE_VARIANT, "b", &variant)
            && dbus_message_iter_append_basic(&variant, DBUS_TYPE_BOOLEAN, &value)
            && dbus_message_iter_close_container(&entry, &variant)
            && dbus_message_iter_close_container(dict, &entry);
}

static dbus_bool_t append_named_uint(
        DBusMessageIter *dict, const char *key, uint32_t value) {
    DBusMessageIter entry;
    DBusMessageIter variant;
    return dbus_message_iter_open_container(dict, DBUS_TYPE_DICT_ENTRY, NULL, &entry)
            && dbus_message_iter_append_basic(&entry, DBUS_TYPE_STRING, &key)
            && dbus_message_iter_open_container(&entry, DBUS_TYPE_VARIANT, "u", &variant)
            && dbus_message_iter_append_basic(&variant, DBUS_TYPE_UINT32, &value)
            && dbus_message_iter_close_container(&entry, &variant)
            && dbus_message_iter_close_container(dict, &entry);
}

static dbus_bool_t decode_hex_pair(
        const unsigned char *value, unsigned char *decoded) {
    unsigned int component = 0;
    for (size_t digit = 0; digit < 2; digit++) {
        unsigned char character = value[digit];
        unsigned int nibble;
        if (character >= '0' && character <= '9') nibble = character - '0';
        else if (character >= 'a' && character <= 'f') nibble = character - 'a' + 10;
        else if (character >= 'A' && character <= 'F') nibble = character - 'A' + 10;
        else return FALSE;
        component = component * 16 + nibble;
    }
    *decoded = (unsigned char)component;
    return TRUE;
}

static void load_environment_appearance(void) {
    const char *scheme = getenv("ARCHPHENE_COLOR_SCHEME");
    if (scheme != NULL && strcmp(scheme, "dark") == 0) appearance.color_scheme = 1;
    else if (scheme != NULL && strcmp(scheme, "light") == 0) appearance.color_scheme = 2;
    else appearance.color_scheme = 0;
    const char *value = getenv("ARCHPHENE_ACCENT_RGB");
    appearance.accent_available = value != NULL && strlen(value) == 6;
    for (size_t index = 0; index < 3; index++) {
        if (appearance.accent_available
                && !decode_hex_pair((const unsigned char *)value + index * 2,
                    &appearance.accent[index])) {
            appearance.accent_available = FALSE;
        }
    }
}

static dbus_bool_t read_appearance_state(struct appearance_state *state) {
    const char *path = getenv("ARCHPHENE_APPEARANCE_STATE");
    if (path == NULL || path[0] != '/' || strlen(path) > 4096) return FALSE;
    int descriptor = open(
            path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
    if (descriptor < 0) return FALSE;
    struct stat metadata;
    if (fstat(descriptor, &metadata) != 0
            || !S_ISREG(metadata.st_mode)
            || metadata.st_size != APPEARANCE_STATE_BYTES) {
        close(descriptor);
        return FALSE;
    }
    unsigned char bytes[APPEARANCE_STATE_BYTES + 1];
    size_t received = 0;
    while (received < sizeof(bytes)) {
        ssize_t count = read(descriptor, bytes + received, sizeof(bytes) - received);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) break;
        received += (size_t)count;
    }
    close(descriptor);
    if (received != APPEARANCE_STATE_BYTES
            || bytes[0] != '1' || bytes[1] != '\n'
            || (bytes[2] != '1' && bytes[2] != '2')
            || bytes[3] != '\n' || bytes[10] != '\n') return FALSE;
    struct appearance_state parsed = {
        .color_scheme = bytes[2] == '1' ? 1U : 2U,
        .accent = {0, 0, 0},
        .accent_available = TRUE,
    };
    for (size_t index = 0; index < 3; index++) {
        if (!decode_hex_pair(bytes + 4 + index * 2, &parsed.accent[index]))
            return FALSE;
    }
    *state = parsed;
    return TRUE;
}

static uint32_t appearance_color_scheme(void) {
    return appearance.color_scheme;
}

static dbus_bool_t appearance_accent(double *red, double *green, double *blue) {
    if (!appearance.accent_available) return FALSE;
    *red = appearance.accent[0] / 255.0;
    *green = appearance.accent[1] / 255.0;
    *blue = appearance.accent[2] / 255.0;
    return TRUE;
}

static dbus_bool_t appearance_accent_available(void) {
    double red;
    double green;
    double blue;
    return appearance_accent(&red, &green, &blue);
}

static dbus_bool_t append_appearance_value(
        DBusMessageIter *parent, const char *key);

static dbus_bool_t send_setting_changed(
        DBusConnection *connection, const char *key) {
    const char *name = "org.freedesktop.appearance";
    DBusMessage *signal = dbus_message_new_signal(
            PORTAL_PATH, PORTAL_SETTINGS, "SettingChanged");
    DBusMessageIter arguments;
    if (signal == NULL) return FALSE;
    dbus_message_iter_init_append(signal, &arguments);
    if (!dbus_message_iter_append_basic(
                &arguments, DBUS_TYPE_STRING, &name)
            || !dbus_message_iter_append_basic(
                &arguments, DBUS_TYPE_STRING, &key)
            || !append_appearance_value(&arguments, key)) {
        dbus_message_unref(signal);
        return FALSE;
    }
    return send_message(connection, signal);
}

static void poll_appearance_state(DBusConnection *connection) {
    struct appearance_state updated;
    if (!read_appearance_state(&updated)) return;
    dbus_bool_t scheme_changed =
            updated.color_scheme != appearance.color_scheme;
    dbus_bool_t accent_changed =
            updated.accent_available != appearance.accent_available
            || memcmp(updated.accent, appearance.accent,
                sizeof(updated.accent)) != 0;
    if (!scheme_changed && !accent_changed) return;
    appearance = updated;
    if (scheme_changed
            && !send_setting_changed(connection, "color-scheme"))
        fprintf(stderr, "Archphene portal could not emit color-scheme change\n");
    if (accent_changed
            && !send_setting_changed(connection, "accent-color"))
        fprintf(stderr, "Archphene portal could not emit accent-color change\n");
}

static dbus_bool_t known_appearance_key(const char *key) {
    return key != NULL && (strcmp(key, "color-scheme") == 0
            || strcmp(key, "accent-color") == 0
            || strcmp(key, "contrast") == 0
            || strcmp(key, "reduced-motion") == 0);
}

static dbus_bool_t append_appearance_value(
        DBusMessageIter *parent, const char *key) {
    DBusMessageIter variant;
    if (strcmp(key, "accent-color") == 0) {
        double red;
        double green;
        double blue;
        if (!appearance_accent(&red, &green, &blue)
                || !dbus_message_iter_open_container(
                        parent, DBUS_TYPE_VARIANT, "(ddd)", &variant)) {
            return FALSE;
        }
        DBusMessageIter tuple;
        dbus_bool_t ok = dbus_message_iter_open_container(
                    &variant, DBUS_TYPE_STRUCT, NULL, &tuple)
                && dbus_message_iter_append_basic(&tuple, DBUS_TYPE_DOUBLE, &red)
                && dbus_message_iter_append_basic(&tuple, DBUS_TYPE_DOUBLE, &green)
                && dbus_message_iter_append_basic(&tuple, DBUS_TYPE_DOUBLE, &blue)
                && dbus_message_iter_close_container(&variant, &tuple)
                && dbus_message_iter_close_container(parent, &variant);
        return ok;
    }
    uint32_t value = strcmp(key, "color-scheme") == 0
            ? appearance_color_scheme() : 0;
    return dbus_message_iter_open_container(
                parent, DBUS_TYPE_VARIANT, "u", &variant)
            && dbus_message_iter_append_basic(&variant, DBUS_TYPE_UINT32, &value)
            && dbus_message_iter_close_container(parent, &variant);
}

static dbus_bool_t append_appearance_entry(
        DBusMessageIter *dict, const char *key) {
    DBusMessageIter entry;
    return dbus_message_iter_open_container(
                dict, DBUS_TYPE_DICT_ENTRY, NULL, &entry)
            && dbus_message_iter_append_basic(&entry, DBUS_TYPE_STRING, &key)
            && append_appearance_value(&entry, key)
            && dbus_message_iter_close_container(dict, &entry);
}

static dbus_bool_t requested_appearance_namespace(
        DBusMessageIter *arguments, dbus_bool_t *valid) {
    *valid = FALSE;
    if (dbus_message_iter_get_arg_type(arguments) != DBUS_TYPE_ARRAY) return FALSE;
    DBusMessageIter namespaces;
    dbus_message_iter_recurse(arguments, &namespaces);
    dbus_bool_t requested =
            dbus_message_iter_get_arg_type(&namespaces) == DBUS_TYPE_INVALID;
    while (dbus_message_iter_get_arg_type(&namespaces) != DBUS_TYPE_INVALID) {
        if (dbus_message_iter_get_arg_type(&namespaces) != DBUS_TYPE_STRING) return FALSE;
        const char *value = NULL;
        dbus_message_iter_get_basic(&namespaces, &value);
        if (value == NULL || strlen(value) > 127) return FALSE;
        size_t length = strlen(value);
        if (length == 0 || strcmp(value, "org.freedesktop.appearance") == 0
                || (value[length - 1] == '*'
                    && strncmp("org.freedesktop.appearance", value, length - 1) == 0)) {
            requested = TRUE;
        }
        dbus_message_iter_next(&namespaces);
    }
    *valid = TRUE;
    return requested;
}

static void handle_settings_read_all(
        DBusConnection *connection, DBusMessage *request) {
    DBusMessageIter arguments;
    dbus_bool_t valid = FALSE;
    if (!dbus_message_iter_init(request, &arguments)) {
        send_error(connection, request, DBUS_ERROR_INVALID_ARGS, "Expected namespace array");
        return;
    }
    dbus_bool_t include_appearance =
            requested_appearance_namespace(&arguments, &valid);
    if (!valid || dbus_message_iter_next(&arguments)) {
        send_error(connection, request, DBUS_ERROR_INVALID_ARGS, "Expected namespace array");
        return;
    }
    DBusMessage *reply = dbus_message_new_method_return(request);
    DBusMessageIter output;
    DBusMessageIter namespaces;
    if (reply == NULL) return;
    dbus_message_iter_init_append(reply, &output);
    dbus_bool_t ok = dbus_message_iter_open_container(
            &output, DBUS_TYPE_ARRAY, "{sa{sv}}", &namespaces);
    if (ok && include_appearance) {
        const char *name = "org.freedesktop.appearance";
        DBusMessageIter namespace_entry;
        DBusMessageIter settings;
        ok = dbus_message_iter_open_container(
                    &namespaces, DBUS_TYPE_DICT_ENTRY, NULL, &namespace_entry)
                && dbus_message_iter_append_basic(
                    &namespace_entry, DBUS_TYPE_STRING, &name)
                && dbus_message_iter_open_container(
                    &namespace_entry, DBUS_TYPE_ARRAY, "{sv}", &settings)
                && append_appearance_entry(&settings, "color-scheme")
                && (!appearance_accent_available()
                    || append_appearance_entry(&settings, "accent-color"))
                && append_appearance_entry(&settings, "contrast")
                && append_appearance_entry(&settings, "reduced-motion")
                && dbus_message_iter_close_container(&namespace_entry, &settings)
                && dbus_message_iter_close_container(&namespaces, &namespace_entry);
    }
    ok = ok && dbus_message_iter_close_container(&output, &namespaces);
    if (!ok) {
        dbus_message_unref(reply);
        send_error(connection, request, DBUS_ERROR_NO_MEMORY,
                "Could not create settings response");
        return;
    }
    send_message(connection, reply);
}

static void handle_settings_read(
        DBusConnection *connection, DBusMessage *request, dbus_bool_t legacy) {
    DBusError error = DBUS_ERROR_INIT;
    const char *namespace = NULL;
    const char *key = NULL;
    if (!dbus_message_get_args(request, &error,
            DBUS_TYPE_STRING, &namespace,
            DBUS_TYPE_STRING, &key,
            DBUS_TYPE_INVALID)) {
        send_error(connection, request, DBUS_ERROR_INVALID_ARGS, "Expected namespace and key");
        dbus_error_free(&error);
        return;
    }
    if (namespace == NULL || strcmp(namespace, "org.freedesktop.appearance") != 0
            || !known_appearance_key(key)
            || (strcmp(key, "accent-color") == 0
                && !appearance_accent_available())) {
        send_error(connection, request, DBUS_ERROR_UNKNOWN_PROPERTY,
                "Unknown portal setting");
        return;
    }
    DBusMessage *reply = dbus_message_new_method_return(request);
    DBusMessageIter output;
    if (reply == NULL) return;
    dbus_message_iter_init_append(reply, &output);
    dbus_bool_t ok;
    if (legacy) {
        DBusMessageIter outer;
        ok = dbus_message_iter_open_container(
                    &output, DBUS_TYPE_VARIANT, "v", &outer)
                && append_appearance_value(&outer, key)
                && dbus_message_iter_close_container(&output, &outer);
    } else {
        ok = append_appearance_value(&output, key);
    }
    if (!ok) {
        dbus_message_unref(reply);
        send_error(connection, request, DBUS_ERROR_NO_MEMORY,
                "Could not create setting response");
        return;
    }
    send_message(connection, reply);
}

static void emit_print_prepare_response(
        DBusConnection *connection, const char *path, const char *destination,
        uint32_t token) {
    DBusMessage *signal = dbus_message_new_signal(
            path, "org.freedesktop.portal.Request", "Response");
    if (signal == NULL) return;
    if (!dbus_message_set_destination(signal, destination)) {
        dbus_message_unref(signal);
        return;
    }
    DBusMessageIter args;
    DBusMessageIter dict;
    uint32_t response = 0;
    dbus_message_iter_init_append(signal, &args);
    dbus_bool_t ok = dbus_message_iter_append_basic(
            &args, DBUS_TYPE_UINT32, &response)
            && dbus_message_iter_open_container(&args, DBUS_TYPE_ARRAY, "{sv}", &dict)
            && append_named_empty_dict(&dict, "settings")
            && append_named_empty_dict(&dict, "page-setup")
            && append_named_uint(&dict, "token", token)
            && dbus_message_iter_close_container(&args, &dict);
    if (!ok) {
        dbus_message_unref(signal);
        return;
    }
    send_message(connection, signal);
}

static void make_request_path(DBusMessage *request, DBusMessageIter *options,
        char *path, size_t path_size) {
    char token[65] = {0};
    read_vardict_strings(options, NULL, 0, NULL, 0, token, sizeof(token));
    if (!valid_path_element(token)) snprintf(token, sizeof(token), "archphene%u", next_id++);
    char sender[65];
    sender_element(dbus_message_get_sender(request), sender, sizeof(sender));
    if (!valid_path_element(sender)) snprintf(sender, sizeof(sender), "client");
    snprintf(path, path_size, "/org/freedesktop/portal/desktop/request/%s/%s",
            sender, token);
}

static dbus_bool_t send_request_reply(DBusConnection *connection,
        DBusMessage *request, const char *path) {
    DBusMessage *reply = dbus_message_new_method_return(request);
    const char *path_value = path;
    if (reply == NULL || !dbus_message_append_args(reply,
            DBUS_TYPE_OBJECT_PATH, &path_value, DBUS_TYPE_INVALID)) {
        if (reply != NULL) dbus_message_unref(reply);
        send_error(connection, request, DBUS_ERROR_NO_MEMORY, "Could not create response");
        return FALSE;
    }
    send_message(connection, reply);
    return TRUE;
}

static void handle_prepare_print(DBusConnection *connection, DBusMessage *request) {
    DBusMessageIter args;
    char title[257] = {0};
    if (!dbus_message_iter_init(request, &args)
            || dbus_message_iter_get_arg_type(&args) != DBUS_TYPE_STRING
            || !dbus_message_iter_next(&args)
            || copy_basic_string(&args, title, sizeof(title)) != 0
            || !dbus_message_iter_next(&args)
            || dbus_message_iter_get_arg_type(&args) != DBUS_TYPE_ARRAY
            || !dbus_message_iter_next(&args)
            || dbus_message_iter_get_arg_type(&args) != DBUS_TYPE_ARRAY
            || !dbus_message_iter_next(&args)
            || dbus_message_iter_get_arg_type(&args) != DBUS_TYPE_ARRAY) {
        send_error(connection, request, DBUS_ERROR_INVALID_ARGS,
                "Expected (ssa{sv}a{sv}a{sv})");
        return;
    }
    char path[256];
    make_request_path(request, &args, path, sizeof(path));
    uint32_t token = next_id++;
    if (token == 0) token = next_id++;
    if (send_request_reply(connection, request, path)) {
        emit_print_prepare_response(
                connection, path, dbus_message_get_sender(request), token);
    }
}

static void handle_print(DBusConnection *connection, DBusMessage *request) {
    DBusMessageIter args;
    char title[257] = {0};
    int pdf_fd = -1;
    if (!dbus_message_iter_init(request, &args)
            || dbus_message_iter_get_arg_type(&args) != DBUS_TYPE_STRING
            || !dbus_message_iter_next(&args)
            || copy_basic_string(&args, title, sizeof(title)) != 0
            || !dbus_message_iter_next(&args)
            || dbus_message_iter_get_arg_type(&args) != DBUS_TYPE_UNIX_FD) {
        send_error(connection, request, DBUS_ERROR_INVALID_ARGS, "Expected (ssha{sv})");
        return;
    }
    dbus_message_iter_get_basic(&args, &pdf_fd);
    if (!dbus_message_iter_next(&args)
            || dbus_message_iter_get_arg_type(&args) != DBUS_TYPE_ARRAY) {
        send_error(connection, request, DBUS_ERROR_INVALID_ARGS, "Expected print options");
        return;
    }
    char path[256];
    make_request_path(request, &args, path, sizeof(path));
    if (title[0] == '\0') snprintf(title, sizeof(title), "%s",
            getenv("ARCHPHENE_APP_NAME") == NULL
                    ? "Linux document" : getenv("ARCHPHENE_APP_NAME"));
    char response[256] = {0};
    int result = archphene_android_print_pdf(pdf_fd, title, response, sizeof(response));
    if (send_request_reply(connection, request, path)) {
        emit_portal_response(connection, path,
                dbus_message_get_sender(request), result == 0 ? 0u : 2u);
    }
}

static void handle_camera_access(DBusConnection *connection, DBusMessage *request) {
    DBusMessageIter options;
    if (!dbus_message_iter_init(request, &options)
            || dbus_message_iter_get_arg_type(&options) != DBUS_TYPE_ARRAY) {
        send_error(connection, request, DBUS_ERROR_INVALID_ARGS, "Expected (a{sv})");
        return;
    }
    char path[256];
    make_request_path(request, &options, path, sizeof(path));
    if (!send_request_reply(connection, request, path)) return;
    char response[256] = {0};
    int result = camera_enabled()
            ? archphene_android_request_camera(response, sizeof(response)) : 1;
    if (result == 1 && strcmp(response, "ERROR\tPERMISSION_REQUESTED") == 0) {
        struct timespec delay = {.tv_sec = 0, .tv_nsec = 100000000};
        for (unsigned int attempt = 0; attempt < 600; attempt++) {
            nanosleep(&delay, NULL);
            result = archphene_android_check_camera(response, sizeof(response));
            if (result == 0 || strcmp(response, "ERROR\tPERMISSION_REQUESTED") != 0) break;
        }
    }
    emit_portal_response(connection, path,
            dbus_message_get_sender(request), result == 0 ? 0u : 2u);
}

static void handle_camera_open_remote(
        DBusConnection *connection, DBusMessage *request) {
    DBusMessageIter options;
    if (!dbus_message_iter_init(request, &options)
            || dbus_message_iter_get_arg_type(&options) != DBUS_TYPE_ARRAY) {
        send_error(connection, request, DBUS_ERROR_INVALID_ARGS, "Expected (a{sv})");
        return;
    }
    char response[256] = {0};
    if (!camera_enabled()
            || archphene_android_check_camera(response, sizeof(response)) != 0) {
        send_error(connection, request, DBUS_ERROR_ACCESS_DENIED,
                "Android camera permission has not been granted");
        return;
    }
    int fd = connect_pipewire_remote();
    if (fd < 0) {
        send_error(connection, request, DBUS_ERROR_FAILED,
                "Private PipeWire camera remote is unavailable");
        return;
    }
    DBusMessage *reply = dbus_message_new_method_return(request);
    if (reply == NULL || !dbus_message_append_args(
            reply, DBUS_TYPE_UNIX_FD, &fd, DBUS_TYPE_INVALID)) {
        if (reply != NULL) dbus_message_unref(reply);
        close(fd);
        send_error(connection, request, DBUS_ERROR_NO_MEMORY, "Could not return PipeWire remote");
        return;
    }
    close(fd);
    send_message(connection, reply);
}

static void handle_open_uri(DBusConnection *connection, DBusMessage *request) {
    DBusMessageIter args;
    char uri[4097] = {0};
    char token[65] = {0};
    if (!dbus_message_iter_init(request, &args)
            || dbus_message_iter_get_arg_type(&args) != DBUS_TYPE_STRING
            || !dbus_message_iter_next(&args)
            || copy_basic_string(&args, uri, sizeof(uri)) != 0
            || !dbus_message_iter_next(&args)
            || dbus_message_iter_get_arg_type(&args) != DBUS_TYPE_ARRAY) {
        send_error(connection, request, DBUS_ERROR_INVALID_ARGS, "Expected (ssa{sv})");
        return;
    }
    read_vardict_strings(&args, NULL, 0, NULL, 0, token, sizeof(token));
    if (!valid_path_element(token)) snprintf(token, sizeof(token), "archphene%u", next_id++);
    char sender[65];
    sender_element(dbus_message_get_sender(request), sender, sizeof(sender));
    if (!valid_path_element(sender)) snprintf(sender, sizeof(sender), "client");
    char path[256];
    snprintf(path, sizeof(path), "/org/freedesktop/portal/desktop/request/%s/%s", sender, token);

    char response[256] = {0};
    int result = archphene_android_open_uri(uri, response, sizeof(response));
    DBusMessage *reply = dbus_message_new_method_return(request);
    const char *path_value = path;
    if (reply == NULL || !dbus_message_append_args(reply,
            DBUS_TYPE_OBJECT_PATH, &path_value, DBUS_TYPE_INVALID)) {
        if (reply != NULL) dbus_message_unref(reply);
        send_error(connection, request, DBUS_ERROR_NO_MEMORY, "Could not create response");
        return;
    }
    send_message(connection, reply);
    emit_portal_response(connection, path,
            dbus_message_get_sender(request), result == 0 ? 0u : 2u);
}

static dbus_bool_t safe_suggested_name(const char *name) {
    if (name == NULL || name[0] == '\0' || strlen(name) > 255
            || strcmp(name, ".") == 0 || strcmp(name, "..") == 0) return FALSE;
    for (const unsigned char *value = (const unsigned char *)name;
            *value != '\0'; value++) {
        if (*value == '/' || *value == '\\' || *value < 32 || *value == 127) return FALSE;
    }
    return TRUE;
}

static void handle_open_file(DBusConnection *connection, DBusMessage *request) {
    DBusMessageIter args;
    char title[257] = {0};
    if (!dbus_message_iter_init(request, &args)
            || dbus_message_iter_get_arg_type(&args) != DBUS_TYPE_STRING
            || !dbus_message_iter_next(&args)
            || copy_basic_string(&args, title, sizeof(title)) != 0
            || !dbus_message_iter_next(&args)
            || dbus_message_iter_get_arg_type(&args) != DBUS_TYPE_ARRAY) {
        send_error(connection, request, DBUS_ERROR_INVALID_ARGS, "Expected (ssa{sv})");
        return;
    }
    dbus_bool_t multiple = FALSE;
    dbus_bool_t directory = FALSE;
    char mime_spec[MAX_MIME_SPEC + 1] = {0};
    read_open_options(
            &args, &multiple, &directory, mime_spec, sizeof(mime_spec));
    if (title[0] == '\0') snprintf(title, sizeof(title), "Open Linux document");
    char path[256];
    make_request_path(request, &args, path, sizeof(path));
    if (!send_request_reply(connection, request, path)) return;

    char uris[32][4097] = {{0}};
    size_t uri_count = 0;
    char fallback_response[64] = "ERROR\tOUT_OF_MEMORY";
    char *response = calloc(MAX_OPEN_RESPONSE, 1);
    int result = -1;
    if (response != NULL) {
        if (directory) {
            result = archphene_android_open_directory(
                    title, &uris[0][0], sizeof(uris[0]),
                    response, MAX_OPEN_RESPONSE);
            if (result == 0) uri_count = 1;
        } else {
            result = archphene_android_open_files(
                    title, mime_spec, multiple ? 1 : 0,
                    &uris[0][0], sizeof(uris[0]), 32, &uri_count,
                    response, MAX_OPEN_RESPONSE);
        }
    } else {
        if (response == NULL) response = fallback_response;
    }
    uint32_t portal_response =
            result == 0 ? 0u : (strcmp(response, "CANCEL") == 0 ? 1u : 2u);
    dbus_bool_t emitted = emit_file_chooser_response(
            connection, path, dbus_message_get_sender(request),
            portal_response, &uris[0][0], sizeof(uris[0]), uri_count);
    fprintf(stderr,
            "OpenFile completed path=%s broker=%d response=%u emitted=%d\n",
            path, result, portal_response, emitted ? 1 : 0);
    if (response != fallback_response) free(response);
}

static void handle_save_file(DBusConnection *connection, DBusMessage *request) {
    DBusMessageIter args;
    char title[257] = {0};
    char name[256] = {0};
    char mime_spec[MAX_MIME_SPEC + 1] = {0};
    if (!dbus_message_iter_init(request, &args)
            || dbus_message_iter_get_arg_type(&args) != DBUS_TYPE_STRING
            || !dbus_message_iter_next(&args)
            || copy_basic_string(&args, title, sizeof(title)) != 0
            || !dbus_message_iter_next(&args)
            || dbus_message_iter_get_arg_type(&args) != DBUS_TYPE_ARRAY) {
        send_error(connection, request, DBUS_ERROR_INVALID_ARGS, "Expected (ssa{sv})");
        return;
    }
    read_save_options(
            &args, name, sizeof(name), mime_spec, sizeof(mime_spec));
    if (!safe_suggested_name(name)) snprintf(name, sizeof(name), "document");
    if (title[0] == '\0') snprintf(title, sizeof(title), "Save Linux document");
    char path[256];
    make_request_path(request, &args, path, sizeof(path));
    if (!send_request_reply(connection, request, path)) return;

    char uri[4097] = {0};
    char response[256] = {0};
    int result = archphene_android_save_file(
            title, name, mime_spec,
            uri, sizeof(uri), response, sizeof(response));
    uint32_t portal_response =
            result == 0 ? 0u : (strcmp(response, "CANCEL") == 0 ? 1u : 2u);
    dbus_bool_t emitted = emit_file_chooser_response(
            connection, path, dbus_message_get_sender(request),
            portal_response,
            result == 0 ? uri : NULL, sizeof(uri), result == 0 ? 1 : 0);
    fprintf(stderr,
            "SaveFile completed path=%s broker=%d response=%u emitted=%d\n",
            path, result, portal_response, emitted);
}

static dbus_bool_t http_scheme(const char *scheme) {
    return scheme != NULL && (strcasecmp(scheme, "http") == 0
            || strcasecmp(scheme, "https") == 0);
}

static void handle_scheme_supported(DBusConnection *connection, DBusMessage *request) {
    DBusMessageIter args;
    char scheme[32] = {0};
    if (!dbus_message_iter_init(request, &args)
            || copy_basic_string(&args, scheme, sizeof(scheme)) != 0) {
        send_error(connection, request, DBUS_ERROR_INVALID_ARGS, "Expected scheme string");
        return;
    }
    dbus_bool_t supported = http_scheme(scheme);
    DBusMessage *reply = dbus_message_new_method_return(request);
    if (reply == NULL || !dbus_message_append_args(reply,
            DBUS_TYPE_BOOLEAN, &supported, DBUS_TYPE_INVALID)) {
        if (reply != NULL) dbus_message_unref(reply);
        send_error(connection, request, DBUS_ERROR_NO_MEMORY, "Could not create response");
        return;
    }
    send_message(connection, reply);
}

static void handle_portal_add_notification(DBusConnection *connection, DBusMessage *request) {
    DBusMessageIter args;
    char source_id[MAX_ID + 1] = {0};
    char title[257] = {0};
    char body[MAX_TEXT + 1] = {0};
    if (!dbus_message_iter_init(request, &args)
            || copy_basic_string(&args, source_id, sizeof(source_id)) != 0
            || !dbus_message_iter_next(&args)
            || dbus_message_iter_get_arg_type(&args) != DBUS_TYPE_ARRAY) {
        send_error(connection, request, DBUS_ERROR_INVALID_ARGS, "Expected (sa{sv})");
        return;
    }
    read_vardict_strings(&args, title, sizeof(title), body, sizeof(body), NULL, 0);
    if (title[0] == '\0') snprintf(title, sizeof(title), "%s",
            getenv("ARCHPHENE_APP_NAME") == NULL ? "Linux application" : getenv("ARCHPHENE_APP_NAME"));
    if (body[0] == '\0') snprintf(body, sizeof(body), "Notification from %s", title);
    char id[128];
    snprintf(id, sizeof(id), "portal-%s", source_id);
    char response[256] = {0};
    int result = archphene_android_notify(id, title, body, response, sizeof(response));
    if (!broker_accepted(result, response)) {
        send_error(connection, request, "org.freedesktop.portal.Error.Failed",
                response[0] == '\0' ? "Android notification failed" : response);
        return;
    }
    send_empty_reply(connection, request);
}

static void handle_portal_remove_notification(DBusConnection *connection, DBusMessage *request) {
    DBusError error = DBUS_ERROR_INIT;
    const char *source_id = NULL;
    if (!dbus_message_get_args(request, &error, DBUS_TYPE_STRING, &source_id, DBUS_TYPE_INVALID)
            || source_id == NULL || strlen(source_id) > MAX_ID) {
        send_error(connection, request, DBUS_ERROR_INVALID_ARGS, "Expected bounded ID");
        dbus_error_free(&error);
        return;
    }
    char id[128];
    snprintf(id, sizeof(id), "portal-%s", source_id);
    char response[256] = {0};
    int result = archphene_android_withdraw_notification(id, response, sizeof(response));
    if (result != 0) {
        send_error(connection, request, "org.freedesktop.portal.Error.Failed",
                response[0] == '\0' ? "Android notification withdrawal failed" : response);
        return;
    }
    send_empty_reply(connection, request);
}

static void handle_classic_notify(DBusConnection *connection, DBusMessage *request) {
    DBusMessageIter args;
    char app[257] = {0};
    char summary[257] = {0};
    char body[MAX_TEXT + 1] = {0};
    uint32_t replaces = 0;
    if (!dbus_message_iter_init(request, &args)
            || copy_basic_string(&args, app, sizeof(app)) != 0
            || !dbus_message_iter_next(&args)
            || dbus_message_iter_get_arg_type(&args) != DBUS_TYPE_UINT32) {
        send_error(connection, request, DBUS_ERROR_INVALID_ARGS, "Expected notification fields");
        return;
    }
    dbus_message_iter_get_basic(&args, &replaces);
    if (!dbus_message_iter_next(&args) || !dbus_message_iter_next(&args)
            || copy_basic_string(&args, summary, sizeof(summary)) != 0
            || !dbus_message_iter_next(&args)
            || copy_basic_string(&args, body, sizeof(body)) != 0) {
        send_error(connection, request, DBUS_ERROR_INVALID_ARGS, "Expected notification text");
        return;
    }
    uint32_t id = replaces == 0 ? next_id++ : replaces;
    if (id == 0) id = next_id++;
    if (summary[0] == '\0') snprintf(summary, sizeof(summary), "%s",
            app[0] == '\0' ? "Linux application" : app);
    if (body[0] == '\0') snprintf(body, sizeof(body), "Notification from %s", summary);
    char text_id[64];
    snprintf(text_id, sizeof(text_id), "classic-%u", id);
    char response[256] = {0};
    int result = archphene_android_notify(text_id, summary, body, response, sizeof(response));
    if (!broker_accepted(result, response)) {
        send_error(connection, request, "org.freedesktop.Notifications.Error.Failed",
                response[0] == '\0' ? "Android notification failed" : response);
        return;
    }
    DBusMessage *reply = dbus_message_new_method_return(request);
    if (reply == NULL || !dbus_message_append_args(reply,
            DBUS_TYPE_UINT32, &id, DBUS_TYPE_INVALID)) {
        if (reply != NULL) dbus_message_unref(reply);
        send_error(connection, request, DBUS_ERROR_NO_MEMORY, "Could not create response");
        return;
    }
    send_message(connection, reply);
}

static void handle_classic_close(DBusConnection *connection, DBusMessage *request) {
    DBusError error = DBUS_ERROR_INIT;
    uint32_t id = 0;
    if (!dbus_message_get_args(request, &error, DBUS_TYPE_UINT32, &id, DBUS_TYPE_INVALID)) {
        send_error(connection, request, DBUS_ERROR_INVALID_ARGS, "Expected notification ID");
        dbus_error_free(&error);
        return;
    }
    char text_id[64];
    snprintf(text_id, sizeof(text_id), "classic-%u", id);
    char response[256] = {0};
    int result = archphene_android_withdraw_notification(text_id, response, sizeof(response));
    if (result != 0) {
        send_error(connection, request, "org.freedesktop.Notifications.Error.Failed",
                response[0] == '\0' ? "Android notification withdrawal failed" : response);
        return;
    }
    send_empty_reply(connection, request);
}

static void handle_properties(DBusConnection *connection, DBusMessage *request) {
    DBusMessageIter args;
    char interface[128] = {0};
    if (!dbus_message_iter_init(request, &args)
            || copy_basic_string(&args, interface, sizeof(interface)) != 0) {
        send_error(connection, request, DBUS_ERROR_INVALID_ARGS, "Expected interface name");
        return;
    }
    dbus_bool_t notification = strcmp(interface, PORTAL_NOTIFICATION) == 0;
    dbus_bool_t printing = strcmp(interface, PORTAL_PRINT) == 0;
    dbus_bool_t camera = strcmp(interface, PORTAL_CAMERA) == 0;
    dbus_bool_t chooser = strcmp(interface, PORTAL_FILE_CHOOSER) == 0;
    dbus_bool_t settings = strcmp(interface, PORTAL_SETTINGS) == 0;
    uint32_t version = settings ? 2u : (chooser ? 4u
            : (camera ? 1u : (notification ? 2u : (printing ? 4u : 5u))));
    if (dbus_message_is_method_call(request, PROPERTIES, "Get")) {
        char property[64] = {0};
        if (!dbus_message_iter_next(&args)
                || copy_basic_string(&args, property, sizeof(property)) != 0) {
            send_error(connection, request, DBUS_ERROR_INVALID_ARGS, "Expected property name");
            return;
        }
        DBusMessage *reply = dbus_message_new_method_return(request);
        DBusMessageIter output;
        DBusMessageIter variant;
        if (reply == NULL) return;
        dbus_message_iter_init_append(reply, &output);
        dbus_bool_t ok = FALSE;
        if (strcmp(property, "version") == 0
                && (camera || notification || printing || chooser || settings
                    || strcmp(interface, PORTAL_OPEN_URI) == 0)) {
            ok = dbus_message_iter_open_container(&output, DBUS_TYPE_VARIANT, "u", &variant)
                    && dbus_message_iter_append_basic(&variant, DBUS_TYPE_UINT32, &version)
                    && dbus_message_iter_close_container(&output, &variant);
        } else if (notification && strcmp(property, "SupportedOptions") == 0) {
            ok = dbus_message_iter_open_container(&output, DBUS_TYPE_VARIANT, "a{sv}", &variant)
                    && append_empty_dict(&variant)
                    && dbus_message_iter_close_container(&output, &variant);
        } else if (camera && strcmp(property, "IsCameraPresent") == 0) {
            dbus_bool_t present = camera_present();
            ok = dbus_message_iter_open_container(&output, DBUS_TYPE_VARIANT, "b", &variant)
                    && dbus_message_iter_append_basic(
                            &variant, DBUS_TYPE_BOOLEAN, &present)
                    && dbus_message_iter_close_container(&output, &variant);
        }
        if (!ok) {
            dbus_message_unref(reply);
            send_error(connection, request, DBUS_ERROR_UNKNOWN_PROPERTY, "Unknown property");
            return;
        }
        send_message(connection, reply);
        return;
    }
    if (!camera && !notification && !printing && !chooser && !settings
            && strcmp(interface, PORTAL_OPEN_URI) != 0) {
        send_error(connection, request, DBUS_ERROR_UNKNOWN_INTERFACE, "Unknown interface");
        return;
    }
    DBusMessage *reply = dbus_message_new_method_return(request);
    DBusMessageIter output;
    DBusMessageIter dict;
    if (reply == NULL) return;
    dbus_message_iter_init_append(reply, &output);
    dbus_bool_t ok = dbus_message_iter_open_container(&output, DBUS_TYPE_ARRAY, "{sv}", &dict)
            && append_version_property(&dict, version)
            && (!notification || append_supported_options_property(&dict))
            && (!camera || append_named_bool(&dict,
                    "IsCameraPresent", camera_present()))
            && dbus_message_iter_close_container(&output, &dict);
    if (!ok) {
        dbus_message_unref(reply);
        send_error(connection, request, DBUS_ERROR_NO_MEMORY, "Could not create response");
        return;
    }
    send_message(connection, reply);
}

static void handle_introspection(DBusConnection *connection, DBusMessage *request) {
    const char *path = dbus_message_get_path(request);
    const char *xml = archphene_atspi_introspection(path);
    if (xml == NULL) xml = archphene_secret_service_introspection(path);
    if (xml == NULL) {
        xml = path != NULL && strcmp(path, NOTIFICATIONS_PATH) == 0
                ? notifications_xml : portal_xml;
    }
    DBusMessage *reply = dbus_message_new_method_return(request);
    if (reply == NULL || !dbus_message_append_args(reply,
            DBUS_TYPE_STRING, &xml, DBUS_TYPE_INVALID)) {
        if (reply != NULL) dbus_message_unref(reply);
        send_error(connection, request, DBUS_ERROR_NO_MEMORY, "Could not create response");
        return;
    }
    send_message(connection, reply);
}

static void handle_message(DBusConnection *connection, DBusMessage *request) {
    if (archphene_atspi_handles(connection, request)) {
        return;
    } else if (dbus_message_is_method_call(request, INTROSPECTABLE, "Introspect")) {
        handle_introspection(connection, request);
    } else if (archphene_secret_service_handles(connection, request)) {
        return;
    } else if (dbus_message_is_method_call(request, PORTAL_OPEN_URI, "OpenURI")) {
        handle_open_uri(connection, request);
    } else if (dbus_message_is_method_call(request, PORTAL_OPEN_URI, "SchemeSupported")) {
        handle_scheme_supported(connection, request);
    } else if (dbus_message_is_method_call(request, PORTAL_FILE_CHOOSER, "OpenFile")) {
        handle_open_file(connection, request);
    } else if (dbus_message_is_method_call(request, PORTAL_FILE_CHOOSER, "SaveFile")) {
        handle_save_file(connection, request);
    } else if (dbus_message_is_method_call(request, PORTAL_SETTINGS, "ReadAll")) {
        handle_settings_read_all(connection, request);
    } else if (dbus_message_is_method_call(request, PORTAL_SETTINGS, "Read")) {
        handle_settings_read(connection, request, TRUE);
    } else if (dbus_message_is_method_call(request, PORTAL_SETTINGS, "ReadOne")) {
        handle_settings_read(connection, request, FALSE);
    } else if (dbus_message_is_method_call(request, PORTAL_CAMERA, "AccessCamera")) {
        handle_camera_access(connection, request);
    } else if (dbus_message_is_method_call(
            request, PORTAL_CAMERA, "OpenPipeWireRemote")) {
        handle_camera_open_remote(connection, request);
    } else if (dbus_message_is_method_call(request, PORTAL_PRINT, "PreparePrint")) {
        handle_prepare_print(connection, request);
    } else if (dbus_message_is_method_call(request, PORTAL_PRINT, "Print")) {
        handle_print(connection, request);
    } else if (dbus_message_is_method_call(request, PORTAL_NOTIFICATION, "AddNotification")) {
        handle_portal_add_notification(connection, request);
    } else if (dbus_message_is_method_call(request, PORTAL_NOTIFICATION, "RemoveNotification")) {
        handle_portal_remove_notification(connection, request);
    } else if (dbus_message_is_method_call(request, PROPERTIES, "Get")
            || dbus_message_is_method_call(request, PROPERTIES, "GetAll")) {
        handle_properties(connection, request);
    } else if (dbus_message_is_method_call(request, CLASSIC_NOTIFICATION, "GetCapabilities")) {
        DBusMessage *reply = dbus_message_new_method_return(request);
        DBusMessageIter output;
        DBusMessageIter capabilities;
        if (reply == NULL) return;
        dbus_message_iter_init_append(reply, &output);
        if (!dbus_message_iter_open_container(&output, DBUS_TYPE_ARRAY, "s", &capabilities)
                || !dbus_message_iter_close_container(&output, &capabilities)) {
            dbus_message_unref(reply);
            return;
        }
        send_message(connection, reply);
    } else if (dbus_message_is_method_call(request, CLASSIC_NOTIFICATION,
            "GetServerInformation")) {
        const char *name = "Archphene";
        const char *vendor = "Archphene";
        const char *version = "1";
        const char *spec = "1.2";
        DBusMessage *reply = dbus_message_new_method_return(request);
        if (reply != NULL && dbus_message_append_args(reply,
                DBUS_TYPE_STRING, &name, DBUS_TYPE_STRING, &vendor,
                DBUS_TYPE_STRING, &version, DBUS_TYPE_STRING, &spec,
                DBUS_TYPE_INVALID)) send_message(connection, reply);
        else if (reply != NULL) dbus_message_unref(reply);
    } else if (dbus_message_is_method_call(request, CLASSIC_NOTIFICATION, "Notify")) {
        handle_classic_notify(connection, request);
    } else if (dbus_message_is_method_call(request, CLASSIC_NOTIFICATION,
            "CloseNotification")) {
        handle_classic_close(connection, request);
    } else {
        send_error(connection, request, DBUS_ERROR_UNKNOWN_METHOD,
                "Archphene does not implement this portal method");
    }
}

static int own_name(DBusConnection *connection, const char *name, DBusError *error) {
    int result = dbus_bus_request_name(connection, name, DBUS_NAME_FLAG_DO_NOT_QUEUE, error);
    return result == DBUS_REQUEST_NAME_REPLY_PRIMARY_OWNER ? 0 : -1;
}

int main(void) {
    if (!dbus_threads_init_default()) {
        fprintf(stderr, "Archphene portal could not initialize D-Bus threading\n");
        return 70;
    }
    signal(SIGINT, stop_running);
    signal(SIGTERM, stop_running);
    load_environment_appearance();
    struct appearance_state initial;
    if (read_appearance_state(&initial)) appearance = initial;
    DBusError error = DBUS_ERROR_INIT;
    DBusConnection *connection = dbus_bus_get_private(DBUS_BUS_SESSION, &error);
    if (connection == NULL) {
        fprintf(stderr, "Archphene portal could not connect: %s\n",
                error.message == NULL ? "unknown error" : error.message);
        dbus_error_free(&error);
        return 70;
    }
    dbus_connection_set_exit_on_disconnect(connection, FALSE);
    const char *secrets_enabled = getenv("ARCHPHENE_ENABLE_SECRETS");
    const char *accessibility_enabled = getenv("ARCHPHENE_ENABLE_ACCESSIBILITY");
    if (own_name(connection, PORTAL_NAME, &error) != 0
            || own_name(connection, CLASSIC_NOTIFICATION, &error) != 0
            || (secrets_enabled != NULL && strcmp(secrets_enabled, "1") == 0
                && archphene_secret_service_own_name(connection, &error) != 0)
            || (accessibility_enabled != NULL
                && strcmp(accessibility_enabled, "1") == 0
                && archphene_atspi_init(connection, &error) != 0)) {
        fprintf(stderr, "Archphene portal could not own bus name: %s\n",
                error.message == NULL ? "name already owned" : error.message);
        dbus_error_free(&error);
        archphene_atspi_shutdown();
        dbus_connection_close(connection);
        dbus_connection_unref(connection);
        return 70;
    }
    dbus_bus_add_match(connection,
            "type='signal',sender='org.freedesktop.DBus',"
            "interface='org.freedesktop.DBus',member='NameOwnerChanged'", &error);
    if (dbus_error_is_set(&error)) {
        fprintf(stderr, "Archphene portal could not subscribe to owner changes: %s\n",
                error.message == NULL ? "unknown error" : error.message);
        dbus_error_free(&error);
        archphene_atspi_shutdown();
        dbus_connection_close(connection);
        dbus_connection_unref(connection);
        return 70;
    }
    dbus_connection_flush(connection);
    fprintf(stderr, "Archphene portal ready\n");
    while (running && dbus_connection_get_is_connected(connection)) {
        dbus_connection_read_write(connection, 250);
        poll_appearance_state(connection);
        DBusMessage *message;
        while ((message = dbus_connection_pop_message(connection)) != NULL) {
            if (dbus_message_get_type(message) == DBUS_MESSAGE_TYPE_METHOD_CALL)
                handle_message(connection, message);
            else if (dbus_message_get_type(message) == DBUS_MESSAGE_TYPE_SIGNAL) {
                archphene_secret_service_handle_signal(message);
                archphene_atspi_handle_signal(connection, message);
            } else {
                archphene_atspi_handles_reply(message);
            }
            dbus_message_unref(message);
        }
    }
    archphene_atspi_shutdown();
    dbus_connection_close(connection);
    dbus_connection_unref(connection);
    return 0;
}
