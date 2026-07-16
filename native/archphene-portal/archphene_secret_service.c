#define _GNU_SOURCE

#include "archphene_secret_service.h"

#include "archphene_android.h"
#include "archphene_secret_crypto.h"

#include <errno.h>
#include <fcntl.h>
#include <linux/memfd.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/random.h>
#include <unistd.h>

#define SECRET_NAME "org.freedesktop.secrets"
#define SERVICE_PATH "/org/freedesktop/secrets"
#define COLLECTION_PATH "/org/freedesktop/secrets/collection/login"
#define DEFAULT_ALIAS_PATH "/org/freedesktop/secrets/aliases/default"
#define ITEM_PREFIX COLLECTION_PATH "/i"
#define SESSION_PREFIX "/org/freedesktop/secrets/session/s"
#define SERVICE_IFACE "org.freedesktop.Secret.Service"
#define COLLECTION_IFACE "org.freedesktop.Secret.Collection"
#define ITEM_IFACE "org.freedesktop.Secret.Item"
#define SESSION_IFACE "org.freedesktop.Secret.Session"
#define PROPERTIES_IFACE "org.freedesktop.DBus.Properties"
#define INTROSPECTABLE_IFACE "org.freedesktop.DBus.Introspectable"
#define ERROR_NOT_SUPPORTED DBUS_ERROR_NOT_SUPPORTED
#define ERROR_NO_SESSION "org.freedesktop.Secret.Error.NoSession"
#define MAX_ITEMS 256
#define MAX_ATTRIBUTES 32
#define MAX_SECRET_BYTES (64 * 1024)
#define MAX_CATALOG_BYTES (1024 * 1024)
#define MAX_TRANSFER_BYTES (MAX_SECRET_BYTES + ARCHPHENE_SECRET_AES_IV_BYTES)

typedef struct {
    char *key;
    char *value;
} SecretAttribute;

typedef struct {
    char *id;
    char *label;
    char *content_type;
    SecretAttribute attributes[MAX_ATTRIBUTES];
    size_t attribute_count;
    uint32_t secret_bytes;
} SecretMetadata;

typedef struct {
    SecretMetadata items[MAX_ITEMS];
    size_t count;
} SecretCatalog;

typedef enum {
    SECRET_SESSION_PLAIN = 0,
    SECRET_SESSION_DH_AES = 1
} SecretSessionMode;

typedef struct {
    dbus_bool_t active;
    char path[96];
    char sender[256];
    SecretSessionMode mode;
    uint8_t key[ARCHPHENE_SECRET_AES_KEY_BYTES];
} SecretSession;

static SecretSession sessions[64];
static uint32_t next_session = 1;
static uint8_t secret_input_scratch[MAX_SECRET_BYTES];
static uint8_t secret_output_scratch[MAX_TRANSFER_BYTES];

static dbus_bool_t is_collection_path(const char *path) {
    return path != NULL && (strcmp(path, COLLECTION_PATH) == 0
            || strcmp(path, DEFAULT_ALIAS_PATH) == 0);
}

static const char service_xml[] =
        "<node><interface name='org.freedesktop.Secret.Service'>"
        "<method name='OpenSession'><arg type='s' direction='in'/><arg type='v' direction='in'/>"
        "<arg type='v' direction='out'/><arg type='o' direction='out'/></method>"
        "<method name='CreateCollection'><arg type='a{sv}' direction='in'/><arg type='s' direction='in'/>"
        "<arg type='o' direction='out'/><arg type='o' direction='out'/></method>"
        "<method name='SearchItems'><arg type='a{ss}' direction='in'/><arg type='ao' direction='out'/>"
        "<arg type='ao' direction='out'/></method>"
        "<method name='Unlock'><arg type='ao' direction='in'/><arg type='ao' direction='out'/>"
        "<arg type='o' direction='out'/></method>"
        "<method name='Lock'><arg type='ao' direction='in'/><arg type='ao' direction='out'/>"
        "<arg type='o' direction='out'/></method>"
        "<method name='GetSecrets'><arg type='ao' direction='in'/><arg type='o' direction='in'/>"
        "<arg type='a{o(oayays)}' direction='out'/></method>"
        "<method name='ReadAlias'><arg type='s' direction='in'/><arg type='o' direction='out'/></method>"
        "<method name='SetAlias'><arg type='s' direction='in'/><arg type='o' direction='in'/></method>"
        "<property name='Collections' type='ao' access='read'/></interface>"
        "<interface name='org.freedesktop.DBus.Introspectable'>"
        "<method name='Introspect'><arg type='s' direction='out'/></method></interface></node>";

static const char collection_xml[] =
        "<node><interface name='org.freedesktop.Secret.Collection'>"
        "<method name='Delete'><arg type='o' direction='out'/></method>"
        "<method name='SearchItems'><arg type='a{ss}' direction='in'/><arg type='ao' direction='out'/></method>"
        "<method name='CreateItem'><arg type='a{sv}' direction='in'/><arg type='(oayays)' direction='in'/>"
        "<arg type='b' direction='in'/><arg type='o' direction='out'/><arg type='o' direction='out'/></method>"
        "<signal name='ItemCreated'><arg type='o'/></signal>"
        "<signal name='ItemDeleted'><arg type='o'/></signal>"
        "<signal name='ItemChanged'><arg type='o'/></signal>"
        "<property name='Items' type='ao' access='read'/><property name='Label' type='s' access='readwrite'/>"
        "<property name='Locked' type='b' access='read'/><property name='Created' type='t' access='read'/>"
        "<property name='Modified' type='t' access='read'/></interface>"
        "<interface name='org.freedesktop.DBus.Introspectable'>"
        "<method name='Introspect'><arg type='s' direction='out'/></method></interface></node>";

static const char item_xml[] =
        "<node><interface name='org.freedesktop.Secret.Item'>"
        "<method name='Delete'><arg type='o' direction='out'/></method>"
        "<method name='GetSecret'><arg type='o' direction='in'/><arg type='(oayays)' direction='out'/></method>"
        "<method name='SetSecret'><arg type='(oayays)' direction='in'/></method>"
        "<property name='Locked' type='b' access='read'/><property name='Attributes' type='a{ss}' access='readwrite'/>"
        "<property name='Label' type='s' access='readwrite'/><property name='Created' type='t' access='read'/>"
        "<property name='Modified' type='t' access='read'/></interface>"
        "<interface name='org.freedesktop.DBus.Introspectable'>"
        "<method name='Introspect'><arg type='s' direction='out'/></method></interface></node>";

static const char session_xml[] =
        "<node><interface name='org.freedesktop.Secret.Session'>"
        "<method name='Close'/></interface><interface name='org.freedesktop.DBus.Introspectable'>"
        "<method name='Introspect'><arg type='s' direction='out'/></method></interface></node>";

static dbus_bool_t send_message(DBusConnection *connection, DBusMessage *message) {
    if (message == NULL) return FALSE;
    dbus_bool_t sent = dbus_connection_send(connection, message, NULL);
    dbus_message_unref(message);
    dbus_connection_flush(connection);
    return sent;
}

static void emit_object_path_signal(DBusConnection *connection,
        const char *path, const char *interface, const char *member,
        const char *object_path) {
    DBusMessage *signal = dbus_message_new_signal(path, interface, member);
    if (signal != NULL && dbus_message_append_args(signal,
            DBUS_TYPE_OBJECT_PATH, &object_path, DBUS_TYPE_INVALID)) {
        send_message(connection, signal);
    } else if (signal != NULL) {
        dbus_message_unref(signal);
    }
}

static void emit_item_signal(DBusConnection *connection, const char *member,
        const char *item_path) {
    emit_object_path_signal(connection, COLLECTION_PATH, COLLECTION_IFACE,
            member, item_path);
}
static void send_error(DBusConnection *connection, DBusMessage *request,
        const char *name, const char *detail) {
    send_message(connection, dbus_message_new_error(request, name, detail));
}

static void free_catalog(SecretCatalog *catalog) {
    if (catalog == NULL) return;
    for (size_t index = 0; index < catalog->count; index++) {
        SecretMetadata *item = &catalog->items[index];
        free(item->id);
        free(item->label);
        free(item->content_type);
        for (size_t attribute = 0; attribute < item->attribute_count; attribute++) {
            free(item->attributes[attribute].key);
            free(item->attributes[attribute].value);
        }
    }
    memset(catalog, 0, sizeof(*catalog));
}

static uint16_t take_u16(const uint8_t **cursor, const uint8_t *end, dbus_bool_t *ok) {
    if (!*ok || (size_t)(end - *cursor) < 2) { *ok = FALSE; return 0; }
    uint16_t value = (uint16_t)((*cursor)[0] << 8 | (*cursor)[1]);
    *cursor += 2;
    return value;
}

