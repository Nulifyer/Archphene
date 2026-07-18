#include "archphene_atspi_translator.h"

#include "archphene_android.h"
#include "archphene_atspi_publish.h"

#include <errno.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define MAX_APPLICATIONS 16
#define MAX_TRANSIENT_ROOTS 32
#define MAX_CACHED_WINDOWS 32
#define ROOT_MAX (MAX_APPLICATIONS + MAX_TRANSIENT_ROOTS)
#define ACTION_RESPONSE_MAX 4096
#define MAX_EVENTS 64
#define REBUILD_RETRY_MILLIS 1000
#define ACTION_REFRESH_PASSES 4
#define ACTION_REFRESH_MILLIS 250

typedef struct {
    char bus[ARCHPHENE_ATSPI_BUS_MAX];
    char path[ARCHPHENE_ATSPI_PATH_MAX];
    int application_id;
} Registration;

typedef struct {
    ArchpheneAtspiReference reference;
    char type[16];
} PendingEvent;

static struct {
    pthread_mutex_t mutex;
    pthread_cond_t changed;
    pthread_t worker;
    bool started;
    bool stopping;
    bool worker_created;
    bool worker_ready;
    bool worker_failed;
    bool dirty;
    bool suppress_retention;
    unsigned int action_refresh_passes;
    Registration applications[MAX_APPLICATIONS];
    size_t application_count;
    ArchpheneAtspiReference transient_roots[MAX_TRANSIENT_ROOTS];
    bool transient_window_roots[MAX_TRANSIENT_ROOTS];
    size_t transient_root_count;
    ArchpheneAtspiNode cached_windows[MAX_CACHED_WINDOWS];
    size_t cached_window_count;
    uint64_t transient_generation;
    uint32_t next_application_id;
    PendingEvent events[MAX_EVENTS];
    size_t event_head;
    size_t event_count;
    ArchpheneAtspiTree *tree;
} state = {
    .mutex = PTHREAD_MUTEX_INITIALIZER,
    .changed = PTHREAD_COND_INITIALIZER,
};

static void wake_worker(void) {
    state.dirty = true;
    pthread_cond_signal(&state.changed);
}

static bool cache_reference_from_iter(
        DBusMessageIter *iter, ArchpheneAtspiReference *reference) {
    if (iter == NULL || reference == NULL
            || dbus_message_iter_get_arg_type(iter) != DBUS_TYPE_STRUCT) {
        return false;
    }
    DBusMessageIter fields;
    dbus_message_iter_recurse(iter, &fields);
    if (dbus_message_iter_get_arg_type(&fields) != DBUS_TYPE_STRING) return false;
    const char *bus = NULL;
    dbus_message_iter_get_basic(&fields, &bus);
    if (!dbus_message_iter_next(&fields)
            || dbus_message_iter_get_arg_type(&fields)
                    != DBUS_TYPE_OBJECT_PATH) return false;
    const char *path = NULL;
    dbus_message_iter_get_basic(&fields, &path);
    if (bus == NULL || path == NULL
            || strlen(bus) >= sizeof(reference->bus)
            || strlen(path) >= sizeof(reference->path)) return false;
    snprintf(reference->bus, sizeof(reference->bus), "%s", bus);
    snprintf(reference->path, sizeof(reference->path), "%s", path);
    return true;
}

static bool cache_state(const uint32_t states[2], unsigned int value) {
    unsigned int word = value / 32;
    unsigned int bit = value % 32;
    return word < 2 && (states[word] & (1u << bit)) != 0;
}

static bool cache_window_role(uint32_t role) {
    return role == 2 || role == 16 || role == 23
            || role == 28 || role == 69;
}

static bool parse_cache_window(
        DBusMessage *message, ArchpheneAtspiNode *node) {
    DBusMessageIter outer;
    DBusMessageIter fields;
    if (message == NULL || node == NULL
            || !dbus_message_iter_init(message, &outer)
            || dbus_message_iter_get_arg_type(&outer) != DBUS_TYPE_STRUCT) {
        return false;
    }
    dbus_message_iter_recurse(&outer, &fields);
    memset(node, 0, sizeof(*node));
    if (!cache_reference_from_iter(&fields, &node->reference)) return false;
    for (int skipped = 0; skipped < 5; skipped++) {
        if (!dbus_message_iter_next(&fields)) return false;
    }
    if (dbus_message_iter_get_arg_type(&fields) != DBUS_TYPE_ARRAY
            || !dbus_message_iter_next(&fields)
            || dbus_message_iter_get_arg_type(&fields) != DBUS_TYPE_STRING) {
        return false;
    }
    const char *name = NULL;
    dbus_message_iter_get_basic(&fields, &name);
    if (!dbus_message_iter_next(&fields)
            || dbus_message_iter_get_arg_type(&fields) != DBUS_TYPE_UINT32) {
        return false;
    }
    uint32_t role = 0;
    dbus_message_iter_get_basic(&fields, &role);
    if (!cache_window_role(role) || !dbus_message_iter_next(&fields)
            || dbus_message_iter_get_arg_type(&fields) != DBUS_TYPE_STRING) {
        return false;
    }
    const char *description = NULL;
    dbus_message_iter_get_basic(&fields, &description);
    if (!dbus_message_iter_next(&fields)
            || dbus_message_iter_get_arg_type(&fields) != DBUS_TYPE_ARRAY) {
        return false;
    }
    uint32_t states[2] = {0, 0};
    DBusMessageIter state_values;
    dbus_message_iter_recurse(&fields, &state_values);
    for (size_t index = 0; index < 2
            && dbus_message_iter_get_arg_type(&state_values)
                    == DBUS_TYPE_UINT32; index++) {
        dbus_message_iter_get_basic(&state_values, &states[index]);
        dbus_message_iter_next(&state_values);
    }
    snprintf(node->role, sizeof(node->role), "window");
    snprintf(node->text, sizeof(node->text), "%.*s",
            ARCHPHENE_ATSPI_TEXT_MAX, name == NULL ? "" : name);
    snprintf(node->description, sizeof(node->description), "%.*s",
            ARCHPHENE_ATSPI_TEXT_MAX,
            description == NULL ? "" : description);
    node->width = 1;
    node->height = 1;
    node->enabled = cache_state(states, 8);
    node->focusable = cache_state(states, 11);
    node->showing = cache_state(states, 25);
    node->visible = cache_state(states, 30);
    return true;
}

