#define _GNU_SOURCE

#include <errno.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <pipewire/pipewire.h>

#define MAX_NODES 32
#define MAX_PORTS 64
#define MAX_LINKS 16
#define TARGET_BYTES 128
#define CAMERA_NAME "archphene.android.camera"

struct policy;

struct node_entry {
    bool active;
    bool targeted_input;
    uint32_t id;
    uint64_t serial;
    char target[TARGET_BYTES];
    struct policy *policy;
    struct pw_node *proxy;
    struct spa_hook object_listener;
    struct spa_hook proxy_listener;
};

struct port_entry {
    bool active;
    bool input;
    uint32_t id;
    uint32_t node_id;
};

struct link_entry {
    bool active;
    uint32_t input_node;
    struct pw_proxy *proxy;
    struct spa_hook listener;
};

struct policy {
    struct pw_main_loop *loop;
    struct pw_context *context;
    struct pw_core *core;
    struct pw_registry *registry;
    struct spa_hook registry_listener;
    uint32_t camera_node;
    uint64_t camera_serial;
    struct node_entry nodes[MAX_NODES];
    struct port_entry ports[MAX_PORTS];
    struct link_entry links[MAX_LINKS];
};

static void maybe_create_links(struct policy *policy);

static bool parse_u32(const char *text, uint32_t *value) {
    if (text == NULL || text[0] == '\0') return false;
    char *end = NULL;
    errno = 0;
    unsigned long long parsed = strtoull(text, &end, 10);
    if (errno != 0 || end == text || *end != '\0' || parsed > UINT32_MAX) {
        return false;
    }
    *value = (uint32_t)parsed;
    return true;
}

static uint64_t parse_u64(const char *text) {
    if (text == NULL || text[0] == '\0') return 0;
    char *end = NULL;
    errno = 0;
    unsigned long long parsed = strtoull(text, &end, 10);
    return errno == 0 && end != text && *end == '\0' ? parsed : 0;
}

static struct node_entry *find_node(struct policy *policy, uint32_t id) {
    for (size_t index = 0; index < MAX_NODES; index++) {
        if (policy->nodes[index].active && policy->nodes[index].id == id) {
            return &policy->nodes[index];
        }
    }
    return NULL;
}

static struct node_entry *put_node(struct policy *policy, uint32_t id) {
    struct node_entry *entry = find_node(policy, id);
    if (entry != NULL) return entry;
    for (size_t index = 0; index < MAX_NODES; index++) {
        if (!policy->nodes[index].active) {
            entry = &policy->nodes[index];
            memset(entry, 0, sizeof(*entry));
            entry->active = true;
            entry->id = id;
            entry->policy = policy;
            return entry;
        }
    }
    return NULL;
}

static struct port_entry *find_port_for_node(
        struct policy *policy, uint32_t node_id, bool input) {
    for (size_t index = 0; index < MAX_PORTS; index++) {
        struct port_entry *entry = &policy->ports[index];
        if (entry->active && entry->node_id == node_id && entry->input == input) {
            return entry;
        }
    }
    return NULL;
}

static struct port_entry *put_port(struct policy *policy, uint32_t id) {
    for (size_t index = 0; index < MAX_PORTS; index++) {
        if (policy->ports[index].active && policy->ports[index].id == id) {
            return &policy->ports[index];
        }
    }
    for (size_t index = 0; index < MAX_PORTS; index++) {
        if (!policy->ports[index].active) {
            struct port_entry *entry = &policy->ports[index];
            memset(entry, 0, sizeof(*entry));
            entry->active = true;
            entry->id = id;
            return entry;
        }
    }
    return NULL;
}

static bool target_matches_camera(
        const struct policy *policy, const char *target) {
    if (target == NULL || target[0] == '\0') return false;
    if (strcmp(target, CAMERA_NAME) == 0) return true;
    uint64_t number = parse_u64(target);
    return number != 0
            && (number == policy->camera_node || number == policy->camera_serial);
}

static struct link_entry *find_link(
        struct policy *policy, uint32_t input_node) {
    for (size_t index = 0; index < MAX_LINKS; index++) {
        if (policy->links[index].active
                && policy->links[index].input_node == input_node) {
            return &policy->links[index];
        }
    }
    return NULL;
}

static void link_destroyed(void *userdata) {
    struct link_entry *entry = userdata;
    spa_hook_remove(&entry->listener);
    entry->proxy = NULL;
    entry->active = false;
}

static void link_removed(void *userdata) {
    struct link_entry *entry = userdata;
    if (entry->proxy != NULL) pw_proxy_destroy(entry->proxy);
}