static uint32_t take_u32(const uint8_t **cursor, const uint8_t *end, dbus_bool_t *ok) {
    if (!*ok || (size_t)(end - *cursor) < 4) { *ok = FALSE; return 0; }
    uint32_t value = (uint32_t)(*cursor)[0] << 24 | (uint32_t)(*cursor)[1] << 16
            | (uint32_t)(*cursor)[2] << 8 | (*cursor)[3];
    *cursor += 4;
    return value;
}

static char *take_string(const uint8_t **cursor, const uint8_t *end,
        size_t maximum, dbus_bool_t *ok) {
    uint16_t length = take_u16(cursor, end, ok);
    if (!*ok || length > maximum || (size_t)(end - *cursor) < length) {
        *ok = FALSE;
        return NULL;
    }
    char *value = calloc((size_t)length + 1, 1);
    if (value == NULL) { *ok = FALSE; return NULL; }
    memcpy(value, *cursor, length);
    *cursor += length;
    if (!dbus_validate_utf8(value, NULL)) {
        free(value);
        *ok = FALSE;
        return NULL;
    }
    return value;
}

static int load_catalog(SecretCatalog *catalog) {
    memset(catalog, 0, sizeof(*catalog));
    int fd = (int)syscall(__NR_memfd_create, "archphene-secret-catalog", MFD_CLOEXEC);
    if (fd < 0) return -1;
    char response[256] = {0};
    int result = archphene_android_catalog_secrets(fd, response, sizeof(response));
    struct stat stat = {0};
    if (result != 0 || fstat(fd, &stat) != 0 || stat.st_size < 7
            || stat.st_size > MAX_CATALOG_BYTES || lseek(fd, 0, SEEK_SET) < 0) {
        close(fd);
        errno = result == 0 ? EPROTO : EACCES;
        return -1;
    }
    uint8_t *encoded = malloc((size_t)stat.st_size);
    if (encoded == NULL) { close(fd); return -1; }
    size_t offset = 0;
    while (offset < (size_t)stat.st_size) {
        ssize_t count = read(fd, encoded + offset, (size_t)stat.st_size - offset);
        if (count <= 0) { free(encoded); close(fd); errno = EIO; return -1; }
        offset += (size_t)count;
    }
    close(fd);
    const uint8_t *cursor = encoded;
    const uint8_t *end = encoded + stat.st_size;
    dbus_bool_t ok = TRUE;
    if (take_u32(&cursor, end, &ok) != 0x41504331 || cursor >= end || *cursor++ != 2) ok = FALSE;
    uint16_t item_count = take_u16(&cursor, end, &ok);
    if (item_count > MAX_ITEMS) ok = FALSE;
    for (size_t index = 0; ok && index < item_count; index++) {
        SecretMetadata *item = &catalog->items[index];
        item->id = take_string(&cursor, end, 128 * 4, &ok);
        item->label = take_string(&cursor, end, 256 * 4, &ok);
        item->content_type = take_string(&cursor, end, 128 * 4, &ok);
        if (!ok || cursor >= end) { ok = FALSE; break; }
        item->attribute_count = *cursor++;
        if (item->attribute_count > MAX_ATTRIBUTES) { ok = FALSE; break; }
        for (size_t attribute = 0; ok && attribute < item->attribute_count; attribute++) {
            item->attributes[attribute].key = take_string(&cursor, end, 128 * 4, &ok);
            item->attributes[attribute].value = take_string(&cursor, end, 512 * 4, &ok);
        }
        item->secret_bytes = take_u32(&cursor, end, &ok);
        if (item->secret_bytes > MAX_SECRET_BYTES) ok = FALSE;
        if (ok) catalog->count++;
    }
    if (cursor != end) ok = FALSE;
    free(encoded);
    if (!ok) { free_catalog(catalog); errno = EPROTO; return -1; }
    return 0;
}


static dbus_bool_t send_empty_reply(DBusConnection *connection, DBusMessage *request) {
    return send_message(connection, dbus_message_new_method_return(request));
}

static dbus_bool_t append_object_paths(DBusMessageIter *parent,
        const char *const *paths, size_t count) {
    DBusMessageIter array;
    if (!dbus_message_iter_open_container(parent, DBUS_TYPE_ARRAY, "o", &array)) return FALSE;
    for (size_t index = 0; index < count; index++) {
        const char *path = paths[index];
        if (!dbus_message_iter_append_basic(&array, DBUS_TYPE_OBJECT_PATH, &path)) return FALSE;
    }
    return dbus_message_iter_close_container(parent, &array);
}

static dbus_bool_t item_path(const char *id, char *path, size_t size) {
    static const char hex[] = "0123456789abcdef";
    size_t prefix = strlen(ITEM_PREFIX);
    size_t length = strlen(id);
    if (prefix + length * 2 + 1 > size) return FALSE;
    memcpy(path, ITEM_PREFIX, prefix);
    for (size_t index = 0; index < length; index++) {
        uint8_t value = (uint8_t)id[index];
        path[prefix + index * 2] = hex[value >> 4];
        path[prefix + index * 2 + 1] = hex[value & 15];
    }
    path[prefix + length * 2] = '\0';
    return TRUE;
}

static SecretMetadata *find_item(SecretCatalog *catalog, const char *path) {
    char candidate[ITEM_PREFIX[0] + 1024];
    (void)candidate;
    for (size_t index = 0; index < catalog->count; index++) {
        char encoded[1200];
        if (item_path(catalog->items[index].id, encoded, sizeof(encoded))
                && strcmp(path, encoded) == 0) return &catalog->items[index];
    }
    return NULL;
}

static void free_attributes(SecretAttribute *attributes, size_t count) {
    for (size_t index = 0; index < count; index++) {
        free(attributes[index].key);
        free(attributes[index].value);
    }
    memset(attributes, 0, sizeof(*attributes) * MAX_ATTRIBUTES);
}

static dbus_bool_t parse_attributes_iter(DBusMessageIter *source,
        SecretAttribute *attributes, size_t *count) {
    if (dbus_message_iter_get_arg_type(source) != DBUS_TYPE_ARRAY
            || dbus_message_iter_get_element_type(source) != DBUS_TYPE_DICT_ENTRY) return FALSE;
    DBusMessageIter array;
    dbus_message_iter_recurse(source, &array);
    while (dbus_message_iter_get_arg_type(&array) != DBUS_TYPE_INVALID) {
        if (*count >= MAX_ATTRIBUTES
                || dbus_message_iter_get_arg_type(&array) != DBUS_TYPE_DICT_ENTRY) return FALSE;
        DBusMessageIter entry;
        dbus_message_iter_recurse(&array, &entry);
        if (dbus_message_iter_get_arg_type(&entry) != DBUS_TYPE_STRING) return FALSE;
        const char *key = NULL;
        dbus_message_iter_get_basic(&entry, &key);
        if (!dbus_message_iter_next(&entry)
                || dbus_message_iter_get_arg_type(&entry) != DBUS_TYPE_STRING) return FALSE;
        const char *value = NULL;
        dbus_message_iter_get_basic(&entry, &value);
        if (key == NULL || value == NULL || strlen(key) < 1 || strlen(key) > 128
                || strlen(value) > 512 || !dbus_validate_utf8(key, NULL)
                || !dbus_validate_utf8(value, NULL)) return FALSE;
        for (size_t index = 0; index < *count; index++) {
            if (strcmp(attributes[index].key, key) == 0) return FALSE;
        }
        attributes[*count].key = strdup(key);
        attributes[*count].value = strdup(value);
        if (attributes[*count].key == NULL || attributes[*count].value == NULL) return FALSE;
        (*count)++;
        dbus_message_iter_next(&array);
    }
    return TRUE;
}

static dbus_bool_t parse_attributes(DBusMessage *message,
        SecretAttribute *attributes, size_t *count) {
    *count = 0;
    DBusMessageIter iterator;
    if (!dbus_message_iter_init(message, &iterator)) return FALSE;
    return parse_attributes_iter(&iterator, attributes, count)
            && !dbus_message_iter_next(&iterator);
}

static dbus_bool_t attributes_match(const SecretMetadata *item,
        const SecretAttribute *query, size_t query_count) {
    for (size_t query_index = 0; query_index < query_count; query_index++) {
        dbus_bool_t found = FALSE;
        for (size_t item_index = 0; item_index < item->attribute_count; item_index++) {
            if (strcmp(query[query_index].key, item->attributes[item_index].key) == 0
                    && strcmp(query[query_index].value,
                        item->attributes[item_index].value) == 0) {
                found = TRUE;
                break;
            }
        }
        if (!found) return FALSE;
    }
    return TRUE;
}

static dbus_bool_t attributes_equal(const SecretMetadata *item,
        const SecretAttribute *attributes, size_t count) {
    return count == item->attribute_count && attributes_match(item, attributes, count);
}

