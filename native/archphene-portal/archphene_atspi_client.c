#include "archphene_atspi_client.h"

#include <ctype.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define ACCESSIBLE "org.a11y.atspi.Accessible"
#define ACTION "org.a11y.atspi.Action"
#define COMPONENT "org.a11y.atspi.Component"
#define EDITABLE_TEXT "org.a11y.atspi.EditableText"
#define PROPERTIES "org.freedesktop.DBus.Properties"
#define TEXT "org.a11y.atspi.Text"
#define CALL_TIMEOUT_MILLIS 1000
#define ROLE_CALL_TIMEOUT_MILLIS 250
#define ACTION_SCAN_MAX 8
#define INTERFACE_SCAN_MAX 64

enum {
    STATE_CHECKED = 4,
    STATE_EDITABLE = 7,
    STATE_ENABLED = 8,
    STATE_FOCUSABLE = 11,
    STATE_SHOWING = 25,
    STATE_VISIBLE = 30,
};

enum {
    ROLE_ALERT = 2,
    ROLE_CHECK_BOX = 7,
    ROLE_CHECK_MENU_ITEM = 8,
    ROLE_COMBO_BOX = 11,
    ROLE_DIAL = 15,
    ROLE_DIALOG = 16,
    ROLE_FRAME = 23,
    ROLE_ICON = 26,
    ROLE_IMAGE = 27,
    ROLE_INTERNAL_FRAME = 28,
    ROLE_LABEL = 29,
    ROLE_LIST = 31,
    ROLE_LIST_ITEM = 32,
    ROLE_MENU = 33,
    ROLE_MENU_BAR = 34,
    ROLE_MENU_ITEM = 35,
    ROLE_PAGE_TAB = 37,
    ROLE_PAGE_TAB_LIST = 38,
    ROLE_PASSWORD_TEXT = 40,
    ROLE_POPUP_MENU = 41,
    ROLE_PROGRESS_BAR = 42,
    ROLE_BUTTON = 43,
    ROLE_RADIO_BUTTON = 44,
    ROLE_RADIO_MENU_ITEM = 45,
    ROLE_SCROLL_BAR = 48,
    ROLE_SLIDER = 51,
    ROLE_SPIN_BUTTON = 52,
    ROLE_TABLE = 55,
    ROLE_TABLE_CELL = 56,
    ROLE_TEAROFF_MENU_ITEM = 59,
    ROLE_TEXT = 61,
    ROLE_TOGGLE_BUTTON = 62,
    ROLE_TREE = 65,
    ROLE_TREE_TABLE = 66,
    ROLE_WINDOW = 69,
    ROLE_PARAGRAPH = 73,
    ROLE_APPLICATION = 75,
    ROLE_EDITBAR = 77,
    ROLE_ENTRY = 79,
    ROLE_HEADING = 83,
    ROLE_LINK = 88,
    ROLE_TABLE_ROW = 90,
    ROLE_TREE_ITEM = 91,
    ROLE_DOCUMENT_TEXT = 94,
    ROLE_LIST_BOX = 98,
    ROLE_LEVEL_BAR = 103,
    ROLE_STATIC = 116,
    ROLE_DESCRIPTION_LIST = 121,
    ROLE_DESCRIPTION_TERM = 122,
    ROLE_DESCRIPTION_VALUE = 123,
    ROLE_PUSH_BUTTON_MENU = 129,
    ROLE_SWITCH = 130,
};

typedef struct {
    dbus_bool_t action;
    dbus_bool_t component;
    dbus_bool_t editable_text;
    dbus_bool_t text;
} Interfaces;

static size_t utf8_sequence_length(const unsigned char *source) {
    unsigned char first = source[0];
    if (first < 0x80) return 1;
    size_t length = first >= 0xc2 && first <= 0xdf ? 2
            : first >= 0xe0 && first <= 0xef ? 3
            : first >= 0xf0 && first <= 0xf4 ? 4 : 0;
    for (size_t index = 1; index < length; index++) {
        if (source[index] == '\0' || (source[index] & 0xc0) != 0x80) return 0;
    }
    return length;
}

