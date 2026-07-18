#include "archphene_atspi_publish.h"

#include "archphene_android.h"

#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#define JSON_MAX (1024 * 1024)
#define NODE_ID_MAX 1000000
#define VIEWPORT_MAX 16384
#define TRAVERSAL_MAX (ARCHPHENE_ATSPI_NODE_MAX + 16)
#define TREE_BUILD_BUDGET_MILLIS 5000

typedef struct {
    ArchpheneAtspiReference reference;
    int parent;
} PendingNode;

typedef struct {
    char *data;
    size_t length;
    size_t capacity;
    int failed;
} JsonBuffer;

static int same_reference(const ArchpheneAtspiReference *left,
        const ArchpheneAtspiReference *right) {
    return strcmp(left->bus, right->bus) == 0
            && strcmp(left->path, right->path) == 0;
}

static uint32_t hash_reference(const ArchpheneAtspiReference *reference) {
    uint32_t hash = 2166136261u;
    const char *parts[] = {reference->bus, "\n", reference->path};
    for (size_t part = 0; part < 3; part++) {
        for (size_t index = 0; parts[part][index] != '\0'; index++) {
            hash ^= (unsigned char)parts[part][index];
            hash *= 16777619u;
        }
    }
    return hash;
}

static int stable_id(const ArchpheneAtspiTree *tree,
        const ArchpheneAtspiReference *reference) {
    int id = (int)(hash_reference(reference) % NODE_ID_MAX) + 1;
    for (int attempts = 0; attempts < NODE_ID_MAX; attempts++) {
        int occupied = 0;
        for (size_t index = 0; index < tree->count; index++) {
            if (tree->nodes[index].id == id) {
                if (same_reference(&tree->nodes[index].node.reference, reference)) {
                    return id;
                }
                occupied = 1;
                break;
            }
        }
        if (!occupied) return id;
        id = id == NODE_ID_MAX ? 1 : id + 1;
    }
    return -1;
}

static int clamp_position(int value) {
    if (value < -VIEWPORT_MAX) return -VIEWPORT_MAX;
    if (value > VIEWPORT_MAX) return VIEWPORT_MAX;
    return value;
}

static int clamp_size(int value) {
    if (value < 1) return 1;
    if (value > VIEWPORT_MAX) return VIEWPORT_MAX;
    return value;
}

static void copy_title(char *target, size_t capacity, const char *source) {
    if (capacity == 0) return;
    if (source == NULL) source = "";
    snprintf(target, capacity, "%.*s", (int)(capacity - 1), source);
}

static int seen_reference(const ArchpheneAtspiReference *seen, size_t count,
        const ArchpheneAtspiReference *reference) {
    for (size_t index = 0; index < count; index++) {
        if (same_reference(&seen[index], reference)) return 1;
    }
    return 0;
}

static const ArchpheneAtspiPublishedNode *tree_find_id(
        const ArchpheneAtspiTree *tree, int id) {
    for (size_t index = 0; index < tree->count; index++) {
        if (tree->nodes[index].id == id) return &tree->nodes[index];
    }
    return NULL;
}

static const ArchpheneAtspiPublishedNode *tree_find_reference(
        const ArchpheneAtspiTree *tree,
        const ArchpheneAtspiReference *reference) {
    for (size_t index = 0; index < tree->count; index++) {
        if (same_reference(&tree->nodes[index].node.reference, reference)) {
            return &tree->nodes[index];
        }
    }
    return NULL;
}

static const ArchpheneAtspiPublishedNode *tree_root(
        const ArchpheneAtspiTree *tree,
        const ArchpheneAtspiPublishedNode *node) {
    const ArchpheneAtspiPublishedNode *current = node;
    for (size_t depth = 0; current->parent != 0 && depth < tree->count; depth++) {
        current = tree_find_id(tree, current->parent);
        if (current == NULL) return NULL;
    }
    return current->parent == 0 ? current : NULL;
}