static dbus_bool_t append_attributes(DBusMessageIter *parent,
        const SecretAttribute *attributes, size_t count) {
    DBusMessageIter array;
    if (!dbus_message_iter_open_container(parent, DBUS_TYPE_ARRAY, "{ss}", &array)) return FALSE;
    for (size_t index = 0; index < count; index++) {
        DBusMessageIter entry;
        const char *key = attributes[index].key;
        const char *value = attributes[index].value;
        if (!dbus_message_iter_open_container(&array, DBUS_TYPE_DICT_ENTRY, NULL, &entry)
                || !dbus_message_iter_append_basic(&entry, DBUS_TYPE_STRING, &key)
                || !dbus_message_iter_append_basic(&entry, DBUS_TYPE_STRING, &value)
                || !dbus_message_iter_close_container(&array, &entry)) return FALSE;
    }
    return dbus_message_iter_close_container(parent, &array);
}

static dbus_bool_t json_append(char **cursor, const char *end, const char *value) {
    for (const unsigned char *input = (const unsigned char *)value; *input; input++) {
        const char *escape = NULL;
        char encoded[7] = {0};
        if (*input == '"' || *input == '\\') {
            encoded[0] = '\\';
            encoded[1] = (char)*input;
            escape = encoded;
        } else if (*input < 0x20) {
            snprintf(encoded, sizeof(encoded), "\\u%04x", *input);
            escape = encoded;
        }
        size_t bytes = escape == NULL ? 1 : strlen(escape);
        if ((size_t)(end - *cursor) <= bytes) return FALSE;
        if (escape == NULL) *(*cursor)++ = (char)*input;
        else { memcpy(*cursor, escape, bytes); *cursor += bytes; }
    }
    return TRUE;
}

static char *attributes_json(const SecretAttribute *attributes, size_t count) {
    char *json = calloc(8193, 1);
    if (json == NULL) return NULL;
    char *cursor = json;
    const char *end = json + 8193;
    *cursor++ = '{';
    for (size_t index = 0; index < count; index++) {
        if (index != 0) *cursor++ = ',';
        if ((size_t)(end - cursor) < 5) { free(json); return NULL; }
        *cursor++ = '"';
        if (!json_append(&cursor, end, attributes[index].key)) { free(json); return NULL; }
        *cursor++ = '"'; *cursor++ = ':'; *cursor++ = '"';
        if (!json_append(&cursor, end, attributes[index].value)) { free(json); return NULL; }
        *cursor++ = '"';
    }
    if ((size_t)(end - cursor) < 2) { free(json); return NULL; }
    *cursor++ = '}';
    *cursor = '\0';
    return json;
}

static void clear_session(SecretSession *session) {
    if (session == NULL) return;
    archphene_secret_crypto_wipe(session->key, sizeof(session->key));
    memset(session, 0, sizeof(*session));
}

static SecretSession *find_session(const char *path, const char *sender) {
    for (size_t index = 0; index < sizeof(sessions) / sizeof(sessions[0]); index++) {
        if (sessions[index].active && strcmp(sessions[index].path, path) == 0
                && strcmp(sessions[index].sender, sender) == 0) return &sessions[index];
    }
    return NULL;
}

static SecretSession *create_session(const char *sender) {
    for (size_t index = 0; index < sizeof(sessions) / sizeof(sessions[0]); index++) {
        if (!sessions[index].active) {
            SecretSession *session = &sessions[index];
            clear_session(session);
            snprintf(session->path, sizeof(session->path), SESSION_PREFIX "%u", next_session++);
            if (next_session == 0) next_session = 1;
            snprintf(session->sender, sizeof(session->sender), "%s", sender);
            session->active = TRUE;
            return session;
        }
    }
    return NULL;
}

static dbus_bool_t random_id(char output[33]) {
    uint8_t bytes[16];
    size_t offset = 0;
    while (offset < sizeof(bytes)) {
        ssize_t count = getrandom(bytes + offset, sizeof(bytes) - offset, 0);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return FALSE;
        offset += (size_t)count;
    }
    static const char hex[] = "0123456789abcdef";
    for (size_t index = 0; index < sizeof(bytes); index++) {
        output[index * 2] = hex[bytes[index] >> 4];
        output[index * 2 + 1] = hex[bytes[index] & 15];
    }
    output[32] = '\0';
    memset(bytes, 0, sizeof(bytes));
    return TRUE;
}

static int create_secret_fd(const uint8_t *value, int length) {
    if (length < 0 || length > MAX_SECRET_BYTES) { errno = EINVAL; return -1; }
    int fd = (int)syscall(__NR_memfd_create, "archphene-secret", MFD_CLOEXEC);
    if (fd < 0) return -1;
    int offset = 0;
    while (offset < length) {
        ssize_t count = write(fd, value + offset, (size_t)(length - offset));
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) { close(fd); errno = EIO; return -1; }
        offset += (int)count;
    }
    if (lseek(fd, 0, SEEK_SET) < 0) { close(fd); return -1; }
    return fd;
}

static dbus_bool_t parse_secret_iter(DBusMessageIter *source, const char *sender,
        const uint8_t **value, int *length, const char **content_type) {
    if (dbus_message_iter_get_arg_type(source) != DBUS_TYPE_STRUCT) return FALSE;
    DBusMessageIter structure;
    dbus_message_iter_recurse(source, &structure);
    if (dbus_message_iter_get_arg_type(&structure) != DBUS_TYPE_OBJECT_PATH) return FALSE;
    const char *session_path = NULL;
    dbus_message_iter_get_basic(&structure, &session_path);
    SecretSession *session = find_session(session_path, sender);
    if (session == NULL || !dbus_message_iter_next(&structure)
            || dbus_message_iter_get_arg_type(&structure) != DBUS_TYPE_ARRAY
            || dbus_message_iter_get_element_type(&structure) != DBUS_TYPE_BYTE) return FALSE;

    DBusMessageIter parameters;
    dbus_message_iter_recurse(&structure, &parameters);
    int parameters_length = 0;
    const uint8_t *parameters_value = NULL;
    dbus_message_iter_get_fixed_array(&parameters, &parameters_value, &parameters_length);
    if (parameters_length < 0 || !dbus_message_iter_next(&structure)
            || dbus_message_iter_get_arg_type(&structure) != DBUS_TYPE_ARRAY
            || dbus_message_iter_get_element_type(&structure) != DBUS_TYPE_BYTE) return FALSE;

    DBusMessageIter bytes;
    dbus_message_iter_recurse(&structure, &bytes);
    int encoded_length = 0;
    const uint8_t *encoded_value = NULL;
    dbus_message_iter_get_fixed_array(&bytes, &encoded_value, &encoded_length);
    if (encoded_length < 0 || encoded_length > MAX_TRANSFER_BYTES
            || !dbus_message_iter_next(&structure)
            || dbus_message_iter_get_arg_type(&structure) != DBUS_TYPE_STRING) return FALSE;
    dbus_message_iter_get_basic(&structure, content_type);
    if (*content_type == NULL || strlen(*content_type) == 0
            || strlen(*content_type) > 128 || !dbus_validate_utf8(*content_type, NULL)
            || dbus_message_iter_next(&structure)) return FALSE;

    if (session->mode == SECRET_SESSION_PLAIN) {
        if (parameters_length != 0 || encoded_length > MAX_SECRET_BYTES) return FALSE;
        *value = encoded_value;
        *length = encoded_length;
        return TRUE;
    }
    if (parameters_length != ARCHPHENE_SECRET_AES_IV_BYTES
            || encoded_length == 0
            || encoded_length % ARCHPHENE_SECRET_AES_IV_BYTES != 0) return FALSE;

    archphene_secret_crypto_wipe(secret_input_scratch, sizeof(secret_input_scratch));
    size_t decoded_length = 0;
    if (archphene_secret_crypto_decrypt(session->key, parameters_value,
            encoded_value, (size_t)encoded_length,
            secret_input_scratch, sizeof(secret_input_scratch),
            &decoded_length) != 0 || decoded_length > MAX_SECRET_BYTES) {
        archphene_secret_crypto_wipe(secret_input_scratch, sizeof(secret_input_scratch));
        return FALSE;
    }
    *value = secret_input_scratch;
    *length = (int)decoded_length;
    return TRUE;
}
static int store_secret_value(const char *id, const char *label,
        const SecretAttribute *attributes, size_t count,
        const uint8_t *value, int length, const char *content_type) {
    char *json = attributes_json(attributes, count);
    int fd = json == NULL ? -1 : create_secret_fd(value, length);
    if (json == NULL || fd < 0) { free(json); return -1; }
    char response[256] = {0};
    int result = archphene_android_store_secret_typed(fd, id, label, json, content_type,
            response, sizeof(response));
    close(fd);
    free(json);
    return result;
}