static void copy_text(char *target, size_t capacity, const char *source) {
    if (capacity == 0) return;
    if (source == NULL) source = "";
    size_t input = 0;
    size_t output = 0;
    while (source[input] != '\0' && output + 1 < capacity) {
        size_t length = utf8_sequence_length(
                (const unsigned char *)&source[input]);
        if (length == 0) {
            target[output++] = '?';
            input++;
            continue;
        }
        if (output + length >= capacity) break;
        memcpy(&target[output], &source[input], length);
        output += length;
        input += length;
    }
    target[output] = '\0';
}

static DBusMessage *send_call_with_timeout(DBusConnection *connection,
        DBusMessage *request, int timeout_millis) {
    if (connection == NULL || request == NULL) {
        if (request != NULL) dbus_message_unref(request);
        return NULL;
    }
    DBusError error = DBUS_ERROR_INIT;
    DBusMessage *reply = dbus_connection_send_with_reply_and_block(
            connection, request, timeout_millis, &error);
    dbus_message_unref(request);
    if (dbus_error_is_set(&error)) dbus_error_free(&error);
    return reply;
}

static DBusMessage *send_call(DBusConnection *connection, DBusMessage *request) {
    return send_call_with_timeout(connection, request, CALL_TIMEOUT_MILLIS);
}

static dbus_bool_t exact_reply(DBusMessage *reply, const char *signature) {
    return reply != NULL
            && dbus_message_get_type(reply) == DBUS_MESSAGE_TYPE_METHOD_RETURN
            && dbus_message_has_signature(reply, signature);
}

static dbus_bool_t valid_reference(const ArchpheneAtspiReference *reference) {
    return reference != NULL && reference->bus[0] == ':'
            && strnlen(reference->bus, sizeof(reference->bus))
                    < sizeof(reference->bus)
            && strnlen(reference->path, sizeof(reference->path))
                    < sizeof(reference->path)
            && dbus_validate_bus_name(reference->bus, NULL)
            && dbus_validate_path(reference->path, NULL);
}

static DBusMessage *new_call(const ArchpheneAtspiReference *reference,
        const char *interface, const char *method) {
    if (!valid_reference(reference)) return NULL;
    return dbus_message_new_method_call(
            reference->bus, reference->path, interface, method);
}

static DBusMessage *get_property(DBusConnection *connection,
        const ArchpheneAtspiReference *reference,
        const char *interface, const char *property) {
    DBusMessage *request = new_call(reference, PROPERTIES, "Get");
    if (request == NULL || !dbus_message_append_args(request,
            DBUS_TYPE_STRING, &interface,
            DBUS_TYPE_STRING, &property,
            DBUS_TYPE_INVALID)) {
        if (request != NULL) dbus_message_unref(request);
        return NULL;
    }
    return send_call(connection, request);
}

static dbus_bool_t variant_iter(DBusMessage *reply, DBusMessageIter *value) {
    DBusMessageIter output;
    if (!exact_reply(reply, "v") || !dbus_message_iter_init(reply, &output)) {
        return FALSE;
    }
    dbus_message_iter_recurse(&output, value);
    return TRUE;
}

static dbus_bool_t read_string_property(DBusConnection *connection,
        const ArchpheneAtspiReference *reference, const char *property,
        char *target, size_t capacity) {
    DBusMessage *reply = get_property(connection, reference, ACCESSIBLE, property);
    DBusMessageIter value;
    dbus_bool_t ok = variant_iter(reply, &value)
            && dbus_message_iter_get_arg_type(&value) == DBUS_TYPE_STRING;
    if (ok) {
        const char *source = NULL;
        dbus_message_iter_get_basic(&value, &source);
        copy_text(target, capacity, source);
    }
    if (reply != NULL) dbus_message_unref(reply);
    return ok;
}

static dbus_bool_t read_method_string(DBusConnection *connection,
        const ArchpheneAtspiReference *reference, const char *interface,
        const char *method, char *target, size_t capacity,
        int timeout_millis) {
    DBusMessage *reply = send_call_with_timeout(connection,
            new_call(reference, interface, method), timeout_millis);
    DBusMessageIter output;
    dbus_bool_t ok = exact_reply(reply, "s")
            && dbus_message_iter_init(reply, &output);
    if (ok) {
        const char *value = NULL;
        dbus_message_iter_get_basic(&output, &value);
        copy_text(target, capacity, value);
    }
    if (reply != NULL) dbus_message_unref(reply);
    return ok;
}