static bool transient_reference_matches(
        const ArchpheneAtspiReference *reference,
        const char *bus, const char *path) {
    return strcmp(reference->bus, bus) == 0
            && strcmp(reference->path, path) == 0;
}

static void remove_cached_window_locked(size_t index) {
    size_t remaining = state.cached_window_count - index - 1;
    memmove(&state.cached_windows[index], &state.cached_windows[index + 1],
            remaining * sizeof(state.cached_windows[0]));
    state.cached_window_count--;
}

static void upsert_cached_window_locked(const ArchpheneAtspiNode *node) {
    for (size_t index = 0; index < state.cached_window_count; index++) {
        ArchpheneAtspiReference *reference =
                &state.cached_windows[index].reference;
        if (transient_reference_matches(reference,
                node->reference.bus, node->reference.path)) {
            state.cached_windows[index] = *node;
            wake_worker();
            return;
        }
    }
    if (state.cached_window_count == MAX_CACHED_WINDOWS) {
        remove_cached_window_locked(0);
    }
    state.cached_windows[state.cached_window_count++] = *node;
    wake_worker();
}

static bool remove_cached_reference_locked(const char *bus, const char *path) {
    for (size_t index = 0; index < state.cached_window_count; index++) {
        if (transient_reference_matches(
                &state.cached_windows[index].reference, bus, path)) {
            remove_cached_window_locked(index);
            wake_worker();
            return true;
        }
    }
    return false;
}

static bool remove_cached_bus_locked(const char *bus) {
    bool removed = false;
    for (size_t index = 0; index < state.cached_window_count;) {
        if (strcmp(state.cached_windows[index].reference.bus, bus) == 0) {
            remove_cached_window_locked(index);
            removed = true;
        } else {
            index++;
        }
    }
    return removed;
}

static bool update_cached_state_locked(
        const char *bus, const char *path, const char *state_name, bool enabled) {
    for (size_t index = 0; index < state.cached_window_count; index++) {
        ArchpheneAtspiNode *node = &state.cached_windows[index];
        if (!transient_reference_matches(&node->reference, bus, path)) continue;
        if (strcmp(state_name, "showing") == 0) node->showing = enabled;
        else if (strcmp(state_name, "visible") == 0) node->visible = enabled;
        else return false;
        wake_worker();
        return true;
    }
    return false;
}

static void remove_transient_root_locked(size_t index) {
    size_t remaining = state.transient_root_count - index - 1;
    memmove(&state.transient_roots[index], &state.transient_roots[index + 1],
            remaining * sizeof(state.transient_roots[0]));
    memmove(&state.transient_window_roots[index],
            &state.transient_window_roots[index + 1],
            remaining * sizeof(state.transient_window_roots[0]));
    state.transient_root_count--;
}