size_t archphene_atspi_tree_retain_descendants(
        const ArchpheneAtspiTree *previous,
        ArchpheneAtspiTree *current) {
    if (previous == NULL || current == NULL || previous->count == 0
            || current->count == 0) return 0;

    size_t retained = 0;
    int changed;
    do {
        changed = 0;
        for (size_t index = 0; index < previous->count
                && current->count < ARCHPHENE_ATSPI_NODE_MAX; index++) {
            const ArchpheneAtspiPublishedNode *node = &previous->nodes[index];
            if (node->parent == 0 || tree_find_reference(
                    current, &node->node.reference) != NULL) continue;
            const ArchpheneAtspiPublishedNode *previous_parent =
                    tree_find_id(previous, node->parent);
            const ArchpheneAtspiPublishedNode *previous_root =
                    tree_root(previous, node);
            if (previous_parent == NULL || previous_root == NULL
                    || tree_find_reference(current,
                        &previous_root->node.reference) == NULL) continue;
            const ArchpheneAtspiPublishedNode *current_parent =
                    tree_find_reference(current,
                            &previous_parent->node.reference);
            if (current_parent == NULL) continue;

            ArchpheneAtspiPublishedNode copy = *node;
            copy.id = stable_id(current, &copy.node.reference);
            if (copy.id < 1) continue;
            copy.parent = current_parent->id;
            current->nodes[current->count++] = copy;
            retained++;
            changed = 1;
        }
    } while (changed && current->count < ARCHPHENE_ATSPI_NODE_MAX);
    return retained;
}

int archphene_atspi_tree_add_root(
        ArchpheneAtspiTree *tree, const ArchpheneAtspiNode *node) {
    if (tree == NULL || node == NULL) return -1;
    if (tree_find_reference(tree, &node->reference) != NULL) return 0;
    if (tree->count >= ARCHPHENE_ATSPI_NODE_MAX) return -1;
    int id = stable_id(tree, &node->reference);
    if (id < 1) return -1;
    ArchpheneAtspiPublishedNode *published = &tree->nodes[tree->count++];
    published->id = id;
    published->parent = 0;
    published->node = *node;
    published->node.x = clamp_position(node->x);
    published->node.y = clamp_position(node->y);
    published->node.width = clamp_size(node->width);
    published->node.height = clamp_size(node->height);
    copy_title(published->window_title,
            sizeof(published->window_title), node->text);
    if (published->node.width > tree->viewport_width)
        tree->viewport_width = published->node.width;
    if (published->node.height > tree->viewport_height)
        tree->viewport_height = published->node.height;
    return 1;
}

static int build_deadline(struct timespec *deadline) {
    if (clock_gettime(CLOCK_MONOTONIC, deadline) != 0) return -1;
    deadline->tv_sec += TREE_BUILD_BUDGET_MILLIS / 1000;
    deadline->tv_nsec += (TREE_BUILD_BUDGET_MILLIS % 1000) * 1000000L;
    if (deadline->tv_nsec >= 1000000000L) {
        deadline->tv_sec++;
        deadline->tv_nsec -= 1000000000L;
    }
    return 0;
}

static int deadline_expired(const struct timespec *deadline) {
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return 1;
    return now.tv_sec > deadline->tv_sec
            || (now.tv_sec == deadline->tv_sec
                && now.tv_nsec >= deadline->tv_nsec);
}

