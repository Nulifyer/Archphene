#include "archphene_atspi_publish.h"

#include "archphene_android.h"

#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#define TREE_WIRE_MAX (1024 * 1024)
#define TREE_WIRE_VERSION 1
#define TREE_WIRE_HEADER_BYTES 24
#define TREE_WIRE_NODE_FIXED_BYTES 36
#define TREE_WIRE_COUNT_OFFSET 20
#define NODE_ID_MAX 1000000
#define VIEWPORT_MAX 16384
#define TRAVERSAL_MAX (ARCHPHENE_ATSPI_NODE_MAX + 16)
#define TREE_BUILD_BUDGET_MILLIS 5000

typedef struct {
    ArchpheneAtspiReference reference;
    int parent;
} PendingNode;

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

int archphene_atspi_tree_add_node(
        ArchpheneAtspiTree *tree, const ArchpheneAtspiNode *node,
        const ArchpheneAtspiReference *parent_reference) {
    if (tree == NULL || node == NULL) return -1;
    if (tree_find_reference(tree, &node->reference) != NULL) return 0;
    if (tree->count >= ARCHPHENE_ATSPI_NODE_MAX) return -1;
    const ArchpheneAtspiPublishedNode *parent = parent_reference == NULL
            ? NULL : tree_find_reference(tree, parent_reference);
    if (parent_reference != NULL && parent == NULL) return -2;
    int id = stable_id(tree, &node->reference);
    if (id < 1) return -1;
    ArchpheneAtspiPublishedNode *published = &tree->nodes[tree->count++];
    published->id = id;
    published->parent = parent == NULL ? 0 : parent->id;
    published->node = *node;
    published->node.x = clamp_position(node->x);
    published->node.y = clamp_position(node->y);
    published->node.width = clamp_size(node->width);
    published->node.height = clamp_size(node->height);
    copy_title(published->window_title, sizeof(published->window_title),
            parent == NULL ? node->text : parent->window_title);
    if (parent == NULL) {
        if (published->node.width > tree->viewport_width)
            tree->viewport_width = published->node.width;
        if (published->node.height > tree->viewport_height)
            tree->viewport_height = published->node.height;
    }
    return 1;
}

int archphene_atspi_tree_add_root(
        ArchpheneAtspiTree *tree, const ArchpheneAtspiNode *node) {
    return archphene_atspi_tree_add_node(tree, node, NULL);
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
            truncated = 1;
            continue;
        }
        if (read_result > 0) truncated = 1;
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

static int write_all(int descriptor, const void *data, size_t length) {
    const unsigned char *bytes = data;
    size_t offset = 0;
    while (offset < length) {
        ssize_t written = write(descriptor, bytes + offset, length - offset);
        if (written < 0 && errno == EINTR) continue;
        if (written <= 0) return -1;
        offset += (size_t)written;
    }
    return 0;
}

static int write_u16(int descriptor, uint16_t value) {
    unsigned char bytes[2] = {
        (unsigned char)(value >> 8),
        (unsigned char)value,
    };
    return write_all(descriptor, bytes, sizeof(bytes));
}

static int write_u32(int descriptor, uint32_t value) {
    unsigned char bytes[4] = {
        (unsigned char)(value >> 24),
        (unsigned char)(value >> 16),
        (unsigned char)(value >> 8),
        (unsigned char)value,
    };
    return write_all(descriptor, bytes, sizeof(bytes));
}

static int emitted_parent(const ArchpheneAtspiTree *tree,
        size_t limit, int parent) {
    if (parent == 0) return 1;
    for (size_t index = 0; index < limit; index++) {
        if (tree->nodes[index].id == parent) return 1;
    }
    return 0;
}

static uint32_t node_flags(const ArchpheneAtspiNode *node) {
    uint32_t flags = 0;
    if (node->enabled) flags |= 1u << 0;
    if (node->focusable) flags |= 1u << 1;
    if (node->clickable) flags |= 1u << 2;
    if (node->editable) flags |= 1u << 3;
    if (node->checkable) flags |= 1u << 4;
    if (node->checked) flags |= 1u << 5;
    if (node->selected) flags |= 1u << 6;
    if (node->password) flags |= 1u << 7;
    if (node->scroll_forward_action >= 0) flags |= 1u << 8;
    if (node->scroll_backward_action >= 0) flags |= 1u << 9;
    return flags;
}

int archphene_atspi_tree_equal(
        const ArchpheneAtspiTree *left,
        const ArchpheneAtspiTree *right) {
    if (left == right) return 1;
    if (left == NULL || right == NULL
            || left->viewport_width != right->viewport_width
            || left->viewport_height != right->viewport_height
            || left->count != right->count) return 0;
    for (size_t index = 0; index < left->count; index++) {
        const ArchpheneAtspiPublishedNode *a = &left->nodes[index];
        const ArchpheneAtspiPublishedNode *b = &right->nodes[index];
        if (a->id != b->id || a->parent != b->parent
                || a->node.x != b->node.x || a->node.y != b->node.y
                || a->node.width != b->node.width
                || a->node.height != b->node.height
                || node_flags(&a->node) != node_flags(&b->node)
                || strcmp(a->node.role, b->node.role) != 0
                || strcmp(a->node.text, b->node.text) != 0
                || strcmp(a->node.description, b->node.description) != 0
                || strcmp(a->window_title, b->window_title) != 0) {
            return 0;
        }
    }
    return 1;
}