static bool remove_captured_showing_roots_locked(
        const ArchpheneAtspiReference *captured, size_t captured_count) {
    bool removed = false;
    for (size_t index = 0; index < state.transient_root_count;) {
        bool matched = false;
        for (size_t captured_index = 0;
                !state.transient_window_roots[index]
                && captured_index < captured_count; captured_index++) {
            if (transient_reference_matches(
                    &state.transient_roots[index],
                    captured[captured_index].bus,
                    captured[captured_index].path)) {
                matched = true;
                break;
            }
        }
        if (matched) {
            remove_transient_root_locked(index);
            removed = true;
        } else {
            index++;
        }
    }
    if (removed) state.transient_generation++;
    return removed;
}
static void restore_captured_showing_roots_locked(
        const ArchpheneAtspiReference *captured, size_t captured_count) {
    bool restored = false;
    for (size_t captured_index = 0;
            captured_index < captured_count
            && state.transient_root_count < MAX_TRANSIENT_ROOTS;
            captured_index++) {
        bool found = false;
        for (size_t index = 0; index < state.transient_root_count; index++) {
            if (transient_reference_matches(
                    &state.transient_roots[index],
                    captured[captured_index].bus,
                    captured[captured_index].path)) {
                found = true;
                break;
            }
        }
        if (found) continue;
        size_t index = state.transient_root_count++;
        state.transient_roots[index] = captured[captured_index];
        state.transient_window_roots[index] = false;
        restored = true;
    }
    if (restored) state.transient_generation++;
}
static void update_transient_root_locked(
        const char *bus, const char *path, bool add, bool window_root) {
    size_t index = 0;
    while (index < state.transient_root_count) {
        ArchpheneAtspiReference *root = &state.transient_roots[index];
        if (transient_reference_matches(root, bus, path)) {
            if (!add) {
                remove_transient_root_locked(index);
                state.transient_generation++;
                wake_worker();
            } else if (window_root && !state.transient_window_roots[index]) {
                state.transient_window_roots[index] = true;
                state.transient_generation++;
                wake_worker();
            }
            return;
        }
        index++;
    }
    if (!add || state.transient_root_count >= MAX_TRANSIENT_ROOTS) return;
    for (size_t tree_index = 0; state.tree != NULL
            && tree_index < state.tree->count; tree_index++) {
        ArchpheneAtspiReference *published =
                &state.tree->nodes[tree_index].node.reference;
        if (!window_root
                && transient_reference_matches(published, bus, path)) return;
    }
    size_t root_index = state.transient_root_count++;
    ArchpheneAtspiReference *root = &state.transient_roots[root_index];
    snprintf(root->bus, sizeof(root->bus), "%s", bus);
    snprintf(root->path, sizeof(root->path), "%s", path);
    state.transient_window_roots[root_index] = window_root;
    state.transient_generation++;
    wake_worker();
}

static bool remove_transient_bus_locked(const char *bus) {
    bool removed = false;
    for (size_t index = 0; index < state.transient_root_count;) {
        ArchpheneAtspiReference *root = &state.transient_roots[index];
        if (strcmp(root->bus, bus) == 0) {
            remove_transient_root_locked(index);
            removed = true;
        } else {
            index++;
        }
    }
    if (removed) state.transient_generation++;
    return removed;
}

static bool parent_is_menu_bar(const ArchpheneAtspiTree *tree, int id) {
    if (tree == NULL) return false;
    int parent = 0;
    for (size_t index = 0; index < tree->count; index++) {
        if (tree->nodes[index].id == id) {
            parent = tree->nodes[index].parent;
            break;
        }
    }
    if (parent == 0) return false;
    for (size_t index = 0; index < tree->count; index++) {
        if (tree->nodes[index].id == parent) {
            return tree->nodes[index].node.menu_bar;
        }
    }
    return false;
}

static bool node_belongs_to_showing_transient_locked(
        const ArchpheneAtspiTree *tree, int id) {
    for (size_t depth = 0; tree != NULL && id > 0
            && depth <= tree->count; depth++) {
        const ArchpheneAtspiPublishedNode *node = NULL;
        for (size_t index = 0; index < tree->count; index++) {
            if (tree->nodes[index].id == id) {
                node = &tree->nodes[index];
                break;
            }
        }
        if (node == NULL) return false;
        for (size_t index = 0; index < state.transient_root_count; index++) {
            if (!state.transient_window_roots[index]
                    && transient_reference_matches(
                            &state.transient_roots[index],
                            node->node.reference.bus,
                            node->node.reference.path)) return true;
        }
        id = node->parent;
    }
    return false;
}

static int allocate_application_id_locked(void) {
    for (size_t attempt = 0; attempt <= MAX_APPLICATIONS; attempt++) {
        if (state.next_application_id == 0
                || state.next_application_id > INT32_MAX) {
            state.next_application_id = 1;
        }
        int candidate = (int)state.next_application_id++;
        bool used = false;
        for (size_t index = 0; index < state.application_count; index++) {
            if (state.applications[index].application_id == candidate) {
                used = true;
                break;
            }
        }
        if (!used) return candidate;
    }
    return -1;
}

static void deadline_after_millis(struct timespec *deadline, long millis) {
    clock_gettime(CLOCK_REALTIME, deadline);
    deadline->tv_sec += millis / 1000;
    deadline->tv_nsec += (millis % 1000) * 1000000L;
    if (deadline->tv_nsec >= 1000000000L) {
        deadline->tv_sec++;
        deadline->tv_nsec -= 1000000000L;
    }
}

static int base64url_value(unsigned char value) {
    if (value >= 'A' && value <= 'Z') return value - 'A';
    if (value >= 'a' && value <= 'z') return value - 'a' + 26;
    if (value >= '0' && value <= '9') return value - '0' + 52;
    if (value == '-') return 62;
    if (value == '_') return 63;
    return -1;
}

