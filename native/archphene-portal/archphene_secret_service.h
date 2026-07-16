#ifndef ARCHPHENE_SECRET_SERVICE_H
#define ARCHPHENE_SECRET_SERVICE_H

#include <dbus/dbus.h>

int archphene_secret_service_own_name(DBusConnection *connection, DBusError *error);
dbus_bool_t archphene_secret_service_handles(DBusConnection *connection,
        DBusMessage *message);
const char *archphene_secret_service_introspection(const char *path);
void archphene_secret_service_handle_signal(DBusMessage *message);

#endif