int archphene_atspi_tree_build(DBusConnection *connection,
        const ArchpheneAtspiReference *applications, size_t application_count,
        ArchpheneAtspiTree *tree) {
    if (connection == NULL || tree == NULL
            || (application_count > 0 && applications == NULL)) return -1;
    struct timespec deadline;
    if (build_deadline(&deadline) != 0) return -1;
    memset(tree, 0, sizeof(*tree));
    tree->viewport_width = 1;
    tree->viewport_height = 1;
    if (application_count == 0) return 0;

    PendingNode *pending = calloc(TRAVERSAL_MAX, sizeof(*pending));
    ArchpheneAtspiReference *seen = calloc(
            TRAVERSAL_MAX, sizeof(*seen));
    ArchpheneAtspiReference *children = calloc(
            ARCHPHENE_ATSPI_CHILD_MAX, sizeof(*children));
    if (pending == NULL || seen == NULL || children == NULL) {
        free(pending);
        free(seen);
        free(children);
        return -1;
    }
    size_t pending_count = 0;
    for (size_t index = 0; index < application_count; index++) {
        if (pending_count >= TRAVERSAL_MAX) goto fail;
        pending[pending_count].reference = applications[index];
        pending[pending_count++].parent = 0;
    }

    size_t cursor = 0;
    size_t seen_count = 0;
    int retry = 0;
    int truncated = 0;
    while (cursor < pending_count) {
        if (deadline_expired(&deadline)) {
            truncated = 1;
            break;
        }
        PendingNode current = pending[cursor++];
        if (seen_reference(seen, seen_count, &current.reference)) continue;
        if (seen_count >= TRAVERSAL_MAX) {
            truncated = 1;
            break;
        }
        seen[seen_count++] = current.reference;

        ArchpheneAtspiNode node;
        size_t child_count = 0;
        int read_result = archphene_atspi_client_read_node(
                connection, &current.reference, &node, children,
                ARCHPHENE_ATSPI_CHILD_MAX, &child_count);

        if (read_result < 0) {
            if (current.parent == 0) {
                fprintf(stderr, "AT-SPI supplemental root path=%s result=%d\n",
                        current.reference.path, read_result);
            }
            truncated = 1;
            continue;
        }
        if (read_result > 0) truncated = 1;
        if (current.parent == 0 && !node.application) {
            fprintf(stderr, "AT-SPI supplemental root path=%s role=%s text=%.*s "
                    "showing=%d visible=%d children=%zu result=%d\n",
                    current.reference.path, node.role, 80, node.text,
                    node.showing, node.visible, child_count, read_result);
        }
        if (node.application) {
            if (child_count == 0) retry = 1;
            size_t available = TRAVERSAL_MAX - pending_count;
            size_t enqueue = child_count < available ? child_count : available;
            if (enqueue < child_count) truncated = 1;
            for (size_t index = 0; index < enqueue; index++) {
                pending[pending_count].reference = children[index];
                pending[pending_count++].parent = 0;
            }
            continue;
        }
        if (!node.showing || !node.visible) {
            if (current.parent != 0 || strcmp(node.role, "window") == 0) continue;
            size_t available = TRAVERSAL_MAX - pending_count;
            size_t enqueue = child_count < available ? child_count : available;
            if (enqueue < child_count) truncated = 1;
            for (size_t index = 0; index < enqueue; index++) {
                pending[pending_count].reference = children[index];
                pending[pending_count++].parent = 0;
            }
            continue;
        }
        if (tree->count >= ARCHPHENE_ATSPI_NODE_MAX) {
            truncated = 1;
            break;
        }
        int id = stable_id(tree, &current.reference);
        if (id < 1) goto fail;
        ArchpheneAtspiPublishedNode *published = &tree->nodes[tree->count++];
        published->id = id;
        published->parent = current.parent;
        published->node = node;
        published->node.x = clamp_position(node.x);
        published->node.y = clamp_position(node.y);
        published->node.width = clamp_size(node.width);
        published->node.height = clamp_size(node.height);
        if (current.parent == 0) {
            copy_title(published->window_title,
                    sizeof(published->window_title), node.text);
            if (published->node.width > tree->viewport_width)
                tree->viewport_width = published->node.width;
            if (published->node.height > tree->viewport_height)
                tree->viewport_height = published->node.height;
        }
        size_t available = TRAVERSAL_MAX - pending_count;
        size_t enqueue = child_count < available ? child_count : available;
        if (enqueue < child_count) truncated = 1;
        for (size_t index = 0; index < enqueue; index++) {
            pending[pending_count].reference = children[index];
            pending[pending_count++].parent = id;
        }
    }
    if (tree->count == 0) goto fail;
    free(pending);
    free(seen);
    free(children);
    if (retry) return ARCHPHENE_ATSPI_TREE_RETRY;
    return truncated ? ARCHPHENE_ATSPI_TREE_TRUNCATED
            : ARCHPHENE_ATSPI_TREE_COMPLETE;

fail:
    free(pending);
    free(seen);
    free(children);
    memset(tree, 0, sizeof(*tree));
    return -1;
}