static int decode_text(const char *encoded, char *output, size_t capacity) {
    size_t encoded_length = strlen(encoded);
    size_t remainder = encoded_length % 4;
    if (capacity == 0 || remainder == 1) return -1;
    size_t decoded_length = encoded_length / 4 * 3
            + (remainder == 2 ? 1 : remainder == 3 ? 2 : 0);
    if (decoded_length + 1 > capacity) return -1;

    size_t length = 0;
    for (size_t index = 0; index < encoded_length; index += 4) {
        size_t available = encoded_length - index;
        int first = base64url_value((unsigned char)encoded[index]);
        int second = available > 1
                ? base64url_value((unsigned char)encoded[index + 1]) : -1;
        int third = available > 2
                ? base64url_value((unsigned char)encoded[index + 2]) : 0;
        int fourth = available > 3
                ? base64url_value((unsigned char)encoded[index + 3]) : 0;
        if (first < 0 || second < 0 || third < 0 || fourth < 0) return -1;
        unsigned char byte = (unsigned char)((first << 2) | (second >> 4));
        if (byte == '\0') return -1;
        output[length++] = (char)byte;
        if (available == 2) {
            if ((second & 0x0f) != 0) return -1;
            break;
        }
        byte = (unsigned char)((second << 4) | (third >> 2));
        if (byte == '\0') return -1;
        output[length++] = (char)byte;
        if (available == 3) {
            if ((third & 0x03) != 0) return -1;
            break;
        }
        byte = (unsigned char)((third << 6) | fourth);
        if (byte == '\0') return -1;
        output[length++] = (char)byte;
    }
    output[length] = '\0';
    return dbus_validate_utf8(output, NULL) ? 0 : -1;
}
static int parse_action(char *response, int *id,
        char **action, char **encoded_text) {
    char *status = response;
    char *id_text = strchr(status, '\t');
    if (id_text == NULL) return -1;
    *id_text++ = '\0';
    *action = strchr(id_text, '\t');
    if (*action == NULL) return -1;
    *(*action)++ = '\0';
    *encoded_text = strchr(*action, '\t');
    if (*encoded_text == NULL) return -1;
    *(*encoded_text)++ = '\0';
    if (strcmp(status, "OK") != 0 || **action == '\0'
            || strchr(*encoded_text, '\t') != NULL) return -1;
    char *end = NULL;
    errno = 0;
    long parsed = strtol(id_text, &end, 10);
    if (errno != 0 || end == id_text || *end != '\0'
            || parsed < 1 || parsed > 1000000) return -1;
    *id = (int)parsed;
    return 0;
}

static void process_event(void) {
    PendingEvent event;
    int id = 0;
    pthread_mutex_lock(&state.mutex);
    if (state.event_count > 0) {
        event = state.events[state.event_head];
        state.event_head = (state.event_head + 1) % MAX_EVENTS;
        state.event_count--;
        for (size_t index = 0; state.tree != NULL
                && index < state.tree->count; index++) {
            const ArchpheneAtspiReference *reference =
                    &state.tree->nodes[index].node.reference;
            if (strcmp(reference->bus, event.reference.bus) == 0
                    && strcmp(reference->path, event.reference.path) == 0) {
                id = state.tree->nodes[index].id;
                break;
            }
        }
    }
    pthread_mutex_unlock(&state.mutex);
    if (id == 0) return;
    char response[256] = {0};
    archphene_android_accessibility_event(
            id, event.type, response, sizeof(response));
}

static int activate_menu_pointer(int node_id) {
    if (node_id <= 0) return -1;
    char response[64] = {0};
    return archphene_android_accessibility_menu_fallback(
            node_id, response, sizeof(response));
}

