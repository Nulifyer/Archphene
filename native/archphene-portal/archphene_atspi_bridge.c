#include "archphene_atspi_bridge.h"
#include "archphene_atspi_translator.h"

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define A11Y_BUS "org.a11y.Bus"
#define A11Y_BUS_PATH "/org/a11y/bus"
#define A11Y_STATUS "org.a11y.Status"
#define A11Y_APPLICATION "org.a11y.atspi.Application"
#define A11Y_REGISTRY "org.a11y.atspi.Registry"
#define A11Y_REGISTRY_PATH "/org/a11y/atspi/registry"
#define A11Y_SOCKET "org.a11y.atspi.Socket"
#define A11Y_CACHE "org.a11y.atspi.Cache"
#define A11Y_CACHE_PATH "/org/a11y/atspi/cache"
#define PROPERTIES "org.freedesktop.DBus.Properties"
#define INTROSPECTABLE "org.freedesktop.DBus.Introspectable"
#define REGISTRY_ROOT "/org/a11y/atspi/accessible/root"
#define CACHE_QUERY_MAX 8
#define APPLICATION_ID_QUERY_MAX 16

static dbus_uint32_t cache_queries[CACHE_QUERY_MAX];
static size_t cache_query_count;
static dbus_uint32_t application_id_queries[APPLICATION_ID_QUERY_MAX];
static size_t application_id_query_count;

static const char bus_xml[] =
        "<node>"
        "<interface name='org.a11y.Bus'>"
        "<method name='GetAddress'><arg type='s' direction='out'/></method>"
        "<property name='IsEnabled' type='b' access='readwrite'/>"
        "</interface>"
        "<interface name='org.a11y.Status'>"
        "<property name='IsEnabled' type='b' access='readwrite'/>"
        "<property name='ScreenReaderEnabled' type='b' access='readwrite'/>"
        "</interface>"
        "<interface name='org.freedesktop.DBus.Properties'>"
        "<method name='Get'><arg type='s' direction='in'/>"
        "<arg type='s' direction='in'/><arg type='v' direction='out'/></method>"
        "<method name='GetAll'><arg type='s' direction='in'/>"
        "<arg type='a{sv}' direction='out'/></method>"
        "<method name='Set'><arg type='s' direction='in'/>"
        "<arg type='s' direction='in'/><arg type='v' direction='in'/></method>"
        "</interface>"
        "<interface name='org.freedesktop.DBus.Introspectable'>"
        "<method name='Introspect'><arg type='s' direction='out'/></method>"
        "</interface>"
        "</node>";

static const char root_xml[] =
        "<node>"
        "<interface name='org.a11y.atspi.Socket'>"
        "<property name='version' type='u' access='read'/>"
        "<method name='Embed'><arg type='(so)' direction='in'/>"
        "<arg type='(so)' direction='out'/></method>"
        "<method name='Embedded'><arg type='s' direction='in'/></method>"
        "<method name='Unembed'><arg type='(so)' direction='in'/></method>"
        "<signal name='Available'><arg type='(so)'/></signal>"
        "</interface>"
        "<interface name='org.freedesktop.DBus.Properties'>"
        "<method name='Get'><arg type='s' direction='in'/>"
        "<arg type='s' direction='in'/><arg type='v' direction='out'/></method>"
        "<method name='GetAll'><arg type='s' direction='in'/>"
        "<arg type='a{sv}' direction='out'/></method>"
        "<method name='Set'><arg type='s' direction='in'/>"
        "<arg type='s' direction='in'/><arg type='v' direction='in'/></method>"
        "</interface>"
        "<interface name='org.freedesktop.DBus.Introspectable'>"
        "<method name='Introspect'><arg type='s' direction='out'/></method>"
        "</interface>"
        "</node>";

