#ifndef ARCHPHENE_ATSPI_CLIENT_H
#define ARCHPHENE_ATSPI_CLIENT_H

#include <dbus/dbus.h>
#include <stddef.h>
#include <stdint.h>

#define ARCHPHENE_ATSPI_BUS_MAX 256
#define ARCHPHENE_ATSPI_PATH_MAX 512
#define ARCHPHENE_ATSPI_TEXT_MAX 1024
#define ARCHPHENE_ATSPI_CHILD_MAX 1024

typedef struct {
    char bus[ARCHPHENE_ATSPI_BUS_MAX];
    char path[ARCHPHENE_ATSPI_PATH_MAX];
} ArchpheneAtspiReference;

typedef struct {
    ArchpheneAtspiReference reference;
    char role[32];
    char text[ARCHPHENE_ATSPI_TEXT_MAX + 1];
    char description[ARCHPHENE_ATSPI_TEXT_MAX + 1];
    int x;
    int y;
    int width;
    int height;
    dbus_bool_t enabled;
    dbus_bool_t focusable;
    dbus_bool_t clickable;
    dbus_bool_t editable;
    dbus_bool_t checkable;
    dbus_bool_t checked;
    dbus_bool_t password;
    dbus_bool_t application;
    int click_action;
    int scroll_forward_action;
    int scroll_backward_action;
} ArchpheneAtspiNode;

int archphene_atspi_client_read_node(
        DBusConnection *connection,
        const ArchpheneAtspiReference *reference,
        ArchpheneAtspiNode *node,
        ArchpheneAtspiReference *children,
        size_t children_capacity,
        size_t *children_count);
int archphene_atspi_client_click(
        DBusConnection *connection, const ArchpheneAtspiNode *node);
int archphene_atspi_client_focus(
        DBusConnection *connection, const ArchpheneAtspiNode *node);
int archphene_atspi_client_set_text(
        DBusConnection *connection, const ArchpheneAtspiNode *node,
        const char *text);
int archphene_atspi_client_scroll(
        DBusConnection *connection, const ArchpheneAtspiNode *node,
        dbus_bool_t forward);

#endif
