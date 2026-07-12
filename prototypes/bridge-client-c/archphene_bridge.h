#ifndef ARCHPHENE_BRIDGE_H
#define ARCHPHENE_BRIDGE_H

#include <stddef.h>

typedef struct ArchpheneBridgeResult {
    int ok;
    int granted;
    char id[64];
    char type[64];
    char reason[128];
    char uri[512];
    char text[4096];
    char raw[8192];
} ArchpheneBridgeResult;

int archphene_request_permission(
        const char *id,
        const char *permission,
        const char *reason,
        ArchpheneBridgeResult *result);

int archphene_open_document(
        const char *id,
        const char *mime,
        const char *reason,
        ArchpheneBridgeResult *result);


int archphene_create_document(
        const char *id,
        const char *mime,
        const char *display_name,
        const char *text,
        const char *reason,
        ArchpheneBridgeResult *result);
int archphene_open_tree(
        const char *id,
        const char *reason,
        ArchpheneBridgeResult *result);

int archphene_tree_write_file(
        const char *id,
        const char *relative_path,
        const char *mime,
        const char *text,
        const char *reason,
        ArchpheneBridgeResult *result);

int archphene_tree_read_file(
        const char *id,
        const char *relative_path,
        const char *reason,
        ArchpheneBridgeResult *result);

int archphene_result_matches_id(const ArchpheneBridgeResult *result, const char *id);

#endif