static int write_tree_header(int descriptor, const ArchpheneAtspiTree *tree) {
    static const unsigned char magic[8] =
            {'A', 'R', 'C', 'H', 'A', 'T', 'S', 'P'};
    return write_all(descriptor, magic, sizeof(magic))
            || write_u32(descriptor, TREE_WIRE_VERSION)
            || write_u32(descriptor, (uint32_t)clamp_size(tree->viewport_width))
            || write_u32(descriptor, (uint32_t)clamp_size(tree->viewport_height))
            || write_u32(descriptor, 0);
}

static int write_tree_node(int descriptor,
        const ArchpheneAtspiPublishedNode *entry,
        const uint16_t lengths[4]) {
    const ArchpheneAtspiNode *node = &entry->node;
    if (write_u32(descriptor, (uint32_t)entry->id)
            || write_u32(descriptor, (uint32_t)entry->parent)
            || write_u32(descriptor, (uint32_t)node->x)
            || write_u32(descriptor, (uint32_t)node->y)
            || write_u32(descriptor, (uint32_t)node->width)
            || write_u32(descriptor, (uint32_t)node->height)
            || write_u32(descriptor, node_flags(node))) return -1;
    for (size_t index = 0; index < 4; index++) {
        if (write_u16(descriptor, lengths[index])) return -1;
    }
    return write_all(descriptor, node->role, lengths[0])
            || write_all(descriptor, node->text, lengths[1])
            || write_all(descriptor, node->description, lengths[2])
            || write_all(descriptor, entry->window_title, lengths[3]);
}

static int write_tree(int descriptor, const ArchpheneAtspiTree *tree,
        size_t *emitted_count, size_t *wire_bytes) {
    if (write_tree_header(descriptor, tree) != 0) return -1;
    size_t total = TREE_WIRE_HEADER_BYTES;
    size_t emitted = 0;
    for (size_t index = 0; index < tree->count; index++) {
        const ArchpheneAtspiPublishedNode *entry = &tree->nodes[index];
        const ArchpheneAtspiNode *node = &entry->node;
        size_t raw_lengths[4] = {
            strnlen(node->role, sizeof(node->role)),
            strnlen(node->text, sizeof(node->text)),
            strnlen(node->description, sizeof(node->description)),
            strnlen(entry->window_title, sizeof(entry->window_title)),
        };
        uint16_t lengths[4];
        size_t record = TREE_WIRE_NODE_FIXED_BYTES;
        for (size_t part = 0; part < 4; part++) {
            if (raw_lengths[part] > UINT16_MAX) return -1;
            lengths[part] = (uint16_t)raw_lengths[part];
            if (raw_lengths[part] > TREE_WIRE_MAX - record) return -1;
            record += raw_lengths[part];
        }
        if (record > TREE_WIRE_MAX - total) break;
        if (!emitted_parent(tree, index, entry->parent)) continue;
        if (write_tree_node(descriptor, entry, lengths) != 0) return -1;
        total += record;
        emitted++;
    }
    if (tree->count > 0 && emitted == 0) return -1;
    if (lseek(descriptor, TREE_WIRE_COUNT_OFFSET, SEEK_SET) < 0
            || write_u32(descriptor, (uint32_t)emitted) != 0
            || lseek(descriptor, 0, SEEK_SET) < 0) return -1;
    *emitted_count = emitted;
    *wire_bytes = total;
    return 0;
}

int archphene_atspi_tree_publish(const ArchpheneAtspiTree *tree) {
    if (tree == NULL) return -1;
    const char *runtime = getenv("ARCHPHENE_RUNTIME_DIR");
    if (runtime == NULL || runtime[0] != '/') return -1;
    char path[1024];
    int length = snprintf(path, sizeof(path),
            "%s/.archphene-atspi-XXXXXX", runtime);
    if (length <= 0 || (size_t)length >= sizeof(path)) return -1;
    int descriptor = mkstemp(path);
    if (descriptor < 0) return -1;
    unlink(path);
    size_t emitted_count = 0;
    size_t wire_bytes = 0;
    int result = write_tree(
            descriptor, tree, &emitted_count, &wire_bytes);
    char response[256] = {0};
    if (result == 0) {
        result = archphene_android_publish_accessibility_tree(
                descriptor, response, sizeof(response));
        if (result == 0 && strcmp(response, "OK") != 0) result = -1;
    }
    static size_t last_logged_count = SIZE_MAX;
    if (result != 0 || emitted_count != last_logged_count) {
        fprintf(stderr, "AT-SPI publish nodes=%zu bytes=%zu result=%d response=%s\n",
                emitted_count, wire_bytes, result,
                response[0] == '\0' ? "none" : response);
        last_logged_count = emitted_count;
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