static uint8_t *read_secret_value(const SecretMetadata *item, int *length) {
    int fd = (int)syscall(__NR_memfd_create, "archphene-secret-read", MFD_CLOEXEC);
    if (fd < 0) return NULL;
    char response[1024] = {0};
    if (archphene_android_read_secret(fd, item->id, response, sizeof(response)) != 0
            || lseek(fd, 0, SEEK_SET) < 0) { close(fd); return NULL; }
    struct stat stat = {0};
    if (fstat(fd, &stat) != 0 || stat.st_size < 0 || stat.st_size > MAX_SECRET_BYTES) {
        close(fd); errno = EPROTO; return NULL;
    }
    uint8_t *value = malloc((size_t)stat.st_size + 1);
    if (value == NULL) { close(fd); return NULL; }
    size_t offset = 0;
    while (offset < (size_t)stat.st_size) {
        ssize_t count = read(fd, value + offset, (size_t)stat.st_size - offset);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) { memset(value, 0, (size_t)stat.st_size); free(value);
            close(fd); errno = EIO; return NULL; }
        offset += (size_t)count;
    }
    close(fd);
    *length = (int)stat.st_size;
    return value;
}

static dbus_bool_t append_secret(DBusMessageIter *parent, const char *session_path,
        const uint8_t *value, int length, const char *content_type) {
    SecretSession *session = NULL;
    for (size_t index = 0; index < sizeof(sessions) / sizeof(sessions[0]); index++) {
        if (sessions[index].active && strcmp(sessions[index].path, session_path) == 0) {
            session = &sessions[index];
            break;
        }
    }
    if (session == NULL || value == NULL || length < 0 || length > MAX_SECRET_BYTES)
        return FALSE;

    uint8_t iv[ARCHPHENE_SECRET_AES_IV_BYTES] = {0};
    const uint8_t *parameters_value = NULL;
    int parameters_length = 0;
    const uint8_t *encoded_value = value;
    int encoded_length = length;
    if (session->mode == SECRET_SESSION_DH_AES) {
        size_t offset = 0;
        while (offset < sizeof(iv)) {
            ssize_t count = getrandom(iv + offset, sizeof(iv) - offset, 0);
            if (count < 0 && errno == EINTR) continue;
            if (count <= 0) return FALSE;
            offset += (size_t)count;
        }
        size_t encrypted_length = 0;
        archphene_secret_crypto_wipe(secret_output_scratch,
                sizeof(secret_output_scratch));
        if (archphene_secret_crypto_encrypt(session->key, iv, value, (size_t)length,
                secret_output_scratch, sizeof(secret_output_scratch),
                &encrypted_length) != 0 || encrypted_length > INT32_MAX) {
            archphene_secret_crypto_wipe(secret_output_scratch,
                    sizeof(secret_output_scratch));
            return FALSE;
        }
        parameters_value = iv;
        parameters_length = sizeof(iv);
        encoded_value = secret_output_scratch;
        encoded_length = (int)encrypted_length;
    }

    DBusMessageIter structure, parameters, bytes;
    dbus_bool_t ok =
            dbus_message_iter_open_container(parent, DBUS_TYPE_STRUCT, NULL, &structure)
            && dbus_message_iter_append_basic(&structure, DBUS_TYPE_OBJECT_PATH, &session_path)
            && dbus_message_iter_open_container(&structure, DBUS_TYPE_ARRAY, "y", &parameters)
            && dbus_message_iter_append_fixed_array(&parameters, DBUS_TYPE_BYTE,
                &parameters_value, parameters_length)
            && dbus_message_iter_close_container(&structure, &parameters)
            && dbus_message_iter_open_container(&structure, DBUS_TYPE_ARRAY, "y", &bytes)
            && dbus_message_iter_append_fixed_array(&bytes, DBUS_TYPE_BYTE,
                &encoded_value, encoded_length)
            && dbus_message_iter_close_container(&structure, &bytes)
            && dbus_message_iter_append_basic(&structure, DBUS_TYPE_STRING, &content_type)
            && dbus_message_iter_close_container(parent, &structure);
    archphene_secret_crypto_wipe(iv, sizeof(iv));
    archphene_secret_crypto_wipe(secret_output_scratch,
            sizeof(secret_output_scratch));
    return ok;
}

static dbus_bool_t parse_item_properties(DBusMessageIter *source, char **label,
        SecretAttribute *attributes, size_t *attribute_count) {
    *label = NULL;
    *attribute_count = 0;
    if (dbus_message_iter_get_arg_type(source) != DBUS_TYPE_ARRAY) return FALSE;
    DBusMessageIter array;
    dbus_message_iter_recurse(source, &array);
    while (dbus_message_iter_get_arg_type(&array) != DBUS_TYPE_INVALID) {
        if (dbus_message_iter_get_arg_type(&array) != DBUS_TYPE_DICT_ENTRY) return FALSE;
        DBusMessageIter entry;
        dbus_message_iter_recurse(&array, &entry);
        if (dbus_message_iter_get_arg_type(&entry) != DBUS_TYPE_STRING) return FALSE;
        const char *name = NULL;
        dbus_message_iter_get_basic(&entry, &name);
        if (!dbus_message_iter_next(&entry)
                || dbus_message_iter_get_arg_type(&entry) != DBUS_TYPE_VARIANT) return FALSE;
        DBusMessageIter variant;
        dbus_message_iter_recurse(&entry, &variant);
        if (strcmp(name, ITEM_IFACE ".Label") == 0) {
            if (*label != NULL || dbus_message_iter_get_arg_type(&variant) != DBUS_TYPE_STRING)
                return FALSE;
            const char *value = NULL;
            dbus_message_iter_get_basic(&variant, &value);
            if (value == NULL || strlen(value) > 256 || !dbus_validate_utf8(value, NULL))
                return FALSE;
            *label = strdup(value);
            if (*label == NULL) return FALSE;
        } else if (strcmp(name, ITEM_IFACE ".Attributes") == 0) {
            if (*attribute_count != 0
                    || !parse_attributes_iter(&variant, attributes, attribute_count)) return FALSE;
        }
        dbus_message_iter_next(&array);
    }
    return *label != NULL;
}

