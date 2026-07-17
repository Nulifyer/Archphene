#ifndef ARCHPHENE_ATSPI_BRIDGE_H
#define ARCHPHENE_ATSPI_BRIDGE_H
#include <dbus/dbus.h>
int archphene_atspi_init(DBusConnection *connection, DBusError *error);
void archphene_atspi_shutdown(void);
dbus_bool_t archphene_atspi_handles(DBusConnection *connection, DBusMessage *message);
void archphene_atspi_handle_signal(DBusMessage *message);
const char *archphene_atspi_introspection(const char *path);
#endif