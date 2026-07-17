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
#define ACTION_RESPONSE_MAX 4096
#define MAX_EVENTS 64
#define REBUILD_RETRY_MILLIS 250

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
    Registration applications[MAX_APPLICATIONS];
    size_t application_count;
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
    pthread_mutex_lock(&state.mutex);
    const ArchpheneAtspiNode *found =
            archphene_atspi_tree_find(state.tree, id);
    if (found != NULL) {
        node = *found;
        found_node = true;
    }
    pthread_mutex_unlock(&state.mutex);
    if (!found_node) return;

    int result = -1;
    if (strcmp(action, "click") == 0) {
        result = archphene_atspi_client_click(connection, &node);
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
    if (result == 0) {
        pthread_mutex_lock(&state.mutex);
        wake_worker();
        pthread_mutex_unlock(&state.mutex);
    }
}

static size_t snapshot_applications(ArchpheneAtspiReference *applications) {
    pthread_mutex_lock(&state.mutex);
    size_t count = state.application_count;
    for (size_t index = 0; index < count; index++) {
        snprintf(applications[index].bus, sizeof(applications[index].bus),
                "%s", state.applications[index].bus);
        snprintf(applications[index].path, sizeof(applications[index].path),
                "%s", state.applications[index].path);
    }
    state.dirty = false;
    pthread_mutex_unlock(&state.mutex);
    return count;
}

static void retain_dirty(void) {
    pthread_mutex_lock(&state.mutex);
    if (!state.stopping) state.dirty = true;
    pthread_mutex_unlock(&state.mutex);
}

static int rebuild_tree(DBusConnection *connection,
        ArchpheneAtspiTree **next_tree) {
    ArchpheneAtspiReference applications[MAX_APPLICATIONS];
    size_t count = snapshot_applications(applications);
    int build_result = archphene_atspi_tree_build(
            connection, applications, count, *next_tree);
    if (build_result < 0 || build_result == ARCHPHENE_ATSPI_TREE_RETRY) {
        retain_dirty();
        return build_result;
    }
    if (archphene_atspi_tree_publish(*next_tree) != 0) {
        retain_dirty();
        return -1;
    }
    pthread_mutex_lock(&state.mutex);
    ArchpheneAtspiTree *previous = state.tree;
    state.tree = *next_tree;
    *next_tree = previous;
    pthread_mutex_unlock(&state.mutex);
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
                    || rebuild_result == ARCHPHENE_ATSPI_TREE_RETRY;
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
        if (!state.stopping && (!state.dirty || delay_retry)) {
            struct timespec deadline;
            deadline_after_millis(&deadline,
                    delay_retry ? REBUILD_RETRY_MILLIS : 25);
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
    state.application_count = 0;
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
    for (size_t index = 0; !found && state.tree != NULL
            && index < state.tree->count; index++) {
        if (strcmp(state.tree->nodes[index].node.reference.bus, bus) == 0) {
            found = TRUE;
        }
    }
    pthread_mutex_unlock(&state.mutex);
    return found;
}

void archphene_atspi_translator_event(DBusMessage *message) {
    const char *bus = dbus_message_get_sender(message);
    const char *path = dbus_message_get_path(message);
    const char *interface = dbus_message_get_interface(message);
    const char *member = dbus_message_get_member(message);
    if (bus == NULL || path == NULL || interface == NULL || member == NULL
            || strlen(bus) >= ARCHPHENE_ATSPI_BUS_MAX
            || strlen(path) >= ARCHPHENE_ATSPI_PATH_MAX) return;

    const char *type = "content";
    if (strcmp(interface, "org.a11y.atspi.Event.Window") == 0) {
        type = "window";
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
            if (state_name != NULL && strcmp(state_name, "focused") == 0
                    && dbus_message_iter_next(&arguments)
                    && dbus_message_iter_get_arg_type(&arguments)
                            == DBUS_TYPE_INT32) {
                int32_t enabled = 0;
                dbus_message_iter_get_basic(&arguments, &enabled);
                if (enabled != 0) type = "focus";
            }
        }
    }

    pthread_mutex_lock(&state.mutex);
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