static const char registry_xml[] =
        "<node>"
        "<interface name='org.a11y.atspi.Registry'>"
        "<property name='version' type='u' access='read'/>"
        "<method name='GetRegisteredEvents'><arg type='a(ss)' direction='out'/></method>"
        "<method name='RegisterEvent'><arg type='s' direction='in'/>"
        "<arg type='as' direction='in'/><arg type='s' direction='in'/></method>"
        "<method name='DeregisterEvent'><arg type='s' direction='in'/>"
        "<arg type='s' direction='in'/></method>"
        "<signal name='EventListenerRegistered'>"
        "<arg type='s'/><arg type='s'/><arg type='as'/></signal>"
        "<signal name='EventListenerDeregistered'>"
        "<arg type='s'/><arg type='s'/></signal>"
        "</interface>"
        "<interface name='org.freedesktop.DBus.Properties'>"
        "<method name='Get'><arg type='s' direction='in'/>"
        "<arg type='s' direction='in'/><arg type='v' direction='out'/></method>"
        "<method name='GetAll'><arg type='s' direction='in'/>"
        "<arg type='a{sv}' direction='out'/></method>"
        "<method name='Set'><arg type='s' direction='in'/>"
        "<arg type='s' direction='in'/><arg type='v' direction='in'/></method>"
        "</interface>"
        "<interface name='org.freedesktop.DBus.Introspectable'>"
        "<method name='Introspect'><arg type='s' direction='out'/></method>"
        "</interface>"
        "</node>";
static dbus_bool_t send_owned(DBusConnection *connection, DBusMessage *message) {
    if (message == NULL) return FALSE;
    dbus_bool_t sent = dbus_connection_send(connection, message, NULL);
    dbus_message_unref(message);
    dbus_connection_flush(connection);
    return sent;
}

static void send_error(DBusConnection *connection, DBusMessage *request,
        const char *name, const char *detail) {
    send_owned(connection, dbus_message_new_error(request, name, detail));
}

static void send_empty(DBusConnection *connection, DBusMessage *request) {
    send_owned(connection, dbus_message_new_method_return(request));
}

static dbus_bool_t require_signature(DBusConnection *connection,
        DBusMessage *request, const char *first, const char *second,
        const char *third) {
    const char *actual = dbus_message_get_signature(request);
    if (actual != NULL && (strcmp(actual, first) == 0
            || (second != NULL && strcmp(actual, second) == 0)
            || (third != NULL && strcmp(actual, third) == 0))) {
        return TRUE;
    }
    send_error(connection, request, DBUS_ERROR_INVALID_ARGS,
            "Invalid private AT-SPI method signature");
    return FALSE;
}

static int own_name(DBusConnection *connection, const char *name, DBusError *error) {
    int result = dbus_bus_request_name(
            connection, name, DBUS_NAME_FLAG_DO_NOT_QUEUE, error);
    return result == DBUS_REQUEST_NAME_REPLY_PRIMARY_OWNER ? 0 : -1;
}

static dbus_bool_t append_reference(DBusMessageIter *output,
        const char *bus, const char *path) {
    DBusMessageIter structure;
    return dbus_message_iter_open_container(
                    output, DBUS_TYPE_STRUCT, NULL, &structure)
            && dbus_message_iter_append_basic(
                    &structure, DBUS_TYPE_STRING, &bus)
            && dbus_message_iter_append_basic(
                    &structure, DBUS_TYPE_OBJECT_PATH, &path)
            && dbus_message_iter_close_container(output, &structure);
}

static dbus_bool_t set_application_id(DBusConnection *connection,
        const char *bus, const char *path, int application_id) {
    DBusMessage *request = dbus_message_new_method_call(
            bus, path, PROPERTIES, "Set");
    if (request == NULL) return FALSE;
    const char *interface = A11Y_APPLICATION;
    const char *property = "Id";
    int32_t value = application_id;
    DBusMessageIter output;
    DBusMessageIter variant;
    dbus_message_iter_init_append(request, &output);
    if (!dbus_message_iter_append_basic(
                    &output, DBUS_TYPE_STRING, &interface)
            || !dbus_message_iter_append_basic(
                    &output, DBUS_TYPE_STRING, &property)
            || !dbus_message_iter_open_container(
                    &output, DBUS_TYPE_VARIANT, "i", &variant)
            || !dbus_message_iter_append_basic(
                    &variant, DBUS_TYPE_INT32, &value)
            || !dbus_message_iter_close_container(&output, &variant)) {
        dbus_message_unref(request);
        return FALSE;
    }
    dbus_uint32_t serial = 0;
    dbus_bool_t sent = dbus_connection_send(connection, request, &serial);
    dbus_message_unref(request);
    if (!sent || serial == 0) return FALSE;
    if (application_id_query_count == APPLICATION_ID_QUERY_MAX) {
        memmove(application_id_queries, application_id_queries + 1,
                (APPLICATION_ID_QUERY_MAX - 1)
                        * sizeof(application_id_queries[0]));
        application_id_query_count--;
    }
    application_id_queries[application_id_query_count++] = serial;
    dbus_connection_flush(connection);
    return TRUE;
}

