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
int archphene_android_print_pdf(
        int pdf_fd, const char *title, char *response, size_t response_size);
int archphene_android_request_audio_input(char *response, size_t response_size);
int archphene_android_check_audio_input(char *response, size_t response_size);
int archphene_android_request_camera(char *response, size_t response_size);
int archphene_android_check_camera(char *response, size_t response_size);
int archphene_android_capture_camera_jpeg(
        int output_fd, int width, int height, int front_facing,
        char *response, size_t response_size);
int archphene_android_stream_camera_i420(
        int output_fd, int width, int height, int front_facing,
        char *response, size_t response_size);

int archphene_android_publish_accessibility_tree(
        int tree_fd, char *response, size_t response_size);
int archphene_android_accessibility_event(
        int node_id, const char *type, char *response, size_t response_size);
int archphene_android_take_accessibility_action(
        int timeout_millis, char *response, size_t response_size);
int archphene_android_accessibility_menu_fallback(
        char *response, size_t response_size);

int archphene_android_store_secret(
        int secret_fd, const char *id, const char *label, const char *attributes_json,
        char *response, size_t response_size);
int archphene_android_store_secret_typed(
        int secret_fd, const char *id, const char *label, const char *attributes_json,
        const char *content_type, char *response, size_t response_size);
int archphene_android_read_secret(
        int output_fd, const char *id, char *response, size_t response_size);
int archphene_android_delete_secret(
        const char *id, char *response, size_t response_size);
int archphene_android_list_secrets(
        int output_fd, char *response, size_t response_size);
int archphene_android_catalog_secrets(
        int output_fd, char *response, size_t response_size);
#endif