static void process_action(DBusConnection *connection) {
    char response[ACTION_RESPONSE_MAX] = {0};
    if (archphene_android_take_accessibility_action(
            100, response, sizeof(response)) != 0
            || strncmp(response, "OK\t", 3) != 0) return;
    int id = 0;
    char *action = NULL;
    char *encoded_text = NULL;
    if (parse_action(response, &id, &action, &encoded_text) != 0) return;
    ArchpheneAtspiNode node;
    bool found_node = false;
    bool menu_bar_child = false;
    bool showing_root_action = false;
    bool popup_state_retired = false;
    bool click = strcmp(action, "click") == 0;
    ArchpheneAtspiReference captured_showing_roots[MAX_TRANSIENT_ROOTS];
    size_t captured_showing_root_count = 0;
    pthread_mutex_lock(&state.mutex);
    const ArchpheneAtspiNode *found =
            archphene_atspi_tree_find(state.tree, id);
    if (found != NULL) {
        node = *found;
        found_node = true;
        menu_bar_child = parent_is_menu_bar(state.tree, id);
        showing_root_action =
                node_belongs_to_showing_transient_locked(state.tree, id);
        if (click && (showing_root_action || node.menu_item)) {
            for (size_t index = 0; index < state.transient_root_count; index++) {
                if (!state.transient_window_roots[index]) {
                    captured_showing_roots[captured_showing_root_count++] =
                            state.transient_roots[index];
                }
            }
            bool showing_roots_pruned = remove_captured_showing_roots_locked(
                    captured_showing_roots, captured_showing_root_count);
            popup_state_retired = showing_roots_pruned || node.menu_item;
            if (popup_state_retired) state.suppress_retention = true;
        }
    }
    pthread_mutex_unlock(&state.mutex);
    if (!found_node) return;

    int result = -1;
    uint64_t transient_generation = 0;
    bool menu_bar_click = click && menu_bar_child;
    bool menu_click = click && (node.show_menu_action || menu_bar_child);
    if (click) {
        if (menu_click && !menu_bar_click) {
            pthread_mutex_lock(&state.mutex);
            transient_generation = state.transient_generation;
            pthread_mutex_unlock(&state.mutex);
        }
        result = menu_bar_click ? activate_menu_pointer(id)
                : archphene_atspi_client_click(connection, &node);
    } else if (strcmp(action, "focus") == 0) {
        result = archphene_atspi_client_focus(connection, &node);
    } else if (strcmp(action, "set-text") == 0) {
        char text[ARCHPHENE_ATSPI_TEXT_MAX + 1];
        if (decode_text(encoded_text, text, sizeof(text)) == 0) {
            result = archphene_atspi_client_set_text(connection, &node, text);
        }
    } else if (strcmp(action, "scroll-forward") == 0) {
        result = archphene_atspi_client_scroll(connection, &node, TRUE);
    } else if (strcmp(action, "scroll-backward") == 0) {
        result = archphene_atspi_client_scroll(connection, &node, FALSE);
    }
    if (result == 0 && menu_click && !menu_bar_click) {
        struct timespec deadline;
        deadline_after_millis(&deadline, 100);
        pthread_mutex_lock(&state.mutex);
        bool transient_changed = false;
        while (!state.stopping) {
            if (transient_generation != state.transient_generation) {
                transient_changed = true;
                if (state.transient_root_count > 0) break;
            }
            int wait_result = pthread_cond_timedwait(
                    &state.changed, &state.mutex, &deadline);
            if (wait_result == ETIMEDOUT) break;
        }
        bool menu_opened = transient_changed && state.transient_root_count > 0;
        pthread_mutex_unlock(&state.mutex);
        if (!menu_opened) activate_menu_pointer(id);
    }
    pthread_mutex_lock(&state.mutex);
    if (result == 0) {
        state.action_refresh_passes = ACTION_REFRESH_PASSES;
        wake_worker();
    } else if (popup_state_retired) {
        restore_captured_showing_roots_locked(
                captured_showing_roots, captured_showing_root_count);
        state.suppress_retention = false;
        wake_worker();
    }
    pthread_mutex_unlock(&state.mutex);
}

static size_t snapshot_applications(ArchpheneAtspiReference *applications) {
    pthread_mutex_lock(&state.mutex);
    size_t count = 0;
    for (size_t index = state.transient_root_count; index > 0; index--) {
        if (state.transient_window_roots[index - 1]) {
            applications[count++] = state.transient_roots[index - 1];
        }
    }
    for (size_t index = 0; index < state.transient_root_count; index++) {
        if (!state.transient_window_roots[index]) {
            applications[count++] = state.transient_roots[index];
        }
    }
    for (size_t index = 0; index < state.application_count; index++) {
        snprintf(applications[count].bus, sizeof(applications[count].bus),
                "%s", state.applications[index].bus);
        snprintf(applications[count].path, sizeof(applications[count].path),
                "%s", state.applications[index].path);
        count++;
    }
    if (state.action_refresh_passes > 0) state.action_refresh_passes--;
    state.dirty = false;
    pthread_mutex_unlock(&state.mutex);
    return count;
}

static size_t snapshot_cached_windows(ArchpheneAtspiNode *windows) {
    pthread_mutex_lock(&state.mutex);
    size_t count = 0;
    for (size_t index = 0; index < state.cached_window_count; index++) {
        ArchpheneAtspiNode *node = &state.cached_windows[index];
        if (node->showing && node->visible && node->text[0] != '\0') {
            windows[count++] = *node;
        }
    }
    pthread_mutex_unlock(&state.mutex);
    return count;
}

static void retain_dirty(void) {
    pthread_mutex_lock(&state.mutex);
    if (!state.stopping) state.dirty = true;
    pthread_mutex_unlock(&state.mutex);
}

static bool action_refresh_pending(void) {
    pthread_mutex_lock(&state.mutex);
    bool pending = state.action_refresh_passes > 0;
    pthread_mutex_unlock(&state.mutex);
    return pending;
}

static bool tree_incomplete(int result) {
    return result == ARCHPHENE_ATSPI_TREE_RETRY
            || result == ARCHPHENE_ATSPI_TREE_TRUNCATED;
}