static void json_raw(JsonBuffer *buffer, const char *value, size_t length) {
    if (buffer->failed || length > buffer->capacity - buffer->length) {
        buffer->failed = 1;
        return;
    }
    memcpy(buffer->data + buffer->length, value, length);
    buffer->length += length;
}

static void json_format(JsonBuffer *buffer, const char *format, ...) {
    if (buffer->failed || buffer->length >= buffer->capacity) {
        buffer->failed = 1;
        return;
    }
    va_list arguments;
    va_start(arguments, format);
    int written = vsnprintf(buffer->data + buffer->length,
            buffer->capacity - buffer->length, format, arguments);
    va_end(arguments);
    if (written < 0 || (size_t)written >= buffer->capacity - buffer->length) {
        buffer->failed = 1;
        return;
    }
    buffer->length += (size_t)written;
}

static void json_string(JsonBuffer *buffer, const char *value) {
    json_raw(buffer, "\"", 1);
    for (size_t index = 0; !buffer->failed && value[index] != '\0'; index++) {
        unsigned char current = (unsigned char)value[index];
        if (current == '"' || current == '\\') {
            char escaped[2] = {'\\', (char)current};
            json_raw(buffer, escaped, sizeof(escaped));
        } else if (current == '\b') json_raw(buffer, "\\b", 2);
        else if (current == '\f') json_raw(buffer, "\\f", 2);
        else if (current == '\n') json_raw(buffer, "\\n", 2);
        else if (current == '\r') json_raw(buffer, "\\r", 2);
        else if (current == '\t') json_raw(buffer, "\\t", 2);
        else if (current < 0x20) json_format(buffer, "\\u%04x", current);
        else json_raw(buffer, (const char *)&value[index], 1);
    }
    json_raw(buffer, "\"", 1);
}

static void json_boolean(JsonBuffer *buffer, dbus_bool_t value) {
    if (value) json_raw(buffer, "true", 4);
    else json_raw(buffer, "false", 5);
}

