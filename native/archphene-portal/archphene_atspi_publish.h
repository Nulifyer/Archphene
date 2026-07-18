#ifndef ARCHPHENE_ATSPI_PUBLISH_H
#define ARCHPHENE_ATSPI_PUBLISH_H

#include "archphene_atspi_client.h"

#define ARCHPHENE_ATSPI_NODE_MAX 1024

#define ARCHPHENE_ATSPI_TREE_COMPLETE 0
#define ARCHPHENE_ATSPI_TREE_RETRY 1
#define ARCHPHENE_ATSPI_TREE_TRUNCATED 2

typedef struct {
    int id;
    int parent;
    char window_title[ARCHPHENE_ATSPI_TEXT_MAX + 1];
    ArchpheneAtspiNode node;
} ArchpheneAtspiPublishedNode;

typedef struct {
    int viewport_width;
    int viewport_height;
    size_t count;
    ArchpheneAtspiPublishedNode nodes[ARCHPHENE_ATSPI_NODE_MAX];
} ArchpheneAtspiTree;

int archphene_atspi_tree_build(
        DBusConnection *connection,
        const ArchpheneAtspiReference *applications,
        size_t application_count,
        ArchpheneAtspiTree *tree);
int archphene_atspi_tree_publish(const ArchpheneAtspiTree *tree);
int archphene_atspi_tree_add_root(
        ArchpheneAtspiTree *tree, const ArchpheneAtspiNode *node);
size_t archphene_atspi_tree_retain_descendants(
        const ArchpheneAtspiTree *previous,
        ArchpheneAtspiTree *current);
const ArchpheneAtspiNode *archphene_atspi_tree_find(
        const ArchpheneAtspiTree *tree, int id);

#endif