static dbus_bool_t read_method_uint32(DBusConnection *connection,
        const ArchpheneAtspiReference *reference, const char *interface,
        const char *method, uint32_t *target, int timeout_millis) {
    DBusMessage *reply = send_call_with_timeout(connection,
            new_call(reference, interface, method), timeout_millis);
    DBusMessageIter output;
    dbus_bool_t ok = target != NULL && exact_reply(reply, "u")
            && dbus_message_iter_init(reply, &output);
    if (ok) dbus_message_iter_get_basic(&output, target);
    if (reply != NULL) dbus_message_unref(reply);
    return ok;
}

static dbus_bool_t read_interfaces(DBusConnection *connection,
        const ArchpheneAtspiReference *reference, Interfaces *interfaces) {
    DBusMessage *reply = send_call(connection,
            new_call(reference, ACCESSIBLE, "GetInterfaces"));
    DBusMessageIter output;
    DBusMessageIter array;
    if (!exact_reply(reply, "as") || !dbus_message_iter_init(reply, &output)) {
        if (reply != NULL) dbus_message_unref(reply);
        return FALSE;
    }
    dbus_message_iter_recurse(&output, &array);
    size_t scanned = 0;
    while (scanned < INTERFACE_SCAN_MAX
            && dbus_message_iter_get_arg_type(&array) == DBUS_TYPE_STRING) {
        const char *name = NULL;
        dbus_message_iter_get_basic(&array, &name);
        if (name != NULL) {
            if (strcmp(name, ACTION) == 0) interfaces->action = TRUE;
            else if (strcmp(name, COMPONENT) == 0) interfaces->component = TRUE;
            else if (strcmp(name, EDITABLE_TEXT) == 0) interfaces->editable_text = TRUE;
            else if (strcmp(name, TEXT) == 0) interfaces->text = TRUE;
        }
        scanned++;
        dbus_message_iter_next(&array);
    }
    if (dbus_message_iter_get_arg_type(&array) != DBUS_TYPE_INVALID) {
        dbus_message_unref(reply);
        return FALSE;
    }
    dbus_message_unref(reply);
    return TRUE;
}

static dbus_bool_t read_states(DBusConnection *connection,
        const ArchpheneAtspiReference *reference, uint32_t states[2]) {
    DBusMessage *reply = send_call(connection,
            new_call(reference, ACCESSIBLE, "GetState"));
    DBusMessageIter output;
    DBusMessageIter array;
    if (!exact_reply(reply, "au") || !dbus_message_iter_init(reply, &output)) {
        if (reply != NULL) dbus_message_unref(reply);
        return FALSE;
    }
    dbus_message_iter_recurse(&output, &array);
    size_t index = 0;
    while (index < 2
            && dbus_message_iter_get_arg_type(&array) == DBUS_TYPE_UINT32) {
        dbus_message_iter_get_basic(&array, &states[index++]);
        dbus_message_iter_next(&array);
    }
    dbus_bool_t complete = index == 2
            && dbus_message_iter_get_arg_type(&array) == DBUS_TYPE_INVALID;
    dbus_message_unref(reply);
    return complete;
}

static dbus_bool_t has_state(const uint32_t states[2], unsigned int state) {
    unsigned int word = state / 32;
    unsigned int bit = state % 32;
    return word < 2 && (states[word] & (1u << bit)) != 0;
}

static void read_extents(DBusConnection *connection,
        const ArchpheneAtspiReference *reference, ArchpheneAtspiNode *node) {
    DBusMessage *request = new_call(reference, COMPONENT, "GetExtents");
    uint32_t coordinate_type = 1;
    if (request == NULL || !dbus_message_append_args(request,
            DBUS_TYPE_UINT32, &coordinate_type, DBUS_TYPE_INVALID)) {
        if (request != NULL) dbus_message_unref(request);
        return;
    }
    DBusMessage *reply = send_call(connection, request);
    DBusMessageIter output;
    DBusMessageIter rectangle;
    if (exact_reply(reply, "(iiii)")
            && dbus_message_iter_init(reply, &output)) {
        int32_t values[4] = {0, 0, 1, 1};
        dbus_message_iter_recurse(&output, &rectangle);
        for (size_t index = 0; index < 4; index++) {
            if (dbus_message_iter_get_arg_type(&rectangle) != DBUS_TYPE_INT32) break;
            dbus_message_iter_get_basic(&rectangle, &values[index]);
            dbus_message_iter_next(&rectangle);
        }
        node->x = values[0];
        node->y = values[1];
        node->width = values[2] > 0 ? values[2] : 1;
        node->height = values[3] > 0 ? values[3] : 1;
    }
    if (reply != NULL) dbus_message_unref(reply);
}