static dbus_bool_t send_available(DBusConnection *connection) {
    const char *owner = A11Y_REGISTRY;
    DBusMessage *signal = dbus_message_new_signal(
            REGISTRY_ROOT, A11Y_SOCKET, "Available");
    if (owner == NULL || signal == NULL) {
        if (signal != NULL) dbus_message_unref(signal);
        return FALSE;
    }
    DBusMessageIter output;
    dbus_message_iter_init_append(signal, &output);
    if (!append_reference(&output, owner, REGISTRY_ROOT)) {
        dbus_message_unref(signal);
        return FALSE;
    }
    return send_owned(connection, signal);
}

static dbus_bool_t request_cache_items(
        DBusConnection *connection, const char *bus) {
    if (connection == NULL || bus == NULL || bus[0] != ':') return FALSE;
    DBusMessage *request = dbus_message_new_method_call(
            bus, A11Y_CACHE_PATH, A11Y_CACHE, "GetItems");
    if (request == NULL) return FALSE;
    dbus_uint32_t serial = 0;
    dbus_bool_t sent = dbus_connection_send(connection, request, &serial);
    dbus_message_unref(request);
    if (!sent || serial == 0) return FALSE;
    if (cache_query_count == CACHE_QUERY_MAX) {
        memmove(cache_queries, cache_queries + 1,
                (CACHE_QUERY_MAX - 1) * sizeof(cache_queries[0]));
        cache_query_count--;
    }
    cache_queries[cache_query_count++] = serial;
    dbus_connection_flush(connection);
    return TRUE;
}

static dbus_bool_t take_cache_query(DBusMessage *message) {
    dbus_uint32_t serial = dbus_message_get_reply_serial(message);
    if (serial == 0) return FALSE;
    for (size_t index = 0; index < cache_query_count; index++) {
        if (cache_queries[index] != serial) continue;
        memmove(&cache_queries[index], &cache_queries[index + 1],
                (cache_query_count - index - 1) * sizeof(cache_queries[0]));
        cache_query_count--;
        return TRUE;
    }
    return FALSE;
}

static dbus_bool_t take_application_id_query(DBusMessage *message) {
    dbus_uint32_t serial = dbus_message_get_reply_serial(message);
    if (serial == 0) return FALSE;
    for (size_t index = 0; index < application_id_query_count; index++) {
        if (application_id_queries[index] != serial) continue;
        memmove(&application_id_queries[index],
                &application_id_queries[index + 1],
                (application_id_query_count - index - 1)
                        * sizeof(application_id_queries[0]));
        application_id_query_count--;
        return TRUE;
    }
    return FALSE;
}

static dbus_bool_t append_boolean_entry(DBusMessageIter *dictionary,
        const char *key) {
    DBusMessageIter entry;
    DBusMessageIter variant;
    dbus_bool_t value = TRUE;
    return dbus_message_iter_open_container(
                    dictionary, DBUS_TYPE_DICT_ENTRY, NULL, &entry)
            && dbus_message_iter_append_basic(
                    &entry, DBUS_TYPE_STRING, &key)
            && dbus_message_iter_open_container(
                    &entry, DBUS_TYPE_VARIANT, "b", &variant)
            && dbus_message_iter_append_basic(
                    &variant, DBUS_TYPE_BOOLEAN, &value)
            && dbus_message_iter_close_container(&entry, &variant)
            && dbus_message_iter_close_container(dictionary, &entry);
}

static dbus_bool_t append_uint_entry(DBusMessageIter *dictionary,
        const char *key, uint32_t value) {
    DBusMessageIter entry;
    DBusMessageIter variant;
    return dbus_message_iter_open_container(
                    dictionary, DBUS_TYPE_DICT_ENTRY, NULL, &entry)
            && dbus_message_iter_append_basic(
                    &entry, DBUS_TYPE_STRING, &key)
            && dbus_message_iter_open_container(
                    &entry, DBUS_TYPE_VARIANT, "u", &variant)
            && dbus_message_iter_append_basic(
                    &variant, DBUS_TYPE_UINT32, &value)
            && dbus_message_iter_close_container(&entry, &variant)
            && dbus_message_iter_close_container(dictionary, &entry);
}
static dbus_bool_t read_property_request(DBusMessage *request,
        const char **interface, const char **property) {
    DBusMessageIter input;
    if (!dbus_message_iter_init(request, &input)
            || dbus_message_iter_get_arg_type(&input) != DBUS_TYPE_STRING) {
        return FALSE;
    }
    dbus_message_iter_get_basic(&input, interface);
    if (property == NULL) return TRUE;
    if (!dbus_message_iter_next(&input)
            || dbus_message_iter_get_arg_type(&input) != DBUS_TYPE_STRING) {
        return FALSE;
    }
    dbus_message_iter_get_basic(&input, property);
    return TRUE;
}

