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
#define LUMA_BYTES (WIDTH * HEIGHT)
#define CHROMA_BYTES (LUMA_BYTES / 4)
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
    unsigned long different_luma_bytes = 0;
    uint8_t plane_min[3] = {UINT8_MAX, UINT8_MAX, UINT8_MAX};
    uint8_t plane_max[3] = {0, 0, 0};
    uint64_t plane_sum[3] = {0, 0, 0};
    const size_t plane_offsets[3] = {0, LUMA_BYTES, LUMA_BYTES + CHROMA_BYTES};
    const size_t plane_lengths[3] = {LUMA_BYTES, CHROMA_BYTES, CHROMA_BYTES};
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
        for (int byte = 1; byte < LUMA_BYTES; byte++) {
            if (frame[byte] != frame[0]) different_luma_bytes++;
        }
        for (int plane = 0; plane < 3; plane++) {
            size_t end = plane_offsets[plane] + plane_lengths[plane];
            for (size_t byte = plane_offsets[plane]; byte < end; byte++) {
                uint8_t value = frame[byte];
                if (value < plane_min[plane]) plane_min[plane] = value;
                if (value > plane_max[plane]) plane_max[plane] = value;
                plane_sum[plane] += value;
            }
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
    if (plane_min[0] == plane_max[0]
            && plane_min[1] == plane_max[1]
            && plane_min[2] == plane_max[2]
            && plane_min[0] == plane_min[1]
            && plane_min[1] == plane_min[2]) {
        fprintf(stderr, "camera frame is uniform across all I420 planes: %u\n",
                plane_min[0]);
        return 65;
    }
    printf("PASS camera I420 stream frames=%d bytes=%d sequence=%u "
            "luma-variation=%lu "
            "Y=%u..%u/%llu U=%u..%u/%llu V=%u..%u/%llu\n",
            FRAME_COUNT, FRAME_BYTES, previous_sequence, different_luma_bytes,
            plane_min[0], plane_max[0],
            (unsigned long long)(plane_sum[0] / (FRAME_COUNT * LUMA_BYTES)),
            plane_min[1], plane_max[1],
            (unsigned long long)(plane_sum[1] / (FRAME_COUNT * CHROMA_BYTES)),
            plane_min[2], plane_max[2],
            (unsigned long long)(plane_sum[2] / (FRAME_COUNT * CHROMA_BYTES)));
    return 0;
}