static void read_text(DBusConnection *connection,
        const ArchpheneAtspiReference *reference, char *target, size_t capacity) {
    DBusMessage *request = new_call(reference, TEXT, "GetText");
    int32_t start = 0;
    int32_t end = ARCHPHENE_ATSPI_TEXT_MAX;
    if (request == NULL || !dbus_message_append_args(request,
            DBUS_TYPE_INT32, &start, DBUS_TYPE_INT32, &end,
            DBUS_TYPE_INVALID)) {
        if (request != NULL) dbus_message_unref(request);
        return;
    }
    DBusMessage *reply = send_call(connection, request);
    DBusMessageIter output;
    if (exact_reply(reply, "s") && dbus_message_iter_init(reply, &output)) {
        const char *value = NULL;
        dbus_message_iter_get_basic(&output, &value);
        copy_text(target, capacity, value);
    }
    if (reply != NULL) dbus_message_unref(reply);
}

static void normalize_action(char *target, size_t capacity, const char *source) {
    if (capacity == 0) return;
    size_t output = 0;
    for (size_t index = 0; source != NULL && source[index] != '\0'
            && output + 1 < capacity; index++) {
        unsigned char current = (unsigned char)source[index];
        if (isalnum(current)) target[output++] = (char)tolower(current);
        else if (output > 0 && target[output - 1] != '-') target[output++] = '-';
    }
    while (output > 0 && target[output - 1] == '-') output--;
    target[output] = '\0';
}

static dbus_bool_t read_action_name(DBusConnection *connection,
        const ArchpheneAtspiReference *reference, int index,
        char *target, size_t capacity) {
    DBusMessage *request = new_call(reference, ACTION, "GetName");
    if (request == NULL || !dbus_message_append_args(request,
            DBUS_TYPE_INT32, &index, DBUS_TYPE_INVALID)) {
        if (request != NULL) dbus_message_unref(request);
        return FALSE;
    }
    DBusMessage *reply = send_call(connection, request);
    DBusMessageIter output;
    dbus_bool_t ok = exact_reply(reply, "s")
            && dbus_message_iter_init(reply, &output);
    if (ok) {
        const char *value = NULL;
        dbus_message_iter_get_basic(&output, &value);
        copy_text(target, capacity, value);
    }
    if (reply != NULL) dbus_message_unref(reply);
    return ok;
}

static void read_actions(DBusConnection *connection,
        const ArchpheneAtspiReference *reference, ArchpheneAtspiNode *node) {
    DBusMessage *reply = send_call(connection,
            new_call(reference, ACTION, "GetActions"));
    DBusMessageIter output;
    DBusMessageIter array;
    if (!exact_reply(reply, "a(sss)")
            || !dbus_message_iter_init(reply, &output)) {
        if (reply != NULL) dbus_message_unref(reply);
        return;
    }
    dbus_message_iter_recurse(&output, &array);
    int index = 0;
    while (index < ACTION_SCAN_MAX
            && dbus_message_iter_get_arg_type(&array) == DBUS_TYPE_STRUCT) {
        DBusMessageIter fields;
        dbus_message_iter_recurse(&array, &fields);
        const char *name = NULL;
        if (dbus_message_iter_get_arg_type(&fields) == DBUS_TYPE_STRING) {
            dbus_message_iter_get_basic(&fields, &name);
        }
        char machine_name[64] = {0};
        if (!read_action_name(connection, reference, index,
                machine_name, sizeof(machine_name))) {
            copy_text(machine_name, sizeof(machine_name), name);
        }
        char normalized[64] = {0};
        normalize_action(normalized, sizeof(normalized), machine_name);
        if (strcmp(normalized, "showmenu") == 0) {
            node->click_action = index;
            node->show_menu_action = TRUE;
        } else if (strstr(normalized, "scroll") != NULL
                && (strstr(normalized, "down") != NULL
                    || strstr(normalized, "forward") != NULL
                    || strstr(normalized, "next") != NULL)) {
            node->scroll_forward_action = index;
        } else if (strstr(normalized, "scroll") != NULL
                && (strstr(normalized, "up") != NULL
                    || strstr(normalized, "back") != NULL
                    || strstr(normalized, "previous") != NULL)) {
            node->scroll_backward_action = index;
        } else if (node->click_action < 0
                && (strcmp(normalized, "click") == 0
                    || strcmp(normalized, "press") == 0
                    || strcmp(normalized, "activate") == 0
                    || strcmp(normalized, "toggle") == 0)) {
            node->click_action = index;
        }
        index++;
        dbus_message_iter_next(&array);
    }
    if (node->click_action < 0 && index == 1) node->click_action = 0;
    node->clickable = node->click_action >= 0;
    dbus_message_unref(reply);
}