static void handle_open_session(DBusConnection *connection, DBusMessage *request) {
    DBusMessageIter input;
    if (!dbus_message_iter_init(request, &input)
            || dbus_message_iter_get_arg_type(&input) != DBUS_TYPE_STRING) {
        send_error(connection, request, DBUS_ERROR_INVALID_ARGS,
                "OpenSession requires an algorithm");
        return;
    }
    const char *algorithm = NULL;
    dbus_message_iter_get_basic(&input, &algorithm);
    if (algorithm == NULL || (strcmp(algorithm, "plain") != 0
            && strcmp(algorithm, "dh-ietf1024-sha256-aes128-cbc-pkcs7") != 0)) {
        send_error(connection, request, ERROR_NOT_SUPPORTED,
                "Secret Service session algorithm is unsupported");
        return;
    }
    if (!dbus_message_iter_next(&input)
            || dbus_message_iter_get_arg_type(&input) != DBUS_TYPE_VARIANT) {
        send_error(connection, request, DBUS_ERROR_INVALID_ARGS,
                "OpenSession arguments are invalid");
        return;
    }
    DBusMessageIter trailing = input;
    if (dbus_message_iter_next(&trailing)) {
        send_error(connection, request, DBUS_ERROR_INVALID_ARGS,
                "OpenSession arguments are invalid");
        return;
    }

    DBusMessageIter argument;
    dbus_message_iter_recurse(&input, &argument);
    dbus_bool_t encrypted = strcmp(algorithm,
            "dh-ietf1024-sha256-aes128-cbc-pkcs7") == 0;
    uint8_t public_value[ARCHPHENE_SECRET_DH_PUBLIC_MAX_BYTES] = {0};
    size_t public_length = 0;
    uint8_t session_key[ARCHPHENE_SECRET_AES_KEY_BYTES] = {0};
    if (!encrypted) {
        const char *plain_input = NULL;
        if (dbus_message_iter_get_arg_type(&argument) != DBUS_TYPE_STRING) {
            send_error(connection, request, DBUS_ERROR_INVALID_ARGS,
                    "Plain session input must be a string");
            return;
        }
        dbus_message_iter_get_basic(&argument, &plain_input);
        if (plain_input == NULL || plain_input[0] != '\0'
                || dbus_message_iter_next(&argument)) {
            send_error(connection, request, DBUS_ERROR_INVALID_ARGS,
                    "Plain session input must be empty");
            return;
        }
    } else {
        if (dbus_message_iter_get_arg_type(&argument) != DBUS_TYPE_ARRAY
                || dbus_message_iter_get_element_type(&argument) != DBUS_TYPE_BYTE) {
            send_error(connection, request, DBUS_ERROR_INVALID_ARGS,
                    "Encrypted session input must be a DH public key");
            return;
        }
        DBusMessageIter peer_array;
        dbus_message_iter_recurse(&argument, &peer_array);
        const uint8_t *peer_public = NULL;
        int peer_length = 0;
        dbus_message_iter_get_fixed_array(&peer_array, &peer_public, &peer_length);
        if (peer_length <= 0
                || archphene_secret_crypto_negotiate(peer_public, (size_t)peer_length,
                    public_value, sizeof(public_value), &public_length,
                    session_key) != 0) {
            send_error(connection, request, DBUS_ERROR_INVALID_ARGS,
                    "Encrypted session DH public key is invalid");
            return;
        }
    }

    SecretSession *session = create_session(dbus_message_get_sender(request));
    if (session == NULL) {
        archphene_secret_crypto_wipe(public_value, sizeof(public_value));
        archphene_secret_crypto_wipe(session_key, sizeof(session_key));
        send_error(connection, request, DBUS_ERROR_LIMITS_EXCEEDED,
                "Secret session limit reached");
        return;
    }
    session->mode = encrypted ? SECRET_SESSION_DH_AES : SECRET_SESSION_PLAIN;
    if (encrypted) memcpy(session->key, session_key, sizeof(session->key));

    DBusMessage *reply = dbus_message_new_method_return(request);
    DBusMessageIter output, variant, public_array;
    const char *empty = "";
    const char *path = session->path;
    dbus_bool_t ok = reply != NULL;
    if (ok) {
        dbus_message_iter_init_append(reply, &output);
        if (encrypted) {
            const uint8_t *public_pointer = public_value;
            ok = dbus_message_iter_open_container(&output, DBUS_TYPE_VARIANT,
                    "ay", &variant)
                    && dbus_message_iter_open_container(&variant, DBUS_TYPE_ARRAY,
                        "y", &public_array)
                    && dbus_message_iter_append_fixed_array(&public_array,
                        DBUS_TYPE_BYTE, &public_pointer, (int)public_length)
                    && dbus_message_iter_close_container(&variant, &public_array)
                    && dbus_message_iter_close_container(&output, &variant);
        } else {
            ok = dbus_message_iter_open_container(&output, DBUS_TYPE_VARIANT,
                    "s", &variant)
                    && dbus_message_iter_append_basic(&variant,
                        DBUS_TYPE_STRING, &empty)
                    && dbus_message_iter_close_container(&output, &variant);
        }
        ok = ok && dbus_message_iter_append_basic(&output,
                DBUS_TYPE_OBJECT_PATH, &path);
    }
    archphene_secret_crypto_wipe(public_value, sizeof(public_value));
    archphene_secret_crypto_wipe(session_key, sizeof(session_key));
    if (!ok) {
        if (reply != NULL) dbus_message_unref(reply);
        clear_session(session);
        return;
    }
    send_message(connection, reply);
}
static void send_search_reply(DBusConnection *connection, DBusMessage *request,
        const SecretAttribute *query, size_t query_count, dbus_bool_t service_reply) {
    SecretCatalog catalog;
    if (load_catalog(&catalog) != 0) {
        send_error(connection, request, DBUS_ERROR_FAILED, "Encrypted secret catalog unavailable");
        return;
    }
    char paths[MAX_ITEMS][1200];
    const char *matches[MAX_ITEMS];
    size_t count = 0;
    for (size_t index = 0; index < catalog.count; index++) {
        if (attributes_match(&catalog.items[index], query, query_count)
                && item_path(catalog.items[index].id, paths[count], sizeof(paths[count]))) {
            matches[count] = paths[count];
            count++;
        }
    }
    DBusMessage *reply = dbus_message_new_method_return(request);
    DBusMessageIter output;
    if (reply != NULL) {
        dbus_message_iter_init_append(reply, &output);
        dbus_bool_t ok = append_object_paths(&output, matches, count);
        if (service_reply) ok = ok && append_object_paths(&output, NULL, 0);
        if (ok) send_message(connection, reply);
        else dbus_message_unref(reply);
    }
    free_catalog(&catalog);
}

static void handle_search(DBusConnection *connection, DBusMessage *request,
        dbus_bool_t service_reply) {
    SecretAttribute query[MAX_ATTRIBUTES] = {{0}};
    size_t count = 0;
    if (!parse_attributes(request, query, &count)) {
        free_attributes(query, count);
        send_error(connection, request, DBUS_ERROR_INVALID_ARGS, "Search attributes are invalid");
        return;
    }
    send_search_reply(connection, request, query, count, service_reply);
    free_attributes(query, count);
}

static void handle_read_alias(DBusConnection *connection, DBusMessage *request) {
    const char *alias = NULL;
    DBusError error;
    dbus_error_init(&error);
    if (!dbus_message_get_args(request, &error, DBUS_TYPE_STRING, &alias, DBUS_TYPE_INVALID)) {
        send_error(connection, request, DBUS_ERROR_INVALID_ARGS, "ReadAlias requires one alias");
        dbus_error_free(&error);
        return;
    }
    const char *path = strcmp(alias, "default") == 0 || strcmp(alias, "login") == 0
            || strcmp(alias, "session") == 0 ? COLLECTION_PATH : "/";
    DBusMessage *reply = dbus_message_new_method_return(request);
    if (reply == NULL || !dbus_message_append_args(reply,
            DBUS_TYPE_OBJECT_PATH, &path, DBUS_TYPE_INVALID)) {
        if (reply != NULL) dbus_message_unref(reply);
        return;
    }
    send_message(connection, reply);
}

static void handle_set_alias(DBusConnection *connection, DBusMessage *request) {
    const char *alias = NULL;
    const char *path = NULL;
    DBusError error;
    dbus_error_init(&error);
    if (!dbus_message_get_args(request, &error, DBUS_TYPE_STRING, &alias,
            DBUS_TYPE_OBJECT_PATH, &path, DBUS_TYPE_INVALID)) {
        send_error(connection, request, DBUS_ERROR_INVALID_ARGS, "SetAlias arguments are invalid");
        dbus_error_free(&error);
        return;
    }
    if ((strcmp(alias, "default") != 0 && strcmp(alias, "login") != 0
            && strcmp(alias, "session") != 0)
            || (strcmp(path, COLLECTION_PATH) != 0 && strcmp(path, "/") != 0)) {
        send_error(connection, request, ERROR_NOT_SUPPORTED,
                "Archphene exposes one always-unlocked login collection");
        return;
    }
    send_empty_reply(connection, request);
}

static void handle_lock_state(DBusConnection *connection, DBusMessage *request,
        dbus_bool_t unlock) {
    DBusMessageIter input;
    if (!dbus_message_iter_init(request, &input)
            || dbus_message_iter_get_arg_type(&input) != DBUS_TYPE_ARRAY) {
        send_error(connection, request, DBUS_ERROR_INVALID_ARGS, "Lock requires object paths");
        return;
    }
    const char *accepted[MAX_ITEMS + 1];
    size_t count = 0;
    if (unlock) {
        DBusMessageIter array;
        dbus_message_iter_recurse(&input, &array);
        while (dbus_message_iter_get_arg_type(&array) != DBUS_TYPE_INVALID
                && count < MAX_ITEMS + 1) {
            if (dbus_message_iter_get_arg_type(&array) != DBUS_TYPE_OBJECT_PATH) {
                send_error(connection, request, DBUS_ERROR_INVALID_ARGS, "Invalid object path");
                return;
            }
            const char *path = NULL;
            dbus_message_iter_get_basic(&array, &path);
            accepted[count++] = path;
            dbus_message_iter_next(&array);
        }
    }
    DBusMessage *reply = dbus_message_new_method_return(request);
    DBusMessageIter output;
    const char *prompt = "/";
    if (reply == NULL) return;
    dbus_message_iter_init_append(reply, &output);
    if (!append_object_paths(&output, accepted, count)
            || !dbus_message_iter_append_basic(&output, DBUS_TYPE_OBJECT_PATH, &prompt)) {
        dbus_message_unref(reply);
        return;
    }
    send_message(connection, reply);
}