static dbus_bool_t read_boolean_property_value(DBusMessage *request) {
    DBusMessageIter input;
    DBusMessageIter variant;
    if (!dbus_message_iter_init(request, &input)
            || !dbus_message_iter_next(&input)
            || !dbus_message_iter_next(&input)
            || dbus_message_iter_get_arg_type(&input) != DBUS_TYPE_VARIANT) {
        return FALSE;
    }
    dbus_message_iter_recurse(&input, &variant);
    return dbus_message_iter_get_arg_type(&variant) == DBUS_TYPE_BOOLEAN
            && !dbus_message_iter_next(&variant);
}

static dbus_bool_t valid_status_property(
        const char *interface, const char *property) {
    if (interface == NULL || property == NULL) return FALSE;
    if (strcmp(interface, A11Y_BUS) == 0)
        return strcmp(property, "IsEnabled") == 0;
    if (strcmp(interface, A11Y_STATUS) == 0)
        return strcmp(property, "IsEnabled") == 0
                || strcmp(property, "ScreenReaderEnabled") == 0;
    return FALSE;
}

static void handle_properties(DBusConnection *connection, DBusMessage *request) {
    const char *interface = NULL;
    const char *property = NULL;
    if (dbus_message_is_method_call(request, PROPERTIES, "Set")) {
        if (!read_property_request(request, &interface, &property)
                || !valid_status_property(interface, property)) {
            send_error(connection, request, DBUS_ERROR_UNKNOWN_PROPERTY,
                    "Unknown accessibility status property");
            return;
        }
        if (!read_boolean_property_value(request)) {
            send_error(connection, request, DBUS_ERROR_INVALID_ARGS,
                    "Accessibility status properties require a boolean value");
            return;
        }
        send_empty(connection, request);
        return;
    }
    if (!read_property_request(request, &interface, NULL)
            || (strcmp(interface, A11Y_BUS) != 0
                && strcmp(interface, A11Y_STATUS) != 0)) {
        send_error(connection, request, DBUS_ERROR_UNKNOWN_INTERFACE,
                "Unknown accessibility status interface");
        return;
    }

    DBusMessage *reply = dbus_message_new_method_return(request);
    if (reply == NULL) return;
    DBusMessageIter output;
    dbus_message_iter_init_append(reply, &output);
    if (dbus_message_is_method_call(request, PROPERTIES, "Get")) {
        if (!read_property_request(request, &interface, &property)
                || !valid_status_property(interface, property)) {
            dbus_message_unref(reply);
            send_error(connection, request, DBUS_ERROR_UNKNOWN_PROPERTY,
                    "Unknown accessibility status property");
            return;
        }
        DBusMessageIter variant;
        dbus_bool_t value = TRUE;
        if (!dbus_message_iter_open_container(
                        &output, DBUS_TYPE_VARIANT, "b", &variant)
                || !dbus_message_iter_append_basic(
                        &variant, DBUS_TYPE_BOOLEAN, &value)
                || !dbus_message_iter_close_container(&output, &variant)) {
            dbus_message_unref(reply);
            return;
        }
    } else {
        DBusMessageIter dictionary;
        dbus_bool_t ok = dbus_message_iter_open_container(
                &output, DBUS_TYPE_ARRAY, "{sv}", &dictionary);
        ok = ok && append_boolean_entry(&dictionary, "IsEnabled");
        if (strcmp(interface, A11Y_STATUS) == 0) {
            ok = ok && append_boolean_entry(
                    &dictionary, "ScreenReaderEnabled");
        }
        ok = ok && dbus_message_iter_close_container(&output, &dictionary);
        if (!ok) {
            dbus_message_unref(reply);
            return;
        }
    }
    send_owned(connection, reply);
}