static int rebuild_tree(DBusConnection *connection,
        ArchpheneAtspiTree **next_tree) {
    ArchpheneAtspiReference applications[ROOT_MAX];
    ArchpheneAtspiNode cached_windows[MAX_CACHED_WINDOWS];
    size_t count = snapshot_applications(applications);
    size_t cached_count = snapshot_cached_windows(cached_windows);
    int build_result = archphene_atspi_tree_build(
            connection, applications, count, *next_tree);
    bool cache_fallback = build_result < 0;
    if (cache_fallback) {
        memset(*next_tree, 0, sizeof(**next_tree));
        (*next_tree)->viewport_width = 1;
        (*next_tree)->viewport_height = 1;
    }
    size_t cached_added = 0;
    for (size_t index = 0; index < cached_count; index++) {
        int added = archphene_atspi_tree_add_root(
                *next_tree, &cached_windows[index]);
        if (added > 0) cached_added++;
    }
    if (cache_fallback && cached_added == 0) {
        retain_dirty();
        return build_result;
    }
    if (cache_fallback) build_result = ARCHPHENE_ATSPI_TREE_TRUNCATED;
    size_t retained = 0;
    bool suppress_retention = false;
    if (tree_incomplete(build_result)) {
        pthread_mutex_lock(&state.mutex);
        suppress_retention = state.suppress_retention;
        if (!suppress_retention && cached_added == 0) {
            retained = archphene_atspi_tree_retain_descendants(
                    state.tree, *next_tree);
        }
        pthread_mutex_unlock(&state.mutex);
    }
    if (cached_added > 0 && tree_incomplete(build_result)) {
        build_result = ARCHPHENE_ATSPI_TREE_COMPLETE;
    }
    if (archphene_atspi_tree_publish(*next_tree) != 0) {
        retain_dirty();
        return -1;
    }
    pthread_mutex_lock(&state.mutex);
    ArchpheneAtspiTree *previous = state.tree;
    state.tree = *next_tree;
    *next_tree = previous;
    if (suppress_retention) state.suppress_retention = false;
    pthread_mutex_unlock(&state.mutex);
    if (retained > 0) {
        fprintf(stderr, "AT-SPI retained %zu nodes during partial refresh\n",
                retained);
    }
    if (tree_incomplete(build_result)
            || (cached_added == 0 && action_refresh_pending())) {
        retain_dirty();
    }
    return build_result;
}
static void signal_startup(bool ready) {
    pthread_mutex_lock(&state.mutex);
    state.worker_ready = ready;
    state.worker_failed = !ready;
    pthread_cond_broadcast(&state.changed);
    pthread_mutex_unlock(&state.mutex);
}

static void *translator_worker(void *unused) {
    (void)unused;
    DBusError error = DBUS_ERROR_INIT;
    DBusConnection *connection = dbus_bus_get_private(DBUS_BUS_SESSION, &error);
    if (connection == NULL) {
        fprintf(stderr, "AT-SPI worker could not connect: %s\n",
                error.message == NULL ? "unknown error" : error.message);
        if (dbus_error_is_set(&error)) dbus_error_free(&error);
        signal_startup(false);
        return NULL;
    }
    dbus_connection_set_exit_on_disconnect(connection, FALSE);
    ArchpheneAtspiTree *next_tree = calloc(1, sizeof(*next_tree));
    if (next_tree == NULL) {
        dbus_connection_close(connection);
        dbus_connection_unref(connection);
        signal_startup(false);
        return NULL;
    }
    signal_startup(true);

    bool retrying = false;
    while (true) {
        pthread_mutex_lock(&state.mutex);
        bool stopping = state.stopping;
        bool dirty = state.dirty;
        pthread_mutex_unlock(&state.mutex);
        if (stopping) break;
        bool delay_retry = false;
        if (dirty) {
            int rebuild_result = rebuild_tree(connection, &next_tree);
            delay_retry = rebuild_result < 0
                    || tree_incomplete(rebuild_result);
            if (delay_retry && !retrying) {
                fprintf(stderr, "AT-SPI tree refresh deferred; retrying\n");
            } else if (!delay_retry && retrying) {
                fprintf(stderr, "AT-SPI tree refresh recovered\n");
            }
            retrying = delay_retry;
        }
        process_event();
        process_action(connection);
        pthread_mutex_lock(&state.mutex);
        bool delay_action_refresh = state.action_refresh_passes > 0;
        if (!state.stopping
                && (!state.dirty || delay_retry || delay_action_refresh)) {
            struct timespec deadline;
            long delay = delay_retry ? REBUILD_RETRY_MILLIS
                    : delay_action_refresh ? ACTION_REFRESH_MILLIS : 25;
            deadline_after_millis(&deadline, delay);
            pthread_cond_timedwait(&state.changed, &state.mutex, &deadline);
        }
        pthread_mutex_unlock(&state.mutex);
    }
    free(next_tree);
    dbus_connection_close(connection);
    dbus_connection_unref(connection);
    return NULL;
}

int archphene_atspi_translator_start(void) {
    pthread_mutex_lock(&state.mutex);
    if (state.started) {
        pthread_mutex_unlock(&state.mutex);
        return 0;
    }
    state.tree = calloc(1, sizeof(*state.tree));
    if (state.tree == NULL) {
        pthread_mutex_unlock(&state.mutex);
        return -1;
    }
    state.started = true;
    state.stopping = false;
    state.worker_ready = false;
    state.worker_failed = false;
    state.dirty = true;
    int result = pthread_create(
            &state.worker, NULL, translator_worker, NULL);
    if (result != 0) {
        free(state.tree);
        state.tree = NULL;
        state.started = false;
        pthread_mutex_unlock(&state.mutex);
        return -1;
    }
    state.worker_created = true;
    while (!state.worker_ready && !state.worker_failed) {
        pthread_cond_wait(&state.changed, &state.mutex);
    }
    bool failed = state.worker_failed;
    pthread_mutex_unlock(&state.mutex);
    if (failed) {
        archphene_atspi_translator_stop();
        return -1;
    }
    return 0;
}