static const struct pw_proxy_events link_events = {
    PW_VERSION_PROXY_EVENTS,
    .removed = link_removed,
    .destroy = link_destroyed,
};

static void destroy_link(struct link_entry *entry) {
    if (!entry->active) return;
    if (entry->proxy != NULL) {
        pw_proxy_destroy(entry->proxy);
    } else {
        entry->active = false;
    }
}

static void maybe_create_links(struct policy *policy) {
    if (policy->camera_node == PW_ID_ANY) return;
    struct port_entry *output =
            find_port_for_node(policy, policy->camera_node, false);
    if (output == NULL) return;

    for (size_t index = 0; index < MAX_NODES; index++) {
        struct node_entry *node = &policy->nodes[index];
        if (!node->active || !node->targeted_input
                || !target_matches_camera(policy, node->target)
                || find_link(policy, node->id) != NULL) {
            continue;
        }
        struct port_entry *input = find_port_for_node(policy, node->id, true);
        if (input == NULL) continue;

        struct link_entry *link = NULL;
        for (size_t link_index = 0; link_index < MAX_LINKS; link_index++) {
            if (!policy->links[link_index].active) {
                link = &policy->links[link_index];
                break;
            }
        }
        if (link == NULL) {
            fprintf(stderr, "Camera policy link limit reached\n");
            return;
        }

        char output_id[16];
        char input_id[16];
        snprintf(output_id, sizeof(output_id), "%u", output->id);
        snprintf(input_id, sizeof(input_id), "%u", input->id);
        struct pw_properties *properties = pw_properties_new(
                PW_KEY_LINK_OUTPUT_PORT, output_id,
                PW_KEY_LINK_INPUT_PORT, input_id,
                NULL);
        if (properties == NULL) continue;
        struct pw_proxy *proxy = pw_core_create_object(
                policy->core,
                "link-factory",
                PW_TYPE_INTERFACE_Link,
                PW_VERSION_LINK,
                &properties->dict,
                0);
        pw_properties_free(properties);
        if (proxy == NULL) {
            fprintf(stderr, "Camera policy could not link ports %u -> %u: %m\n",
                    output->id, input->id);
            continue;
        }
        memset(link, 0, sizeof(*link));
        link->active = true;
        link->input_node = node->id;
        link->proxy = proxy;
        pw_proxy_add_listener(proxy, &link->listener, &link_events, link);
        fprintf(stderr, "Archphene camera link=%u->%u\n",
                output->id, input->id);
    }
}

static void update_node_properties(
        struct node_entry *entry, const struct spa_dict *props) {
    if (props == NULL) return;
    const char *media_class = spa_dict_lookup(props, PW_KEY_MEDIA_CLASS);
    const char *target = spa_dict_lookup(props, "node.target");
    if (target == NULL) target = spa_dict_lookup(props, PW_KEY_TARGET_OBJECT);
    if (media_class == NULL || strstr(media_class, "Input") == NULL
            || target == NULL) {
        return;
    }
    entry->targeted_input = true;
    entry->serial =
            parse_u64(spa_dict_lookup(props, PW_KEY_OBJECT_SERIAL));
    snprintf(entry->target, sizeof(entry->target), "%s", target);
    maybe_create_links(entry->policy);
}

static void node_info(
        void *userdata, const struct pw_node_info *info) {
    struct node_entry *entry = userdata;
    update_node_properties(entry, info == NULL ? NULL : info->props);
}

static const struct pw_node_events node_events = {
    PW_VERSION_NODE_EVENTS,
    .info = node_info,
};

static void node_proxy_destroyed(void *userdata) {
    struct node_entry *entry = userdata;
    spa_hook_remove(&entry->object_listener);
    spa_hook_remove(&entry->proxy_listener);
    entry->proxy = NULL;
}

static const struct pw_proxy_events node_proxy_events = {
    PW_VERSION_PROXY_EVENTS,
    .destroy = node_proxy_destroyed,
};