static void handle_registry_properties(
        DBusConnection *connection, DBusMessage *request) {
    const char *interface = NULL;
    const char *property = NULL;
    const char *path = dbus_message_get_path(request);
    const char *expected_interface =
            path != NULL && strcmp(path, REGISTRY_ROOT) == 0
            ? A11Y_SOCKET : A11Y_REGISTRY;
    if (!read_property_request(request, &interface, NULL)
            || strcmp(interface, expected_interface) != 0) {
        send_error(connection, request, DBUS_ERROR_UNKNOWN_INTERFACE,
                "Unknown accessibility registry interface");
        return;
    }
    if (dbus_message_is_method_call(request, PROPERTIES, "Set")) {
        send_error(connection, request,
                "org.freedesktop.DBus.Error.PropertyReadOnly",
                "Accessibility registry version is read-only");
        return;
    }
    DBusMessage *reply = dbus_message_new_method_return(request);
    if (reply == NULL) return;
    DBusMessageIter output;
    dbus_message_iter_init_append(reply, &output);
    uint32_t version = 1;
    if (dbus_message_is_method_call(request, PROPERTIES, "Get")) {
        if (!read_property_request(request, &interface, &property)
                || strcmp(property, "version") != 0) {
            dbus_message_unref(reply);
            send_error(connection, request, DBUS_ERROR_UNKNOWN_PROPERTY,
                    "Unknown accessibility registry property");
            return;
        }
        DBusMessageIter variant;
        if (!dbus_message_iter_open_container(
                        &output, DBUS_TYPE_VARIANT, "u", &variant)
                || !dbus_message_iter_append_basic(
                        &variant, DBUS_TYPE_UINT32, &version)
                || !dbus_message_iter_close_container(&output, &variant)) {
            dbus_message_unref(reply);
            return;
        }
    } else {
        DBusMessageIter dictionary;
        if (!dbus_message_iter_open_container(
                        &output, DBUS_TYPE_ARRAY, "{sv}", &dictionary)
                || !append_uint_entry(&dictionary, "version", version)
                || !dbus_message_iter_close_container(&output, &dictionary)) {
            dbus_message_unref(reply);
            return;
        }
    }
    send_owned(connection, reply);
}
static void handle_address(DBusConnection *connection, DBusMessage *request) {
    const char *address = getenv("DBUS_SESSION_BUS_ADDRESS");
    DBusMessage *reply = dbus_message_new_method_return(request);
    if (address == NULL || address[0] == '\0' || reply == NULL
            || !dbus_message_append_args(reply,
                    DBUS_TYPE_STRING, &address, DBUS_TYPE_INVALID)) {
        if (reply != NULL) dbus_message_unref(reply);
        send_error(connection, request, DBUS_ERROR_FAILED,
                "Private accessibility bus address is unavailable");
        return;
    }
    send_owned(connection, reply);
}

static dbus_bool_t read_reference(DBusMessage *request,
        const char **claimed_bus, const char **path) {
    DBusMessageIter input;
    DBusMessageIter structure;
    if (!dbus_message_iter_init(request, &input)
            || dbus_message_iter_get_arg_type(&input) != DBUS_TYPE_STRUCT) {
        return FALSE;
    }
    dbus_message_iter_recurse(&input, &structure);
    if (dbus_message_iter_get_arg_type(&structure) != DBUS_TYPE_STRING) {
        return FALSE;
    }
    dbus_message_iter_get_basic(&structure, claimed_bus);
    if (!dbus_message_iter_next(&structure)
            || dbus_message_iter_get_arg_type(&structure)
                    != DBUS_TYPE_OBJECT_PATH) {
        return FALSE;
    }
    dbus_message_iter_get_basic(&structure, path);
    return TRUE;
}