static int render_tree(const ArchpheneAtspiTree *tree,
        char **json, size_t *json_length) {
    char *storage = malloc(JSON_MAX);
    if (storage == NULL) return -1;
    JsonBuffer output = {.data = storage, .capacity = JSON_MAX};
    if (tree->count == 0) {
        static const char clear_tree[] =
                "{\"viewportWidth\":1,\"viewportHeight\":1,"
                "\"clear\":true,\"nodes\":[]}";
        json_raw(&output, clear_tree, sizeof(clear_tree) - 1);
    } else {
        json_format(&output, "{\"viewportWidth\":%d,\"viewportHeight\":%d,"
                "\"nodes\":[", tree->viewport_width, tree->viewport_height);
        size_t emitted = 0;
        for (size_t index = 0; index < tree->count; index++) {
            size_t checkpoint = output.length;
            const ArchpheneAtspiPublishedNode *entry = &tree->nodes[index];
            const ArchpheneAtspiNode *node = &entry->node;
            if (emitted > 0) json_raw(&output, ",", 1);
            json_format(&output, "{\"id\":%d,\"parent\":%d,\"role\":",
                    entry->id, entry->parent);
            json_string(&output, node->role);
            json_raw(&output, ",\"text\":", 8);
            json_string(&output, node->text);
            json_raw(&output, ",\"description\":", 15);
            json_string(&output, node->description);
            if (entry->parent == 0) {
                json_raw(&output, ",\"windowTitle\":", 15);
                json_string(&output, entry->window_title);
            }
            json_format(&output,
                    ",\"x\":%d,\"y\":%d,\"width\":%d,\"height\":%d,"
                    "\"enabled\":", node->x, node->y,
                    node->width, node->height);
            json_boolean(&output, node->enabled);
            json_raw(&output, ",\"focusable\":", 13);
            json_boolean(&output, node->focusable);
            json_raw(&output, ",\"clickable\":", 13);
            json_boolean(&output, node->clickable);
            json_raw(&output, ",\"editable\":", 12);
            json_boolean(&output, node->editable);
            json_raw(&output, ",\"checkable\":", 13);
            json_boolean(&output, node->checkable);
            json_raw(&output, ",\"checked\":", 11);
            json_boolean(&output, node->checked);
            json_raw(&output, ",\"password\":", 12);
            json_boolean(&output, node->password);
            json_raw(&output, ",\"scrollForward\":", 17);
            json_boolean(&output, node->scroll_forward_action >= 0);
            json_raw(&output, ",\"scrollBackward\":", 18);
            json_boolean(&output, node->scroll_backward_action >= 0);
            json_raw(&output, "}", 1);
            if (output.failed || output.length + 2 > output.capacity) {
                output.failed = 0;
                output.length = checkpoint;
                break;
            }
            emitted++;
        }
        if (emitted == 0) output.failed = 1;
        json_raw(&output, "]}", 2);
    }
    if (output.failed || output.length == 0 || output.length >= JSON_MAX) {
        free(storage);
        return -1;
    }
    *json = storage;
    *json_length = output.length;
    return 0;
}

static int write_all(int descriptor, const char *data, size_t length) {
    size_t offset = 0;
    while (offset < length) {
        ssize_t written = write(descriptor, data + offset, length - offset);
        if (written < 0 && errno == EINTR) continue;
        if (written <= 0) return -1;
        offset += (size_t)written;
    }
    return 0;
}

int archphene_atspi_tree_publish(const ArchpheneAtspiTree *tree) {
    if (tree == NULL) return -1;
    char *json = NULL;
    size_t json_length = 0;
    if (render_tree(tree, &json, &json_length) != 0) return -1;
    const char *runtime = getenv("ARCHPHENE_RUNTIME_DIR");
    if (runtime == NULL || runtime[0] != '/') {
        free(json);
        return -1;
    }
    char path[1024];
    int length = snprintf(path, sizeof(path),
            "%s/.archphene-atspi-XXXXXX", runtime);
    if (length <= 0 || (size_t)length >= sizeof(path)) {
        free(json);
        return -1;
    }
    int descriptor = mkstemp(path);
    if (descriptor < 0) {
        free(json);
        return -1;
    }
    unlink(path);
    int result = write_all(descriptor, json, json_length);
    free(json);
    if (result == 0 && lseek(descriptor, 0, SEEK_SET) < 0) result = -1;
    char response[256] = {0};
    if (result == 0) {
        result = archphene_android_publish_accessibility_tree(
                descriptor, response, sizeof(response));
        if (result == 0 && strcmp(response, "OK") != 0) result = -1;
    }
    close(descriptor);
    return result;
}

const ArchpheneAtspiNode *archphene_atspi_tree_find(
        const ArchpheneAtspiTree *tree, int id) {
    if (tree == NULL || id < 1) return NULL;
    for (size_t index = 0; index < tree->count; index++) {
        if (tree->nodes[index].id == id) return &tree->nodes[index].node;
    }
    return NULL;
}