static int read_children(DBusConnection *connection,
        const ArchpheneAtspiReference *reference,
        ArchpheneAtspiReference *children, size_t capacity, size_t *count) {
    DBusMessage *reply = send_call(connection,
            new_call(reference, ACCESSIBLE, "GetChildren"));
    DBusMessageIter output;
    DBusMessageIter array;
    if (!exact_reply(reply, "a(so)")
            || !dbus_message_iter_init(reply, &output)) {
        if (reply != NULL) dbus_message_unref(reply);
        return -1;
    }
    dbus_message_iter_recurse(&output, &array);
    dbus_bool_t truncated = FALSE;
    while (dbus_message_iter_get_arg_type(&array) == DBUS_TYPE_STRUCT) {
        DBusMessageIter fields;
        dbus_message_iter_recurse(&array, &fields);
        const char *bus = NULL;
        const char *path = NULL;
        if (dbus_message_iter_get_arg_type(&fields) != DBUS_TYPE_STRING) {
            dbus_message_unref(reply);
            return -1;
        }
        dbus_message_iter_get_basic(&fields, &bus);
        if (!dbus_message_iter_next(&fields)
                || dbus_message_iter_get_arg_type(&fields)
                        != DBUS_TYPE_OBJECT_PATH) {
            dbus_message_unref(reply);
            return -1;
        }
        dbus_message_iter_get_basic(&fields, &path);
        ArchpheneAtspiReference child = {0};
        if (bus != NULL && path != NULL
                && strnlen(bus, sizeof(child.bus)) < sizeof(child.bus)
                && strnlen(path, sizeof(child.path)) < sizeof(child.path)) {
            copy_text(child.bus, sizeof(child.bus), bus);
            copy_text(child.path, sizeof(child.path), path);
        }
        if (valid_reference(&child)) {
            if (*count < capacity) {
                children[*count] = child;
                (*count)++;
            } else {
                truncated = TRUE;
            }
        }
        dbus_message_iter_next(&array);
    }
    if (dbus_message_iter_get_arg_type(&array) != DBUS_TYPE_INVALID) {
        dbus_message_unref(reply);
        return -1;
    }
    dbus_message_unref(reply);
    return truncated ? 1 : 0;
}

static const char *map_role_id(uint32_t role) {
    switch (role) {
        case ROLE_ALERT:
        case ROLE_DIALOG:
        case ROLE_FRAME:
        case ROLE_INTERNAL_FRAME:
        case ROLE_WINDOW:
            return "window";
        case ROLE_CHECK_BOX:
        case ROLE_CHECK_MENU_ITEM:
        case ROLE_TOGGLE_BUTTON:
        case ROLE_SWITCH:
            return "checkbox";
        case ROLE_RADIO_BUTTON:
        case ROLE_RADIO_MENU_ITEM:
            return "radio";
        case ROLE_PAGE_TAB:
        case ROLE_BUTTON:
        case ROLE_LINK:
        case ROLE_PUSH_BUTTON_MENU:
            return "button";
        case ROLE_PASSWORD_TEXT:
        case ROLE_EDITBAR:
        case ROLE_ENTRY:
            return "edit";
        case ROLE_ICON:
        case ROLE_IMAGE:
            return "image";
        case ROLE_LIST:
        case ROLE_COMBO_BOX:
        case ROLE_PAGE_TAB_LIST:
        case ROLE_TABLE:
        case ROLE_TREE:
        case ROLE_TREE_TABLE:
        case ROLE_LIST_BOX:
        case ROLE_DESCRIPTION_LIST:
            return "list";
        case ROLE_LIST_ITEM:
        case ROLE_TABLE_CELL:
        case ROLE_TABLE_ROW:
        case ROLE_TREE_ITEM:
        case ROLE_DESCRIPTION_TERM:
        case ROLE_DESCRIPTION_VALUE:
            return "list-item";
        case ROLE_MENU:
        case ROLE_MENU_BAR:
        case ROLE_POPUP_MENU:
            return "menu";
        case ROLE_MENU_ITEM:
        case ROLE_TEAROFF_MENU_ITEM:
            return "menu-item";
        case ROLE_DIAL:
        case ROLE_PROGRESS_BAR:
        case ROLE_SCROLL_BAR:
        case ROLE_SLIDER:
        case ROLE_SPIN_BUTTON:
        case ROLE_LEVEL_BAR:
            return "slider";
        case ROLE_LABEL:
        case ROLE_HEADING:
            return "label";
        case ROLE_TEXT:
        case ROLE_PARAGRAPH:
        case ROLE_DOCUMENT_TEXT:
        case ROLE_STATIC:
            return "text";
        default:
            return NULL;
    }
}