void archphene_atspi_translator_stop(void) {
    pthread_mutex_lock(&state.mutex);
    if (!state.started) {
        pthread_mutex_unlock(&state.mutex);
        return;
    }
    state.stopping = true;
    pthread_cond_broadcast(&state.changed);
    bool join = state.worker_created;
    pthread_t worker = state.worker;
    pthread_mutex_unlock(&state.mutex);
    if (join) pthread_join(worker, NULL);

    pthread_mutex_lock(&state.mutex);
    free(state.tree);
    state.tree = NULL;
    state.started = false;
    state.stopping = false;
    state.worker_created = false;
    state.worker_ready = false;
    state.worker_failed = false;
    state.dirty = false;
    state.action_refresh_passes = 0;
    state.application_count = 0;
    state.transient_root_count = 0;
    state.cached_window_count = 0;
    state.transient_generation = 0;
    state.next_application_id = 1;
    state.event_head = 0;
    state.event_count = 0;
    pthread_mutex_unlock(&state.mutex);
}
int archphene_atspi_translator_register(
        const char *bus, const char *path, int *application_id) {
    if (bus == NULL || path == NULL || application_id == NULL
            || bus[0] != ':' || strlen(bus) >= ARCHPHENE_ATSPI_BUS_MAX
            || strlen(path) >= ARCHPHENE_ATSPI_PATH_MAX
            || !dbus_validate_path(path, NULL)) return -1;
    pthread_mutex_lock(&state.mutex);
    for (size_t index = 0; index < state.application_count; index++) {
        if (strcmp(state.applications[index].bus, bus) == 0
                && strcmp(state.applications[index].path, path) == 0) {
            *application_id = state.applications[index].application_id;
            wake_worker();
            pthread_mutex_unlock(&state.mutex);
            return 0;
        }
    }
    if (!state.started || state.application_count >= MAX_APPLICATIONS) {
        pthread_mutex_unlock(&state.mutex);
        return -1;
    }
    int id = allocate_application_id_locked();
    if (id < 1) {
        pthread_mutex_unlock(&state.mutex);
        return -1;
    }
    Registration *entry = &state.applications[state.application_count++];
    snprintf(entry->bus, sizeof(entry->bus), "%s", bus);
    snprintf(entry->path, sizeof(entry->path), "%s", path);
    entry->application_id = id;
    *application_id = id;
    wake_worker();
    pthread_mutex_unlock(&state.mutex);
    return 0;
}

void archphene_atspi_translator_unregister(
        const char *bus, const char *path) {
    if (bus == NULL || path == NULL) return;
    pthread_mutex_lock(&state.mutex);
    for (size_t index = 0; index < state.application_count;) {
        Registration *entry = &state.applications[index];
        if (strcmp(entry->bus, bus) == 0
                && strcmp(entry->path, path) == 0) {
            memmove(entry, entry + 1,
                    (state.application_count - index - 1) * sizeof(*entry));
            state.application_count--;
            wake_worker();
        } else {
            index++;
        }
    }
    bool bus_registered = false;
    for (size_t index = 0; index < state.application_count; index++) {
        if (strcmp(state.applications[index].bus, bus) == 0) {
            bus_registered = true;
            break;
        }
    }
    if (!bus_registered && remove_transient_bus_locked(bus)) wake_worker();
    pthread_mutex_unlock(&state.mutex);
}

void archphene_atspi_translator_disconnect(const char *bus) {
    if (bus == NULL) return;
    pthread_mutex_lock(&state.mutex);
    bool affected = false;
    for (size_t index = 0; index < state.application_count;) {
        Registration *entry = &state.applications[index];
        if (strcmp(entry->bus, bus) == 0) {
            memmove(entry, entry + 1,
                    (state.application_count - index - 1) * sizeof(*entry));
            state.application_count--;
            affected = true;
        } else {
            index++;
        }
    }
    if (remove_transient_bus_locked(bus)) affected = true;
    if (remove_cached_bus_locked(bus)) affected = true;
    for (size_t index = 0; !affected && state.tree != NULL
            && index < state.tree->count; index++) {
        if (strcmp(state.tree->nodes[index].node.reference.bus, bus) == 0) {
            affected = true;
        }
    }
    if (affected) wake_worker();
    pthread_mutex_unlock(&state.mutex);
}

dbus_bool_t archphene_atspi_translator_has_bus(const char *bus) {
    if (bus == NULL) return FALSE;
    pthread_mutex_lock(&state.mutex);
    dbus_bool_t found = FALSE;
    for (size_t index = 0; index < state.application_count; index++) {
        if (strcmp(state.applications[index].bus, bus) == 0) {
            found = TRUE;
            break;
        }
    }
    for (size_t index = 0; !found && index < state.transient_root_count; index++) {
        if (strcmp(state.transient_roots[index].bus, bus) == 0) found = TRUE;
    }
    for (size_t index = 0; !found && state.tree != NULL
            && index < state.tree->count; index++) {
        if (strcmp(state.tree->nodes[index].node.reference.bus, bus) == 0) {
            found = TRUE;
        }
    }
    pthread_mutex_unlock(&state.mutex);
    return found;
}