static void handle_create_item(DBusConnection *connection, DBusMessage *request) {
    DBusMessageIter input;
    SecretAttribute attributes[MAX_ATTRIBUTES] = {{0}};
    size_t attribute_count = 0;
    char *label = NULL;
    if (!dbus_message_iter_init(request, &input)
            || !parse_item_properties(&input, &label, attributes, &attribute_count)
            || !dbus_message_iter_next(&input)) {
        free(label); free_attributes(attributes, attribute_count);
        send_error(connection, request, DBUS_ERROR_INVALID_ARGS, "CreateItem properties are invalid");
        return;
    }
    const uint8_t *value = NULL;
    int length = 0;
    const char *content_type = NULL;
    const char *sender = dbus_message_get_sender(request);
    if (!parse_secret_iter(&input, sender, &value, &length, &content_type)
            || !dbus_message_iter_next(&input)
            || dbus_message_iter_get_arg_type(&input) != DBUS_TYPE_BOOLEAN) {
        free(label); free_attributes(attributes, attribute_count);
        send_error(connection, request, ERROR_NO_SESSION, "CreateItem secret session is invalid");
        return;
    }
    dbus_bool_t replace = FALSE;
    dbus_message_iter_get_basic(&input, &replace);
    SecretCatalog catalog;
    if (load_catalog(&catalog) != 0) {
        free(label); free_attributes(attributes, attribute_count);
        send_error(connection, request, DBUS_ERROR_FAILED, "Encrypted secret catalog unavailable");
        return;
    }
    const char *id = NULL;
    dbus_bool_t replaced = FALSE;
    if (replace) {
        for (size_t index = 0; index < catalog.count; index++) {
            if (attributes_equal(&catalog.items[index], attributes, attribute_count)) {
                id = catalog.items[index].id;
                replaced = TRUE;
                break;
            }
        }
    }
    char generated[33];
    if (id == NULL) {
        if (!random_id(generated)) {
            free_catalog(&catalog); free(label); free_attributes(attributes, attribute_count);
            send_error(connection, request, DBUS_ERROR_FAILED, "Could not generate secret identity");
            return;
        }
        id = generated;
    }
    char path[1200];
    int result = item_path(id, path, sizeof(path))
            ? store_secret_value(id, label, attributes, attribute_count, value, length,
                    content_type) : -1;
    if (result != 0) {
        free_catalog(&catalog); free(label); free_attributes(attributes, attribute_count);
        send_error(connection, request, DBUS_ERROR_FAILED, "Could not persist encrypted secret");
        return;
    }
    DBusMessage *reply = dbus_message_new_method_return(request);
    const char *item = path;
    const char *prompt = "/";
    if (reply != NULL && dbus_message_append_args(reply,
            DBUS_TYPE_OBJECT_PATH, &item, DBUS_TYPE_OBJECT_PATH, &prompt, DBUS_TYPE_INVALID))
        send_message(connection, reply);
    else if (reply != NULL) dbus_message_unref(reply);
    emit_item_signal(connection, replaced ? "ItemChanged" : "ItemCreated", path);
    free_catalog(&catalog); free(label); free_attributes(attributes, attribute_count);
}

static void handle_get_secret(DBusConnection *connection, DBusMessage *request,
        SecretMetadata *item) {
    const char *session_path = NULL;
    DBusError error;
    dbus_error_init(&error);
    if (!dbus_message_get_args(request, &error, DBUS_TYPE_OBJECT_PATH,
            &session_path, DBUS_TYPE_INVALID)
            || find_session(session_path, dbus_message_get_sender(request)) == NULL) {
        send_error(connection, request, ERROR_NO_SESSION, "Secret session is invalid");
        dbus_error_free(&error);
        return;
    }
    int length = 0;
    uint8_t *value = read_secret_value(item, &length);
    if (value == NULL) {
        send_error(connection, request, DBUS_ERROR_FAILED, "Encrypted secret is unavailable");
        return;
    }
    DBusMessage *reply = dbus_message_new_method_return(request);
    DBusMessageIter output;
    if (reply != NULL) {
        dbus_message_iter_init_append(reply, &output);
        if (append_secret(&output, session_path, value, length, item->content_type))
            send_message(connection, reply);
        else dbus_message_unref(reply);
    }
    memset(value, 0, (size_t)length);
    free(value);
}

static void handle_set_secret(DBusConnection *connection, DBusMessage *request,
        SecretMetadata *item) {
    DBusMessageIter input;
    const uint8_t *value = NULL;
    int length = 0;
    const char *content_type = NULL;
    if (!dbus_message_iter_init(request, &input)
            || !parse_secret_iter(&input, dbus_message_get_sender(request),
                &value, &length, &content_type)
            || dbus_message_iter_next(&input)
            || store_secret_value(item->id, item->label, item->attributes,
                item->attribute_count, value, length, content_type) != 0) {
        send_error(connection, request, ERROR_NO_SESSION, "SetSecret failed or session is invalid");
        return;
    }
    send_empty_reply(connection, request);
    emit_item_signal(connection, "ItemChanged", dbus_message_get_path(request));
}

static void handle_delete_item(DBusConnection *connection, DBusMessage *request,
        SecretMetadata *item) {
    char response[256] = {0};
    if (archphene_android_delete_secret(item->id, response, sizeof(response)) != 0) {
        send_error(connection, request, DBUS_ERROR_FAILED, "Could not delete encrypted secret");
        return;
    }
    const char *prompt = "/";
    DBusMessage *reply = dbus_message_new_method_return(request);
    if (reply != NULL && dbus_message_append_args(reply,
            DBUS_TYPE_OBJECT_PATH, &prompt, DBUS_TYPE_INVALID)) send_message(connection, reply);
    else if (reply != NULL) dbus_message_unref(reply);
    emit_item_signal(connection, "ItemDeleted", dbus_message_get_path(request));
}


static dbus_bool_t append_variant_string(DBusMessageIter *parent, const char *value) {
    DBusMessageIter variant;
    return dbus_message_iter_open_container(parent, DBUS_TYPE_VARIANT, "s", &variant)
            && dbus_message_iter_append_basic(&variant, DBUS_TYPE_STRING, &value)
            && dbus_message_iter_close_container(parent, &variant);
}

static dbus_bool_t append_variant_bool(DBusMessageIter *parent, dbus_bool_t value) {
    DBusMessageIter variant;
    return dbus_message_iter_open_container(parent, DBUS_TYPE_VARIANT, "b", &variant)
            && dbus_message_iter_append_basic(&variant, DBUS_TYPE_BOOLEAN, &value)
            && dbus_message_iter_close_container(parent, &variant);
}

static dbus_bool_t append_variant_u64(DBusMessageIter *parent, uint64_t value) {
    DBusMessageIter variant;
    return dbus_message_iter_open_container(parent, DBUS_TYPE_VARIANT, "t", &variant)
            && dbus_message_iter_append_basic(&variant, DBUS_TYPE_UINT64, &value)
            && dbus_message_iter_close_container(parent, &variant);
}

static dbus_bool_t append_variant_attributes(DBusMessageIter *parent,
        const SecretAttribute *attributes, size_t count) {
    DBusMessageIter variant;
    return dbus_message_iter_open_container(parent, DBUS_TYPE_VARIANT, "a{ss}", &variant)
            && append_attributes(&variant, attributes, count)
            && dbus_message_iter_close_container(parent, &variant);
}

static dbus_bool_t append_variant_paths(DBusMessageIter *parent,
        const char *const *paths, size_t count) {
    DBusMessageIter variant;
    return dbus_message_iter_open_container(parent, DBUS_TYPE_VARIANT, "ao", &variant)
            && append_object_paths(&variant, paths, count)
            && dbus_message_iter_close_container(parent, &variant);
}

static dbus_bool_t open_property(DBusMessageIter *array, DBusMessageIter *entry,
        const char *name) {
    return dbus_message_iter_open_container(array, DBUS_TYPE_DICT_ENTRY, NULL, entry)
            && dbus_message_iter_append_basic(entry, DBUS_TYPE_STRING, &name);
}

static dbus_bool_t close_property(DBusMessageIter *array, DBusMessageIter *entry) {
    return dbus_message_iter_close_container(array, entry);
}

static dbus_bool_t append_requested_property(DBusMessageIter *output,
        const char *path, const char *interface, const char *property,
        SecretCatalog *catalog, SecretMetadata *item) {
    if (strcmp(path, SERVICE_PATH) == 0 && strcmp(interface, SERVICE_IFACE) == 0
            && strcmp(property, "Collections") == 0) {
        const char *paths[] = { COLLECTION_PATH };
        return append_variant_paths(output, paths, 1);
    }
    if (is_collection_path(path) && strcmp(interface, COLLECTION_IFACE) == 0) {
        if (strcmp(property, "Items") == 0) {
            char encoded[MAX_ITEMS][1200];
            const char *paths[MAX_ITEMS];
            size_t count = 0;
            for (size_t index = 0; index < catalog->count; index++) {
                if (item_path(catalog->items[index].id, encoded[count], sizeof(encoded[count]))) {
                    paths[count] = encoded[count];
                    count++;
                }
            }
            return append_variant_paths(output, paths, count);
        }
        if (strcmp(property, "Label") == 0) return append_variant_string(output, "Login");
        if (strcmp(property, "Locked") == 0) return append_variant_bool(output, FALSE);
        if (strcmp(property, "Created") == 0 || strcmp(property, "Modified") == 0)
            return append_variant_u64(output, 0);
    }
    if (item != NULL && strcmp(interface, ITEM_IFACE) == 0) {
        if (strcmp(property, "Locked") == 0) return append_variant_bool(output, FALSE);
        if (strcmp(property, "Attributes") == 0)
            return append_variant_attributes(output, item->attributes, item->attribute_count);
        if (strcmp(property, "Label") == 0) return append_variant_string(output, item->label);
        if (strcmp(property, "Created") == 0 || strcmp(property, "Modified") == 0)
            return append_variant_u64(output, 0);
    }
    return FALSE;
}

