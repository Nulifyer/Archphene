#define _GNU_SOURCE

#include "archphene_android.h"

#include <errno.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#define HEADER_BYTES 36
#define WIDTH 640
#define HEIGHT 480
#define FRAME_BYTES (WIDTH * HEIGHT * 3 / 2)
#define FRAME_COUNT 3

struct request {
    int fd;
    int result;
    char response[256];
};

static uint32_t read_u32_le(const uint8_t *value) {
    return (uint32_t)value[0]
            | (uint32_t)value[1] << 8
            | (uint32_t)value[2] << 16
            | (uint32_t)value[3] << 24;
}

static int read_full(int fd, void *buffer, size_t size) {
    size_t offset = 0;
    while (offset < size) {
        ssize_t count = read(fd, (uint8_t *)buffer + offset, size - offset);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return -1;
        offset += (size_t)count;
    }
    return 0;
}

static void *request_stream(void *userdata) {
    struct request *request = userdata;
    request->result = archphene_android_stream_camera_i420(
            request->fd, WIDTH, HEIGHT, 0,
            request->response, sizeof(request->response));
    close(request->fd);
    return NULL;
}

int main(int argc, char **argv) {
    if (argc != 3 || strcmp(argv[1], "--socket") != 0) {
        fprintf(stderr, "usage: %s --socket @BROKER\n", argv[0]);
        return 64;
    }
    if (setenv("ARCHPHENE_ANDROID_BROKER", argv[2], 1) != 0) {
        perror("setenv");
        return 70;
    }
    int sockets[2];
    if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, sockets) != 0) {
        perror("socketpair");
        return 70;
    }
    struct request request = {.fd = sockets[1], .result = -1};
    pthread_t thread;
    if (pthread_create(&thread, NULL, request_stream, &request) != 0) {
        perror("pthread_create");
        close(sockets[0]);
        close(sockets[1]);
        return 70;
    }
    uint8_t *frame = malloc(FRAME_BYTES);
    if (frame == NULL) {
        close(sockets[0]);
        pthread_join(thread, NULL);
        return 70;
    }
    uint32_t previous_sequence = 0;
    unsigned long different_bytes = 0;
    for (int index = 0; index < FRAME_COUNT; index++) {
        uint8_t header[HEADER_BYTES];
        if (read_full(sockets[0], header, sizeof(header)) != 0
                || memcmp(header, "APCF", 4) != 0
                || read_u32_le(header + 4) != 1
                || read_u32_le(header + 8) != WIDTH
                || read_u32_le(header + 12) != HEIGHT
                || read_u32_le(header + 16) != 1
                || read_u32_le(header + 24) != FRAME_BYTES
                || read_full(sockets[0], frame, FRAME_BYTES) != 0) {
            fprintf(stderr, "invalid APCF frame %d\n", index);
            free(frame);
            close(sockets[0]);
            pthread_join(thread, NULL);
            return 65;
        }
        uint32_t sequence = read_u32_le(header + 20);
        if (index > 0 && sequence <= previous_sequence) {
            fprintf(stderr, "non-increasing frame sequence\n");
            free(frame);
            close(sockets[0]);
            pthread_join(thread, NULL);
            return 65;
        }
        previous_sequence = sequence;
        for (int byte = 1; byte < FRAME_BYTES; byte++) {
            if (frame[byte] != frame[0]) different_bytes++;
        }
    }
    free(frame);
    shutdown(sockets[0], SHUT_RDWR);
    close(sockets[0]);
    pthread_join(thread, NULL);
    if (request.result != 0 || strcmp(request.response, "OK") != 0) {
        fprintf(stderr, "stream request failed: %s\n", request.response);
        return 69;
    }
    if (different_bytes == 0) {
        fprintf(stderr, "camera frames contain no variation\n");
        return 65;
    }
    printf("PASS camera I420 stream frames=%d bytes=%d sequence=%u variation=%lu\n",
            FRAME_COUNT, FRAME_BYTES, previous_sequence, different_bytes);
    return 0;
}
