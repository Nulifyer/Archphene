#ifndef ARCHPHENE_ATSPI_TRANSLATOR_H
#define ARCHPHENE_ATSPI_TRANSLATOR_H

#include <dbus/dbus.h>

int archphene_atspi_translator_start(void);
void archphene_atspi_translator_stop(void);
int archphene_atspi_translator_register(
        const char *bus, const char *path, int *application_id);
void archphene_atspi_translator_unregister(const char *bus, const char *path);
void archphene_atspi_translator_disconnect(const char *bus);
dbus_bool_t archphene_atspi_translator_has_bus(const char *bus);
void archphene_atspi_translator_mark_dirty(void);
void archphene_atspi_translator_event(DBusMessage *message);

#endif