static void handle_embed(DBusConnection *connection, DBusMessage *request) {
    const char *sender = dbus_message_get_sender(request);
    const char *claimed_bus = NULL;
    const char *path = NULL;
    if (sender == NULL || !read_reference(request, &claimed_bus, &path)
            || path == NULL || (claimed_bus != NULL && claimed_bus[0] != '\0'
                && strcmp(claimed_bus, sender) != 0)) {
        send_error(connection, request, DBUS_ERROR_INVALID_ARGS,
                "Invalid or spoofed application root");
        return;
    }
    int application_id = 0;
    if (archphene_atspi_translator_register(
            sender, path, &application_id) != 0) {
        send_error(connection, request, DBUS_ERROR_LIMITS_EXCEEDED,
                "Accessibility application registry is full");
        return;
    }
    if (!set_application_id(connection, sender, path, application_id)) {
        archphene_atspi_translator_unregister(sender, path);
        send_error(connection, request, DBUS_ERROR_FAILED,
                "Could not assign accessibility application ID");
        return;
    }

    const char *owner = dbus_bus_get_unique_name(connection);
    DBusMessage *reply = dbus_message_new_method_return(request);
    if (owner == NULL || reply == NULL) {
        if (reply != NULL) dbus_message_unref(reply);
        archphene_atspi_translator_unregister(sender, path);
        return;
    }
    DBusMessageIter output;
    dbus_message_iter_init_append(reply, &output);
    if (!append_reference(&output, owner, REGISTRY_ROOT)) {
        dbus_message_unref(reply);
        archphene_atspi_translator_unregister(sender, path);
        return;
    }
    fprintf(stderr, "AT-SPI application registered bus=%s root=%s\n",
            sender, path);
    send_owned(connection, reply);
    if (!request_cache_items(connection, sender)) {
        archphene_atspi_translator_mark_dirty();
    }
}

static void handle_unembed(DBusConnection *connection, DBusMessage *request) {
    const char *sender = dbus_message_get_sender(request);
    const char *claimed_bus = NULL;
    const char *path = NULL;
    if (sender == NULL || !read_reference(request, &claimed_bus, &path)
            || path == NULL || (claimed_bus != NULL && claimed_bus[0] != '\0'
                && strcmp(claimed_bus, sender) != 0)) {
        send_error(connection, request, DBUS_ERROR_INVALID_ARGS,
                "Invalid or spoofed application root");
        return;
    }
    archphene_atspi_translator_unregister(sender, path);
    send_empty(connection, request);
}

static dbus_bool_t append_listener(DBusMessageIter *listeners,
        const char *bus, const char *event) {
    DBusMessageIter structure;
    return dbus_message_iter_open_container(
                    listeners, DBUS_TYPE_STRUCT, NULL, &structure)
            && dbus_message_iter_append_basic(
                    &structure, DBUS_TYPE_STRING, &bus)
            && dbus_message_iter_append_basic(
                    &structure, DBUS_TYPE_STRING, &event)
            && dbus_message_iter_close_container(listeners, &structure);
}

static void handle_registered_events(
        DBusConnection *connection, DBusMessage *request) {
    const char *owner = dbus_bus_get_unique_name(connection);
    DBusMessage *reply = dbus_message_new_method_return(request);
    if (owner == NULL || reply == NULL) {
        if (reply != NULL) dbus_message_unref(reply);
        return;
    }
    DBusMessageIter output;
    DBusMessageIter listeners;
    dbus_message_iter_init_append(reply, &output);
    if (!dbus_message_iter_open_container(
                    &output, DBUS_TYPE_ARRAY, "(ss)", &listeners)
            || !append_listener(&listeners, owner, "object:")
            || !append_listener(&listeners, owner, "window:")
            || !dbus_message_iter_close_container(&output, &listeners)) {
        dbus_message_unref(reply);
        return;
    }
    send_owned(connection, reply);
}

static void handle_introspection(
        DBusConnection *connection, DBusMessage *request) {
    const char *path = dbus_message_get_path(request);
    const char *xml = path != NULL && strcmp(path, A11Y_BUS_PATH) == 0
            ? bus_xml : (path != NULL && strcmp(path, REGISTRY_ROOT) == 0
                    ? root_xml : registry_xml);
    DBusMessage *reply = dbus_message_new_method_return(request);
    if (reply != NULL && dbus_message_append_args(
            reply, DBUS_TYPE_STRING, &xml, DBUS_TYPE_INVALID)) {
        send_owned(connection, reply);
    } else if (reply != NULL) {
        dbus_message_unref(reply);
    }
}

int archphene_atspi_init(DBusConnection *connection, DBusError *error) {
    if (own_name(connection, A11Y_BUS, error) != 0
            || own_name(connection, A11Y_REGISTRY, error) != 0
            || archphene_atspi_translator_start() != 0) {
        return -1;
    }
    dbus_bus_add_match(connection,
            "type='signal',interface='org.a11y.atspi.Event.Object'", error);
    if (!dbus_error_is_set(error)) {
        dbus_bus_add_match(connection,
                "type='signal',interface='org.a11y.atspi.Event.Window'", error);
    }
    if (!dbus_error_is_set(error)) {
        dbus_bus_add_match(connection,
                "type='signal',interface='org.a11y.atspi.Cache'", error);
    }
    if (dbus_error_is_set(error)) {
        archphene_atspi_translator_stop();
        return -1;
    }
    if (!send_available(connection)) {
        archphene_atspi_translator_stop();
        return -1;
    }
    fprintf(stderr, "Private AT-SPI2 registry and translator ready\n");
    return 0;
}

