#ifndef ARCHPHENE_ANDROID_H
#define ARCHPHENE_ANDROID_H

#include <stddef.h>

int archphene_android_open_uri(
        const char *uri, char *response, size_t response_size);
int archphene_android_notify(
        const char *id, const char *title, const char *body,
        char *response, size_t response_size);
int archphene_android_withdraw_notification(
        const char *id, char *response, size_t response_size);

#endif