static void map_role(const char *source, char target[32]) {
    char role[64] = {0};
    normalize_action(role, sizeof(role), source);
    const char *mapped = "view";
    if (strstr(role, "window") != NULL || strcmp(role, "frame") == 0
            || strcmp(role, "dialog") == 0) mapped = "window";
    else if (strstr(role, "check") != NULL || strstr(role, "toggle") != NULL)
        mapped = "checkbox";
    else if (strstr(role, "radio") != NULL) mapped = "radio";
    else if (strstr(role, "button") != NULL) mapped = "button";
    else if (strstr(role, "entry") != NULL || strstr(role, "password") != NULL)
        mapped = "edit";
    else if (strcmp(role, "text") == 0) mapped = "text";
    else if (strstr(role, "image") != NULL || strcmp(role, "icon") == 0)
        mapped = "image";
    else if (strcmp(role, "list") == 0 || strcmp(role, "list-box") == 0)
        mapped = "list";
    else if (strcmp(role, "list-item") == 0) mapped = "list-item";
    else if (strcmp(role, "menu") == 0 || strcmp(role, "menu-bar") == 0
            || strcmp(role, "popup-menu") == 0) mapped = "menu";
    else if (strstr(role, "menu-item") != NULL) mapped = "menu-item";
    else if (strcmp(role, "slider") == 0 || strcmp(role, "scroll-bar") == 0)
        mapped = "slider";
    else if (strcmp(role, "label") == 0 || strcmp(role, "heading") == 0)
        mapped = "label";
    copy_text(target, 32, mapped);
}

int archphene_atspi_client_read_node(DBusConnection *connection,
        const ArchpheneAtspiReference *reference, ArchpheneAtspiNode *node,
        ArchpheneAtspiReference *children, size_t children_capacity,
        size_t *children_count) {
    if (connection == NULL || reference == NULL || node == NULL
            || children == NULL || children_count == NULL) return -1;
    *children_count = 0;
    memset(node, 0, sizeof(*node));
    node->reference = *reference;
    node->width = 1;
    node->height = 1;
    node->enabled = TRUE;
    node->showing = TRUE;
    node->visible = TRUE;
    node->click_action = -1;
    node->scroll_forward_action = -1;
    node->scroll_backward_action = -1;
    uint32_t role_id = 0;
    dbus_bool_t role_id_available = read_method_uint32(
            connection, reference, ACCESSIBLE, "GetRole", &role_id,
            ROLE_CALL_TIMEOUT_MILLIS);
    char role_name[64] = {0};
    dbus_bool_t role_name_available = read_method_string(connection, reference,
            ACCESSIBLE, "GetRoleName", role_name, sizeof(role_name),
            ROLE_CALL_TIMEOUT_MILLIS);
    if (!role_id_available && !role_name_available) return -1;
    const char *mapped_role = role_id_available ? map_role_id(role_id) : NULL;
    if (mapped_role != NULL) copy_text(node->role, sizeof(node->role), mapped_role);
    else map_role(role_name, node->role);
    char normalized_role[64] = {0};
    normalize_action(normalized_role, sizeof(normalized_role), role_name);
    node->application = (role_id_available && role_id == ROLE_APPLICATION)
            || strcmp(normalized_role, "application") == 0;
    node->menu_bar = (role_id_available && role_id == ROLE_MENU_BAR)
            || strcmp(normalized_role, "menu-bar") == 0
            || strcmp(normalized_role, "menubar") == 0;
    node->menu_item = (role_id_available
            && (role_id == ROLE_MENU_ITEM
                    || role_id == ROLE_CHECK_MENU_ITEM
                    || role_id == ROLE_RADIO_MENU_ITEM
                    || role_id == ROLE_TEAROFF_MENU_ITEM))
            || strstr(normalized_role, "menu-item") != NULL;
    node->password = (role_id_available && role_id == ROLE_PASSWORD_TEXT)
            || strstr(normalized_role, "password") != NULL;
    read_string_property(connection, reference, "Name",
            node->text, sizeof(node->text));
    read_string_property(connection, reference, "Description",
            node->description, sizeof(node->description));
    Interfaces interfaces = {0};
    read_interfaces(connection, reference, &interfaces);
    uint32_t states[2] = {0, 0};
    if (read_states(connection, reference, states)) {
        node->enabled = has_state(states, STATE_ENABLED);
        node->focusable = has_state(states, STATE_FOCUSABLE);
        node->editable = has_state(states, STATE_EDITABLE);
        node->checked = has_state(states, STATE_CHECKED);
        node->showing = has_state(states, STATE_SHOWING);
        node->visible = has_state(states, STATE_VISIBLE);
    }
    if (interfaces.component) read_extents(connection, reference, node);
    if (interfaces.text && !node->password && node->text[0] == '\0') {
        read_text(connection, reference, node->text, sizeof(node->text));
    }
    if (interfaces.action) read_actions(connection, reference, node);
    node->editable = node->editable || interfaces.editable_text;
    node->focusable = node->focusable || node->clickable || node->editable;
    node->checkable = strcmp(node->role, "checkbox") == 0
            || strcmp(node->role, "radio") == 0;
    return read_children(connection, reference, children,
            children_capacity, children_count);
}