static void handle_properties_get(DBusConnection *connection, DBusMessage *request,
        SecretCatalog *catalog, SecretMetadata *item) {
    const char *interface = NULL;
    const char *property = NULL;
    DBusError error;
    dbus_error_init(&error);
    if (!dbus_message_get_args(request, &error, DBUS_TYPE_STRING, &interface,
            DBUS_TYPE_STRING, &property, DBUS_TYPE_INVALID)) {
        send_error(connection, request, DBUS_ERROR_INVALID_ARGS, "Property request is invalid");
        dbus_error_free(&error);
        return;
    }
    DBusMessage *reply = dbus_message_new_method_return(request);
    DBusMessageIter output;
    if (reply == NULL) return;
    dbus_message_iter_init_append(reply, &output);
    if (!append_requested_property(&output, dbus_message_get_path(request),
            interface, property, catalog, item)) {
        dbus_message_unref(reply);
        send_error(connection, request, DBUS_ERROR_UNKNOWN_PROPERTY, "Unknown secret property");
        return;
    }
    send_message(connection, reply);
}

static dbus_bool_t append_property_named(DBusMessageIter *array, const char *name,
        const char *path, const char *interface, SecretCatalog *catalog,
        SecretMetadata *item) {
    DBusMessageIter entry;
    return open_property(array, &entry, name)
            && append_requested_property(&entry, path, interface, name, catalog, item)
            && close_property(array, &entry);
}

static void handle_properties_get_all(DBusConnection *connection, DBusMessage *request,
        SecretCatalog *catalog, SecretMetadata *item) {
    const char *interface = NULL;
    DBusError error;
    dbus_error_init(&error);
    if (!dbus_message_get_args(request, &error, DBUS_TYPE_STRING,
            &interface, DBUS_TYPE_INVALID)) {
        send_error(connection, request, DBUS_ERROR_INVALID_ARGS, "GetAll request is invalid");
        dbus_error_free(&error);
        return;
    }
    const char *path = dbus_message_get_path(request);
    const char *const *names = NULL;
    size_t count = 0;
    static const char *service_names[] = {"Collections"};
    static const char *collection_names[] = {"Items", "Label", "Locked", "Created", "Modified"};
    static const char *item_names[] = {"Locked", "Attributes", "Label", "Created", "Modified"};
    if (strcmp(path, SERVICE_PATH) == 0 && strcmp(interface, SERVICE_IFACE) == 0) {
        names = service_names; count = sizeof(service_names) / sizeof(service_names[0]);
    } else if (is_collection_path(path) && strcmp(interface, COLLECTION_IFACE) == 0) {
        names = collection_names; count = sizeof(collection_names) / sizeof(collection_names[0]);
    } else if (item != NULL && strcmp(interface, ITEM_IFACE) == 0) {
        names = item_names; count = sizeof(item_names) / sizeof(item_names[0]);
    } else {
        send_error(connection, request, DBUS_ERROR_UNKNOWN_INTERFACE, "Unknown secret interface");
        return;
    }
    DBusMessage *reply = dbus_message_new_method_return(request);
    DBusMessageIter output, array;
    if (reply == NULL) return;
    dbus_message_iter_init_append(reply, &output);
    dbus_bool_t ok = dbus_message_iter_open_container(&output,
            DBUS_TYPE_ARRAY, "{sv}", &array);
    for (size_t index = 0; ok && index < count; index++)
        ok = append_property_named(&array, names[index], path, interface, catalog, item);
    ok = ok && dbus_message_iter_close_container(&output, &array);
    if (ok) send_message(connection, reply);
    else dbus_message_unref(reply);
}

static void handle_properties_set(DBusConnection *connection, DBusMessage *request,
        SecretMetadata *item) {
    DBusMessageIter input;
    if (!dbus_message_iter_init(request, &input)
            || dbus_message_iter_get_arg_type(&input) != DBUS_TYPE_STRING) {
        send_error(connection, request, DBUS_ERROR_INVALID_ARGS, "Property Set is invalid");
        return;
    }
    const char *interface = NULL;
    dbus_message_iter_get_basic(&input, &interface);
    if (!dbus_message_iter_next(&input)
            || dbus_message_iter_get_arg_type(&input) != DBUS_TYPE_STRING) {
        send_error(connection, request, DBUS_ERROR_INVALID_ARGS, "Property Set is invalid");
        return;
    }
    const char *property = NULL;
    dbus_message_iter_get_basic(&input, &property);
    if (!dbus_message_iter_next(&input)
            || dbus_message_iter_get_arg_type(&input) != DBUS_TYPE_VARIANT) {
        send_error(connection, request, DBUS_ERROR_INVALID_ARGS, "Property Set value is invalid");
        return;
    }
    DBusMessageIter variant;
    dbus_message_iter_recurse(&input, &variant);
    if (strcmp(interface, COLLECTION_IFACE) == 0 && strcmp(property, "Label") == 0
            && dbus_message_iter_get_arg_type(&variant) == DBUS_TYPE_STRING) {
        send_empty_reply(connection, request);
        return;
    }
    if (item == NULL || strcmp(interface, ITEM_IFACE) != 0) {
        send_error(connection, request, DBUS_ERROR_PROPERTY_READ_ONLY,
                "Secret property cannot be changed");
        return;
    }
    const char *label = item->label;
    SecretAttribute replacement[MAX_ATTRIBUTES] = {{0}};
    size_t replacement_count = 0;
    if (strcmp(property, "Label") == 0
            && dbus_message_iter_get_arg_type(&variant) == DBUS_TYPE_STRING) {
        dbus_message_iter_get_basic(&variant, &label);
        if (label == NULL || strlen(label) > 256 || !dbus_validate_utf8(label, NULL)) {
            send_error(connection, request, DBUS_ERROR_INVALID_ARGS, "Secret label is invalid");
            return;
        }
    } else if (strcmp(property, "Attributes") == 0
            && parse_attributes_iter(&variant, replacement, &replacement_count)) {
    } else {
        free_attributes(replacement, replacement_count);
        send_error(connection, request, DBUS_ERROR_PROPERTY_READ_ONLY,
                "Secret property cannot be changed");
        return;
    }
    const SecretAttribute *attributes = replacement_count > 0
            || strcmp(property, "Attributes") == 0 ? replacement : item->attributes;
    size_t attribute_count = strcmp(property, "Attributes") == 0
            ? replacement_count : item->attribute_count;
    int length = 0;
    uint8_t *value = read_secret_value(item, &length);
    int result = value == NULL ? -1 : store_secret_value(item->id, label,
            attributes, attribute_count, value, length, item->content_type);
    if (value != NULL) { memset(value, 0, (size_t)length); free(value); }
    free_attributes(replacement, replacement_count);
    if (result != 0) {
        send_error(connection, request, DBUS_ERROR_FAILED, "Could not update encrypted secret");
        return;
    }
    send_empty_reply(connection, request);
    emit_item_signal(connection, "ItemChanged", dbus_message_get_path(request));
}

static void handle_get_secrets(DBusConnection *connection, DBusMessage *request,
        SecretCatalog *catalog) {
    DBusMessageIter input;
    if (!dbus_message_iter_init(request, &input)
            || dbus_message_iter_get_arg_type(&input) != DBUS_TYPE_ARRAY) {
        send_error(connection, request, DBUS_ERROR_INVALID_ARGS, "GetSecrets paths are invalid");
        return;
    }
    DBusMessageIter paths;
    dbus_message_iter_recurse(&input, &paths);
    if (!dbus_message_iter_next(&input)
            || dbus_message_iter_get_arg_type(&input) != DBUS_TYPE_OBJECT_PATH) {
        send_error(connection, request, DBUS_ERROR_INVALID_ARGS, "GetSecrets session is invalid");
        return;
    }
    const char *session_path = NULL;
    dbus_message_iter_get_basic(&input, &session_path);
    if (find_session(session_path, dbus_message_get_sender(request)) == NULL) {
        send_error(connection, request, ERROR_NO_SESSION, "Secret session is invalid");
        return;
    }
    DBusMessage *reply = dbus_message_new_method_return(request);
    DBusMessageIter output, array;
    if (reply == NULL) return;
    dbus_message_iter_init_append(reply, &output);
    dbus_bool_t ok = dbus_message_iter_open_container(&output,
            DBUS_TYPE_ARRAY, "{o(oayays)}", &array);
    size_t count = 0;
    while (ok && dbus_message_iter_get_arg_type(&paths) != DBUS_TYPE_INVALID
            && count++ < MAX_ITEMS) {
        if (dbus_message_iter_get_arg_type(&paths) != DBUS_TYPE_OBJECT_PATH) { ok = FALSE; break; }
        const char *path = NULL;
        dbus_message_iter_get_basic(&paths, &path);
        SecretMetadata *item = find_item(catalog, path);
        if (item != NULL) {
            int length = 0;
            uint8_t *value = read_secret_value(item, &length);
            DBusMessageIter entry;
            ok = value != NULL
                    && dbus_message_iter_open_container(&array, DBUS_TYPE_DICT_ENTRY, NULL, &entry)
                    && dbus_message_iter_append_basic(&entry, DBUS_TYPE_OBJECT_PATH, &path)
                    && append_secret(&entry, session_path, value, length, item->content_type)
                    && dbus_message_iter_close_container(&array, &entry);
            if (value != NULL) { memset(value, 0, (size_t)length); free(value); }
        }
        dbus_message_iter_next(&paths);
    }
    ok = ok && dbus_message_iter_close_container(&output, &array);
    if (ok) send_message(connection, reply);
    else {
        dbus_message_unref(reply);
        send_error(connection, request, DBUS_ERROR_FAILED, "Could not read encrypted secrets");
    }
}