static void handle_cache_signal(DBusMessage *message) {
    const char *member = dbus_message_get_member(message);
    if (member == NULL) return;
    if (strcmp(member, "AddAccessible") == 0) {
        ArchpheneAtspiNode node;
        if (!parse_cache_window(message, &node)) return;
        pthread_mutex_lock(&state.mutex);
        upsert_cached_window_locked(&node);
        pthread_mutex_unlock(&state.mutex);
        fprintf(stderr, "AT-SPI cached window bus=%s path=%s showing=%d "
                "visible=%d title=%.*s\n", node.reference.bus,
                node.reference.path, node.showing, node.visible,
                80, node.text);
    } else if (strcmp(member, "RemoveAccessible") == 0) {
        DBusMessageIter iter;
        ArchpheneAtspiReference reference;
        if (!dbus_message_iter_init(message, &iter)
                || !cache_reference_from_iter(&iter, &reference)) return;
        pthread_mutex_lock(&state.mutex);
        bool removed = remove_cached_reference_locked(
                reference.bus, reference.path);
        pthread_mutex_unlock(&state.mutex);
        if (removed) {
            fprintf(stderr, "AT-SPI removed cached window bus=%s path=%s\n",
                    reference.bus, reference.path);
        }
    }
}

void archphene_atspi_translator_event(DBusMessage *message) {
    const char *bus = dbus_message_get_sender(message);
    const char *path = dbus_message_get_path(message);
    const char *interface = dbus_message_get_interface(message);
    const char *member = dbus_message_get_member(message);
    if (bus == NULL || path == NULL || interface == NULL || member == NULL
            || strlen(bus) >= ARCHPHENE_ATSPI_BUS_MAX
            || strlen(path) >= ARCHPHENE_ATSPI_PATH_MAX) return;
    if (strcmp(interface, "org.a11y.atspi.Cache") == 0) {
        handle_cache_signal(message);
        return;
    }

    const char *type = "content";
    int transient_change = 0;
    bool transient_window = false;
    const char *cached_state_name = NULL;
    bool cached_state_enabled = false;
    if (strcmp(interface, "org.a11y.atspi.Event.Window") == 0) {
        type = "window";
        transient_window = true;
        if (strcmp(member, "Create") == 0) transient_change = 1;
        else if (strcmp(member, "Destroy") == 0
                || strcmp(member, "Close") == 0) transient_change = -1;
    } else if (strcmp(member, "TextChanged") == 0
            || strcmp(member, "TextCaretMoved") == 0
            || strcmp(member, "TextSelectionChanged") == 0) {
        type = "text";
    } else if (strcmp(member, "SelectionChanged") == 0
            || strcmp(member, "ActiveDescendantChanged") == 0) {
        type = "selected";
    } else if (strcmp(member, "StateChanged") == 0) {
        DBusMessageIter arguments;
        if (dbus_message_iter_init(message, &arguments)
                && dbus_message_iter_get_arg_type(&arguments)
                        == DBUS_TYPE_STRING) {
            const char *state_name = NULL;
            dbus_message_iter_get_basic(&arguments, &state_name);
            if (state_name != NULL && dbus_message_iter_next(&arguments)
                    && dbus_message_iter_get_arg_type(&arguments)
                            == DBUS_TYPE_INT32) {
                int32_t enabled = 0;
                dbus_message_iter_get_basic(&arguments, &enabled);
                if (strcmp(state_name, "focused") == 0 && enabled != 0) {
                    type = "focus";
                } else if (strcmp(state_name, "showing") == 0) {
                    transient_change = enabled != 0 ? 1 : -1;
                    cached_state_name = state_name;
                    cached_state_enabled = enabled != 0;
                } else if (strcmp(state_name, "visible") == 0) {
                    cached_state_name = state_name;
                    cached_state_enabled = enabled != 0;
                }
            }
        }
    }

    pthread_mutex_lock(&state.mutex);
    if (transient_change != 0) {
        update_transient_root_locked(
                bus, path, transient_change > 0, transient_window);
    }
    if (cached_state_name != NULL) {
        update_cached_state_locked(
                bus, path, cached_state_name, cached_state_enabled);
    }
    for (size_t offset = 0; offset < state.event_count; offset++) {
        size_t index = (state.event_head + offset) % MAX_EVENTS;
        PendingEvent *queued = &state.events[index];
        if (strcmp(queued->reference.bus, bus) == 0
                && strcmp(queued->reference.path, path) == 0
                && strcmp(queued->type, type) == 0) {
            wake_worker();
            pthread_mutex_unlock(&state.mutex);
            return;
        }
    }
    if (state.event_count == MAX_EVENTS) {
        state.event_head = (state.event_head + 1) % MAX_EVENTS;
        state.event_count--;
    }
    size_t tail = (state.event_head + state.event_count) % MAX_EVENTS;
    PendingEvent *event = &state.events[tail];
    snprintf(event->reference.bus, sizeof(event->reference.bus), "%s", bus);
    snprintf(event->reference.path, sizeof(event->reference.path), "%s", path);
    snprintf(event->type, sizeof(event->type), "%s", type);
    state.event_count++;
    wake_worker();
    pthread_mutex_unlock(&state.mutex);
}
void archphene_atspi_translator_mark_dirty(void) {
    pthread_mutex_lock(&state.mutex);
    if (state.started) wake_worker();
    pthread_mutex_unlock(&state.mutex);
}