dbus_bool_t archphene_atspi_handles(
        DBusConnection *connection, DBusMessage *message) {
    const char *path = dbus_message_get_path(message);
    if (path == NULL || (strcmp(path, A11Y_BUS_PATH) != 0
            && strcmp(path, A11Y_REGISTRY_PATH) != 0
            && strcmp(path, REGISTRY_ROOT) != 0)) {
        return FALSE;
    }

    if (dbus_message_is_method_call(message, INTROSPECTABLE, "Introspect")) {
        if (require_signature(connection, message, "", NULL, NULL))
            handle_introspection(connection, message);
        return TRUE;
    }
    if (strcmp(path, A11Y_BUS_PATH) == 0) {
        if (dbus_message_is_method_call(message, PROPERTIES, "Get")) {
            if (require_signature(connection, message, "ss", NULL, NULL))
                handle_properties(connection, message);
        } else if (dbus_message_is_method_call(message, PROPERTIES, "GetAll")) {
            if (require_signature(connection, message, "s", NULL, NULL))
                handle_properties(connection, message);
        } else if (dbus_message_is_method_call(message, PROPERTIES, "Set")) {
            if (require_signature(connection, message, "ssv", NULL, NULL))
                handle_properties(connection, message);
        } else if (dbus_message_is_method_call(message, A11Y_BUS, "GetAddress")) {
            if (require_signature(connection, message, "", NULL, NULL))
                handle_address(connection, message);
        } else {
            send_error(connection, message, DBUS_ERROR_UNKNOWN_METHOD,
                    "Unsupported private AT-SPI bus method");
        }
        return TRUE;
    }
    if (strcmp(path, REGISTRY_ROOT) == 0) {
        if (dbus_message_is_method_call(message, PROPERTIES, "Get")) {
            if (require_signature(connection, message, "ss", NULL, NULL))
                handle_registry_properties(connection, message);
        } else if (dbus_message_is_method_call(message, PROPERTIES, "GetAll")) {
            if (require_signature(connection, message, "s", NULL, NULL))
                handle_registry_properties(connection, message);
        } else if (dbus_message_is_method_call(message, PROPERTIES, "Set")) {
            if (require_signature(connection, message, "ssv", NULL, NULL))
                handle_registry_properties(connection, message);
        } else if (dbus_message_is_method_call(message, A11Y_SOCKET, "Embed")) {
            if (require_signature(connection, message, "(so)", NULL, NULL))
                handle_embed(connection, message);
        } else if (dbus_message_is_method_call(message, A11Y_SOCKET, "Unembed")) {
            if (require_signature(connection, message, "(so)", NULL, NULL))
                handle_unembed(connection, message);
        } else {
            send_error(connection, message, DBUS_ERROR_UNKNOWN_METHOD,
                    "Unsupported private AT-SPI socket method");
        }
        return TRUE;
    }

    if (dbus_message_is_method_call(message, PROPERTIES, "Get")) {
        if (require_signature(connection, message, "ss", NULL, NULL))
            handle_registry_properties(connection, message);
    } else if (dbus_message_is_method_call(message, PROPERTIES, "GetAll")) {
        if (require_signature(connection, message, "s", NULL, NULL))
            handle_registry_properties(connection, message);
    } else if (dbus_message_is_method_call(message, PROPERTIES, "Set")) {
        if (require_signature(connection, message, "ssv", NULL, NULL))
            handle_registry_properties(connection, message);
    } else if (dbus_message_is_method_call(
            message, A11Y_REGISTRY, "GetRegisteredEvents")) {
        if (require_signature(connection, message, "", NULL, NULL))
            handle_registered_events(connection, message);
    } else if (dbus_message_is_method_call(
            message, A11Y_REGISTRY, "RegisterEvent")) {
        if (require_signature(connection, message, "sass", "sas", "s"))
            send_empty(connection, message);
    } else if (dbus_message_is_method_call(
            message, A11Y_REGISTRY, "DeregisterEvent")) {
        if (require_signature(connection, message, "ss", "s", NULL))
            send_empty(connection, message);
    } else {
        send_error(connection, message, DBUS_ERROR_UNKNOWN_METHOD,
                "Unsupported private AT-SPI registry method");
    }
    return TRUE;
}
dbus_bool_t archphene_atspi_handles_reply(DBusMessage *message) {
    if (message == NULL
            || (dbus_message_get_type(message) != DBUS_MESSAGE_TYPE_METHOD_RETURN
                && dbus_message_get_type(message) != DBUS_MESSAGE_TYPE_ERROR)) {
        return FALSE;
    }
    if (take_application_id_query(message)) {
        if (dbus_message_get_type(message) == DBUS_MESSAGE_TYPE_ERROR) {
            const char *name = dbus_message_get_error_name(message);
            fprintf(stderr, "AT-SPI application ID assignment failed error=%s\n",
                    name == NULL ? "unknown" : name);
        }
        return TRUE;
    }
    if (!take_cache_query(message)) return FALSE;
    if (dbus_message_get_type(message) == DBUS_MESSAGE_TYPE_METHOD_RETURN) {
        archphene_atspi_translator_cache_items(message);
    } else {
        const char *name = dbus_message_get_error_name(message);
        dbus_bool_t cache_not_ready = name != NULL
                && strcmp(name, DBUS_ERROR_UNKNOWN_METHOD) == 0;
        fprintf(stderr, "AT-SPI cache query failed error=%s\n",
                name == NULL ? "unknown" : name);
        if (!cache_not_ready) archphene_atspi_translator_mark_dirty();
    }
    return TRUE;
}
void archphene_atspi_handle_signal(
        DBusConnection *connection, DBusMessage *message) {
    if (message == NULL
            || dbus_message_get_type(message) != DBUS_MESSAGE_TYPE_SIGNAL) {
        return;
    }
    const char *sender = dbus_message_get_sender(message);
    const char *interface = dbus_message_get_interface(message);
    const char *member = dbus_message_get_member(message);
    dbus_bool_t accessibility_event = interface != NULL
            && (strcmp(interface, "org.a11y.atspi.Event.Object") == 0
                || strcmp(interface, "org.a11y.atspi.Event.Window") == 0);
    dbus_bool_t cache_event = interface != NULL && member != NULL
            && strcmp(interface, "org.a11y.atspi.Cache") == 0
            && ((strcmp(member, "AddAccessible") == 0
                    && dbus_message_has_signature(
                        message, "((so)(so)(so)iiassusau)"))
                || (strcmp(member, "RemoveAccessible") == 0
                    && dbus_message_has_signature(message, "(so)")));
    if (sender != NULL && cache_event) {
        archphene_atspi_translator_event(message);
        return;
    }
    if (sender != NULL && accessibility_event
            && dbus_message_has_signature(message, "siiva{sv}")
            && archphene_atspi_translator_has_bus(sender)) {
        archphene_atspi_translator_event(message);
        if (connection != NULL
                && strcmp(interface, "org.a11y.atspi.Event.Window") == 0
                && strcmp(member, "Create") == 0) {
            if (!request_cache_items(connection, sender)) {
                archphene_atspi_translator_mark_dirty();
            }
        }
        return;
    }
    if (!dbus_message_is_signal(
            message, "org.freedesktop.DBus", "NameOwnerChanged")
            || !dbus_message_has_signature(message, "sss")) {
        return;
    }
    const char *name = NULL;
    const char *old_owner = NULL;
    const char *new_owner = NULL;
    if (dbus_message_get_args(message, NULL,
                DBUS_TYPE_STRING, &name,
                DBUS_TYPE_STRING, &old_owner,
                DBUS_TYPE_STRING, &new_owner,
                DBUS_TYPE_INVALID)
            && name != NULL && old_owner != NULL && old_owner[0] != '\0'
            && new_owner != NULL && new_owner[0] == '\0') {
        archphene_atspi_translator_disconnect(name);
    }
}

const char *archphene_atspi_introspection(const char *path) {
    if (path == NULL) return NULL;
    if (strcmp(path, A11Y_BUS_PATH) == 0) return bus_xml;
    if (strcmp(path, REGISTRY_ROOT) == 0) return root_xml;
    if (strcmp(path, A11Y_REGISTRY_PATH) == 0) return registry_xml;
    return NULL;
}
void archphene_atspi_shutdown(void) {
    archphene_atspi_translator_stop();
}