void archphene_secret_service_handle_signal(DBusMessage *message) {
    if (!dbus_message_is_signal(message, "org.freedesktop.DBus", "NameOwnerChanged")) return;
    const char *name = NULL;
    const char *old_owner = NULL;
    const char *new_owner = NULL;
    DBusError error;
    dbus_error_init(&error);
    if (!dbus_message_get_args(message, &error,
            DBUS_TYPE_STRING, &name, DBUS_TYPE_STRING, &old_owner,
            DBUS_TYPE_STRING, &new_owner, DBUS_TYPE_INVALID)) {
        dbus_error_free(&error);
        return;
    }
    if (name == NULL || name[0] != ':' || new_owner == NULL || new_owner[0] != '\0') return;
    for (size_t index = 0; index < sizeof(sessions) / sizeof(sessions[0]); index++) {
        if (sessions[index].active
                && (strcmp(sessions[index].sender, name) == 0
                    || (old_owner != NULL && strcmp(sessions[index].sender, old_owner) == 0)))
            clear_session(&sessions[index]);
    }
}
const char *archphene_secret_service_introspection(const char *path) {
    if (path == NULL) return NULL;
    if (strcmp(path, SERVICE_PATH) == 0) return service_xml;
    if (is_collection_path(path)) return collection_xml;
    if (strncmp(path, ITEM_PREFIX, strlen(ITEM_PREFIX)) == 0) return item_xml;
    if (strncmp(path, SESSION_PREFIX, strlen(SESSION_PREFIX)) == 0) return session_xml;
    return NULL;
}

int archphene_secret_service_own_name(DBusConnection *connection, DBusError *error) {
    int result = dbus_bus_request_name(
            connection, SECRET_NAME, DBUS_NAME_FLAG_DO_NOT_QUEUE, error);
    return result == DBUS_REQUEST_NAME_REPLY_PRIMARY_OWNER ? 0 : -1;
}


dbus_bool_t archphene_secret_service_handles(
        DBusConnection *connection, DBusMessage *message) {
    const char *path = dbus_message_get_path(message);
    if (path == NULL || (strcmp(path, SERVICE_PATH) != 0
            && !is_collection_path(path)
            && strncmp(path, ITEM_PREFIX, strlen(ITEM_PREFIX)) != 0
            && strncmp(path, SESSION_PREFIX, strlen(SESSION_PREFIX)) != 0)) return FALSE;

    const char *interface = dbus_message_get_interface(message);
    const char *member = dbus_message_get_member(message);
    if (interface == NULL || member == NULL) {
        send_error(connection, message, DBUS_ERROR_INVALID_ARGS, "Secret request is incomplete");
        return TRUE;
    }

    if (getenv("ARCHPHENE_SECRET_TRACE") != NULL) {
        fprintf(stderr, "secret request interface=%s member=%s path=%s\n",
                interface, member, path);
        fflush(stderr);
    }

    SecretCatalog catalog;
    dbus_bool_t catalog_loaded = FALSE;
    SecretMetadata *item = NULL;
    if (is_collection_path(path)
            || strncmp(path, ITEM_PREFIX, strlen(ITEM_PREFIX)) == 0
            || strcmp(interface, PROPERTIES_IFACE) == 0
            || (strcmp(path, SERVICE_PATH) == 0 && strcmp(member, "GetSecrets") == 0)) {
        if (load_catalog(&catalog) != 0) {
            send_error(connection, message, DBUS_ERROR_FAILED,
                    "Encrypted secret catalog unavailable");
            return TRUE;
        }
        catalog_loaded = TRUE;
        if (strncmp(path, ITEM_PREFIX, strlen(ITEM_PREFIX)) == 0) {
            item = find_item(&catalog, path);
            if (item == NULL) {
                free_catalog(&catalog);
                send_error(connection, message, DBUS_ERROR_UNKNOWN_OBJECT,
                        "Secret item does not exist");
                return TRUE;
            }
        }
    }

    if (strcmp(interface, PROPERTIES_IFACE) == 0 && strcmp(member, "Get") == 0) {
        handle_properties_get(connection, message, &catalog, item);
    } else if (strcmp(interface, PROPERTIES_IFACE) == 0 && strcmp(member, "GetAll") == 0) {
        handle_properties_get_all(connection, message, &catalog, item);
    } else if (strcmp(interface, PROPERTIES_IFACE) == 0 && strcmp(member, "Set") == 0) {
        handle_properties_set(connection, message, item);
    } else if (strcmp(path, SERVICE_PATH) == 0 && strcmp(interface, SERVICE_IFACE) == 0) {
        if (strcmp(member, "OpenSession") == 0) {
            handle_open_session(connection, message);
        } else if (strcmp(member, "SearchItems") == 0) {
            handle_search(connection, message, TRUE);
        } else if (strcmp(member, "ReadAlias") == 0) {
            handle_read_alias(connection, message);
        } else if (strcmp(member, "SetAlias") == 0) {
            handle_set_alias(connection, message);
        } else if (strcmp(member, "Unlock") == 0) {
            handle_lock_state(connection, message, TRUE);
        } else if (strcmp(member, "Lock") == 0) {
            handle_lock_state(connection, message, FALSE);
        } else if (strcmp(member, "GetSecrets") == 0) {
            handle_get_secrets(connection, message, &catalog);
        } else if (strcmp(member, "CreateCollection") == 0) {
            const char *collection = COLLECTION_PATH;
            const char *prompt = "/";
            DBusMessage *reply = dbus_message_new_method_return(message);
            if (reply != NULL && dbus_message_append_args(reply,
                    DBUS_TYPE_OBJECT_PATH, &collection,
                    DBUS_TYPE_OBJECT_PATH, &prompt, DBUS_TYPE_INVALID))
                send_message(connection, reply);
            else if (reply != NULL) dbus_message_unref(reply);
        } else {
            send_error(connection, message, DBUS_ERROR_UNKNOWN_METHOD,
                    "Unknown Secret Service method");
        }
    } else if (is_collection_path(path)
            && strcmp(interface, COLLECTION_IFACE) == 0) {
        if (strcmp(member, "SearchItems") == 0) {
            handle_search(connection, message, FALSE);
        } else if (strcmp(member, "CreateItem") == 0) {
            handle_create_item(connection, message);
        } else if (strcmp(member, "Delete") == 0) {
            send_error(connection, message, ERROR_NOT_SUPPORTED,
                    "The Archphene login collection cannot be deleted");
        } else {
            send_error(connection, message, DBUS_ERROR_UNKNOWN_METHOD,
                    "Unknown Secret Collection method");
        }
    } else if (item != NULL && strcmp(interface, ITEM_IFACE) == 0) {
        if (strcmp(member, "GetSecret") == 0) {
            handle_get_secret(connection, message, item);
        } else if (strcmp(member, "SetSecret") == 0) {
            handle_set_secret(connection, message, item);
        } else if (strcmp(member, "Delete") == 0) {
            handle_delete_item(connection, message, item);
        } else {
            send_error(connection, message, DBUS_ERROR_UNKNOWN_METHOD,
                    "Unknown Secret Item method");
        }
    } else if (strncmp(path, SESSION_PREFIX, strlen(SESSION_PREFIX)) == 0
            && strcmp(interface, SESSION_IFACE) == 0 && strcmp(member, "Close") == 0) {
        SecretSession *session = find_session(path, dbus_message_get_sender(message));
        if (session == NULL) {
            send_error(connection, message, ERROR_NO_SESSION, "Secret session is invalid");
        } else {
            clear_session(session);
            send_empty_reply(connection, message);
        }
    } else {
        send_error(connection, message, DBUS_ERROR_UNKNOWN_INTERFACE,
                "Unknown Secret Service interface");
    }

    if (catalog_loaded) free_catalog(&catalog);
    archphene_secret_crypto_wipe(secret_input_scratch,
            sizeof(secret_input_scratch));
    archphene_secret_crypto_wipe(secret_output_scratch,
            sizeof(secret_output_scratch));
    return TRUE;
}