static int call_boolean(DBusConnection *connection,
        const ArchpheneAtspiReference *reference, const char *interface,
        const char *method, int argument, dbus_bool_t has_argument,
        const char *text) {
    DBusMessage *request = new_call(reference, interface, method);
    if (request == NULL) return -1;
    if ((has_argument && !dbus_message_append_args(request,
                    DBUS_TYPE_INT32, &argument, DBUS_TYPE_INVALID))
            || (text != NULL && !dbus_message_append_args(request,
                    DBUS_TYPE_STRING, &text, DBUS_TYPE_INVALID))) {
        dbus_message_unref(request);
        return -1;
    }
    DBusMessage *reply = send_call(connection, request);
    DBusMessageIter output;
    dbus_bool_t result = FALSE;
    if (exact_reply(reply, "b") && dbus_message_iter_init(reply, &output)) {
        dbus_message_iter_get_basic(&output, &result);
    }
    if (reply != NULL) dbus_message_unref(reply);
    return result ? 0 : -1;
}

int archphene_atspi_client_click(
        DBusConnection *connection, const ArchpheneAtspiNode *node) {
    if (connection == NULL || node == NULL || node->click_action < 0
            || node->click_action >= ACTION_SCAN_MAX) return -1;
    return call_boolean(connection, &node->reference, ACTION, "DoAction",
            node->click_action, TRUE, NULL);
}

int archphene_atspi_client_focus(
        DBusConnection *connection, const ArchpheneAtspiNode *node) {
    if (connection == NULL || node == NULL) return -1;
    return call_boolean(connection, &node->reference, COMPONENT, "GrabFocus",
            0, FALSE, NULL);
}

int archphene_atspi_client_set_text(DBusConnection *connection,
        const ArchpheneAtspiNode *node, const char *text) {
    if (connection == NULL || node == NULL || text == NULL || !node->editable
            || strnlen(text, ARCHPHENE_ATSPI_TEXT_MAX + 1)
                    > ARCHPHENE_ATSPI_TEXT_MAX) return -1;
    return call_boolean(connection, &node->reference, EDITABLE_TEXT,
            "SetTextContents", 0, FALSE, text);
}

int archphene_atspi_client_scroll(DBusConnection *connection,
        const ArchpheneAtspiNode *node, dbus_bool_t forward) {
    if (connection == NULL || node == NULL) return -1;
    int action = forward ? node->scroll_forward_action
            : node->scroll_backward_action;
    if (action < 0 || action >= ACTION_SCAN_MAX) return -1;
    return call_boolean(connection, &node->reference, ACTION, "DoAction",
            action, TRUE, NULL);
}