static void registry_global(void *userdata, uint32_t id,
        uint32_t permissions, const char *type, uint32_t version,
        const struct spa_dict *props) {
    (void)permissions;
    struct policy *policy = userdata;
    if (props == NULL) return;

    if (strcmp(type, PW_TYPE_INTERFACE_Node) == 0) {
        const char *name = spa_dict_lookup(props, PW_KEY_NODE_NAME);
        if (name != NULL && strcmp(name, CAMERA_NAME) == 0) {
            policy->camera_node = id;
            policy->camera_serial =
                    parse_u64(spa_dict_lookup(props, PW_KEY_OBJECT_SERIAL));
            fprintf(stderr, "Archphene camera policy source=%u serial=%llu\n",
                    id, (unsigned long long)policy->camera_serial);
        } else {
            struct node_entry *entry = put_node(policy, id);
            if (entry != NULL) {
                update_node_properties(entry, props);
                uint32_t bind_version =
                        version < PW_VERSION_NODE ? version : PW_VERSION_NODE;
                entry->proxy = pw_registry_bind(policy->registry, id,
                        type, bind_version, 0);
                if (entry->proxy != NULL) {
                    pw_node_add_listener(entry->proxy, &entry->object_listener,
                            &node_events, entry);
                    pw_proxy_add_listener((struct pw_proxy *)entry->proxy,
                            &entry->proxy_listener, &node_proxy_events, entry);
                }
            }
        }
    } else if (strcmp(type, PW_TYPE_INTERFACE_Port) == 0) {
        uint32_t node_id = 0;
        const char *node = spa_dict_lookup(props, PW_KEY_NODE_ID);
        const char *direction =
                spa_dict_lookup(props, PW_KEY_PORT_DIRECTION);
        if (parse_u32(node, &node_id) && direction != NULL) {
            struct port_entry *entry = put_port(policy, id);
            if (entry != NULL) {
                entry->node_id = node_id;
                entry->input = strcmp(direction, "in") == 0;
            }
        }
    }
    maybe_create_links(policy);
}

static void registry_global_remove(void *userdata, uint32_t id) {
    struct policy *policy = userdata;
    if (policy->camera_node == id) {
        policy->camera_node = PW_ID_ANY;
        policy->camera_serial = 0;
        for (size_t index = 0; index < MAX_LINKS; index++) {
            destroy_link(&policy->links[index]);
        }
    }
    for (size_t index = 0; index < MAX_NODES; index++) {
        struct node_entry *node = &policy->nodes[index];
        if (node->active && node->id == id) {
            struct link_entry *link = find_link(policy, id);
            if (link != NULL) destroy_link(link);
            if (node->proxy != NULL) {
                pw_proxy_destroy((struct pw_proxy *)node->proxy);
            }
            node->active = false;
        }
    }
    for (size_t index = 0; index < MAX_PORTS; index++) {
        if (policy->ports[index].active && policy->ports[index].id == id) {
            policy->ports[index].active = false;
        }
    }
}

static const struct pw_registry_events registry_events = {
    PW_VERSION_REGISTRY_EVENTS,
    .global = registry_global,
    .global_remove = registry_global_remove,
};

static void stop_policy(void *userdata, int signal_number) {
    (void)signal_number;
    struct policy *policy = userdata;
    pw_main_loop_quit(policy->loop);
}

int main(int argc, char **argv) {
    struct policy policy = {
        .camera_node = PW_ID_ANY,
    };
    pw_init(&argc, &argv);
    policy.loop = pw_main_loop_new(NULL);
    if (policy.loop == NULL) return 70;
    pw_loop_add_signal(
            pw_main_loop_get_loop(policy.loop), SIGINT, stop_policy, &policy);
    pw_loop_add_signal(
            pw_main_loop_get_loop(policy.loop), SIGTERM, stop_policy, &policy);
    policy.context =
            pw_context_new(pw_main_loop_get_loop(policy.loop), NULL, 0);
    if (policy.context == NULL) goto fail;
    policy.core = pw_context_connect(policy.context, NULL, 0);
    if (policy.core == NULL) goto fail;
    policy.registry =
            pw_core_get_registry(policy.core, PW_VERSION_REGISTRY, 0);
    if (policy.registry == NULL) goto fail;
    pw_registry_add_listener(policy.registry, &policy.registry_listener,
            &registry_events, &policy);
    pw_main_loop_run(policy.loop);

    for (size_t index = 0; index < MAX_LINKS; index++) {
        destroy_link(&policy.links[index]);
    }
    for (size_t index = 0; index < MAX_NODES; index++) {
        if (policy.nodes[index].proxy != NULL) {
            pw_proxy_destroy((struct pw_proxy *)policy.nodes[index].proxy);
        }
    }
    pw_proxy_destroy((struct pw_proxy *)policy.registry);
    pw_core_disconnect(policy.core);
    pw_context_destroy(policy.context);
    pw_main_loop_destroy(policy.loop);
    pw_deinit();
    return 0;

fail:
    if (policy.core != NULL) pw_core_disconnect(policy.core);
    if (policy.context != NULL) pw_context_destroy(policy.context);
    pw_main_loop_destroy(policy.loop);
    pw_deinit();
    return 70;
}
