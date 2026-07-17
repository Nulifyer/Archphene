#define _GNU_SOURCE

#include "archphene_android.h"

#include <errno.h>
#include <pthread.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#include <pipewire/pipewire.h>
#include <spa/param/video/format-utils.h>

#define WIDTH 640
#define HEIGHT 480
#define FRAME_BYTES (WIDTH * HEIGHT * 3 / 2)
#define HEADER_BYTES 36
#define MAX_BUFFERS 8

struct camera_state {
    pthread_mutex_t lock;
    uint8_t frame[FRAME_BYTES];
    int have_frame;
    atomic_bool stop;
};

struct app {
    struct pw_main_loop *loop;
    struct pw_context *context;
    struct pw_core *core;
    struct pw_stream *stream;
    struct spa_hook stream_listener;
    struct spa_source *timer;
    struct camera_state camera;
    pthread_t camera_thread;
    uint32_t sequence;
    int result;
};

struct request {
    int fd;
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

static void *request_camera(void *userdata) {
    struct request *request = userdata;
    char response[256] = {0};
    (void)archphene_android_stream_camera_i420(
            request->fd, WIDTH, HEIGHT, 0, response, sizeof(response));
    close(request->fd);
    return NULL;
}

static void sleep_millis(long millis) {
    struct timespec delay = {
        .tv_sec = millis / 1000,
        .tv_nsec = (millis % 1000) * 1000000
    };
    while (nanosleep(&delay, &delay) != 0 && errno == EINTR) {}
}

static void wait_for_camera_access(void) {
    char response[256] = {0};
    while (archphene_android_check_camera(response, sizeof(response)) != 0) {
        sleep_millis(100);
    }
}

static void *read_camera(void *userdata) {
    struct camera_state *state = userdata;
    while (!atomic_load(&state->stop)) {
        char permission[256] = {0};
        if (archphene_android_check_camera(permission, sizeof(permission)) != 0) {
            sleep_millis(250);
            continue;
        }
        int sockets[2];
        if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, sockets) != 0) {
            sleep_millis(250);
            continue;
        }
        struct request request = {.fd = sockets[1]};
        pthread_t requester;
        if (pthread_create(&requester, NULL, request_camera, &request) != 0) {
            close(sockets[0]);
            close(sockets[1]);
            sleep_millis(250);
            continue;
        }
        while (!atomic_load(&state->stop)) {
            uint8_t header[HEADER_BYTES];
            if (read_full(sockets[0], header, sizeof(header)) != 0) break;
            if (memcmp(header, "APCF", 4) != 0
                    || read_u32_le(header + 4) != 1
                    || read_u32_le(header + 8) != WIDTH
                    || read_u32_le(header + 12) != HEIGHT
                    || read_u32_le(header + 16) != 1
                    || read_u32_le(header + 24) != FRAME_BYTES) break;
            uint8_t frame[FRAME_BYTES];
            if (read_full(sockets[0], frame, sizeof(frame)) != 0) break;
            pthread_mutex_lock(&state->lock);
            memcpy(state->frame, frame, sizeof(frame));
            state->have_frame = 1;
            pthread_mutex_unlock(&state->lock);
        }
        shutdown(sockets[0], SHUT_RDWR);
        close(sockets[0]);
        pthread_join(requester, NULL);
        if (!atomic_load(&state->stop)) sleep_millis(250);
    }
    return NULL;
}

static void fill_test_frame(uint8_t *frame) {
    for (int row = 0; row < HEIGHT; row++) {
        for (int column = 0; column < WIDTH; column++) {
            frame[row * WIDTH + column] =
                    (uint8_t)((column * 255) / (WIDTH - 1));
        }
    }
    memset(frame + WIDTH * HEIGHT, 96, WIDTH * HEIGHT / 4);
    memset(frame + WIDTH * HEIGHT * 5 / 4, 160, WIDTH * HEIGHT / 4);
}

static void on_process(void *userdata) {
    struct app *app = userdata;
    struct pw_buffer *pw_buffer = pw_stream_dequeue_buffer(app->stream);
    if (pw_buffer == NULL) return;
    struct spa_buffer *buffer = pw_buffer->buffer;
    if (buffer->n_datas < 1 || buffer->datas[0].data == NULL
            || buffer->datas[0].maxsize < FRAME_BYTES) {
        pw_stream_queue_buffer(app->stream, pw_buffer);
        return;
    }
    uint8_t *destination = buffer->datas[0].data;
    pthread_mutex_lock(&app->camera.lock);
    memcpy(destination, app->camera.frame, FRAME_BYTES);
    pthread_mutex_unlock(&app->camera.lock);
    struct spa_meta_header *header = spa_buffer_find_meta_data(
            buffer, SPA_META_Header, sizeof(*header));
    if (header != NULL) {
        struct timespec now;
        header->pts = clock_gettime(CLOCK_MONOTONIC, &now) == 0
                ? (int64_t)now.tv_sec * 1000000000LL + now.tv_nsec
                : -1;
        header->flags = 0;
        header->seq = app->sequence++;
        header->dts_offset = 0;
    }
    buffer->datas[0].chunk->offset = 0;
    buffer->datas[0].chunk->size = FRAME_BYTES;
    buffer->datas[0].chunk->stride = WIDTH;
    pw_stream_queue_buffer(app->stream, pw_buffer);
}

static void on_timeout(void *userdata, uint64_t expirations) {
    (void)expirations;
    struct app *app = userdata;
    pw_stream_trigger_process(app->stream);
}

static void on_state_changed(void *userdata, enum pw_stream_state old,
        enum pw_stream_state state, const char *error) {
    (void)old;
    struct app *app = userdata;
    if (state == PW_STREAM_STATE_ERROR) {
        if (error != NULL) fprintf(stderr, "camera stream: %s\n", error);
        pw_main_loop_quit(app->loop);
    } else if (state == PW_STREAM_STATE_PAUSED) {
        fprintf(stderr, "Archphene camera node=%u\n",
                pw_stream_get_node_id(app->stream));
    } else if (state == PW_STREAM_STATE_STREAMING) {
        struct timespec start = {.tv_sec = 0, .tv_nsec = 1};
        struct timespec interval = {.tv_sec = 0, .tv_nsec = 33333333};
        pw_loop_update_timer(pw_main_loop_get_loop(app->loop),
                app->timer, &start, &interval, false);
    }
}

static void on_param_changed(void *userdata, uint32_t id,
        const struct spa_pod *param) {
    struct app *app = userdata;
    if (param == NULL || id != SPA_PARAM_Format) return;
    uint8_t storage[512];
    struct spa_pod_builder builder =
            SPA_POD_BUILDER_INIT(storage, sizeof(storage));
    const struct spa_pod *params[2];
    params[0] = spa_pod_builder_add_object(&builder,
            SPA_TYPE_OBJECT_ParamBuffers, SPA_PARAM_Buffers,
            SPA_PARAM_BUFFERS_buffers, SPA_POD_CHOICE_RANGE_Int(4, 2, MAX_BUFFERS),
            SPA_PARAM_BUFFERS_blocks, SPA_POD_Int(1),
            SPA_PARAM_BUFFERS_size, SPA_POD_Int(FRAME_BYTES),
            SPA_PARAM_BUFFERS_stride, SPA_POD_Int(WIDTH));
    params[1] = spa_pod_builder_add_object(&builder,
            SPA_TYPE_OBJECT_ParamMeta, SPA_PARAM_Meta,
            SPA_PARAM_META_type, SPA_POD_Id(SPA_META_Header),
            SPA_PARAM_META_size, SPA_POD_Int(sizeof(struct spa_meta_header)));
    pw_stream_update_params(app->stream, params, 2);
}

static const struct pw_stream_events stream_events = {
    PW_VERSION_STREAM_EVENTS,
    .state_changed = on_state_changed,
    .param_changed = on_param_changed,
    .process = on_process,
};

static void stop_app(void *userdata, int signal_number) {
    (void)signal_number;
    struct app *app = userdata;
    pw_main_loop_quit(app->loop);
}

int main(int argc, char **argv) {
    struct app app = {0};
    pthread_mutex_init(&app.camera.lock, NULL);
    atomic_init(&app.camera.stop, 0);
    const char *test_pattern = getenv("ARCHPHENE_PIPEWIRE_TEST_PATTERN");
    int test_pattern_enabled = test_pattern != NULL
            && strcmp(test_pattern, "1") == 0;
    if (test_pattern_enabled) {
        fill_test_frame(app.camera.frame);
    } else {
        wait_for_camera_access();
    }
    pw_init(&argc, &argv);
    app.loop = pw_main_loop_new(NULL);
    if (app.loop == NULL) return 70;
    pw_loop_add_signal(pw_main_loop_get_loop(app.loop), SIGINT, stop_app, &app);
    pw_loop_add_signal(pw_main_loop_get_loop(app.loop), SIGTERM, stop_app, &app);
    app.context = pw_context_new(pw_main_loop_get_loop(app.loop), NULL, 0);
    if (app.context == NULL) {
        app.result = 70;
        goto cleanup;
    }
    app.timer = pw_loop_add_timer(
            pw_main_loop_get_loop(app.loop), on_timeout, &app);
    for (int attempt = 0; attempt < 100 && app.core == NULL; attempt++) {
        app.core = pw_context_connect(app.context, NULL, 0);
        if (app.core == NULL) sleep_millis(50);
    }
    if (app.core == NULL) {
        fprintf(stderr, "Could not connect to private PipeWire core: %m\n");
        app.result = 70;
        goto cleanup;
    }
    app.stream = pw_stream_new(app.core, "Archphene Android Camera",
            pw_properties_new(
                    PW_KEY_MEDIA_CLASS, "Video/Source",
                    PW_KEY_MEDIA_TYPE, "Video",
                    PW_KEY_MEDIA_CATEGORY, "Capture",
                    PW_KEY_MEDIA_ROLE, "Camera",
                    PW_KEY_NODE_NAME, "archphene.android.camera",
                    PW_KEY_NODE_DESCRIPTION, "Android Camera",
                    PW_KEY_NODE_SUPPORTS_REQUEST, "1",
                    NULL));
    if (app.stream == NULL) {
        app.result = 70;
        goto cleanup;
    }
    pw_stream_add_listener(
            app.stream, &app.stream_listener, &stream_events, &app);
    uint8_t storage[512];
    struct spa_pod_builder builder =
            SPA_POD_BUILDER_INIT(storage, sizeof(storage));
    const struct spa_pod *format = spa_pod_builder_add_object(&builder,
            SPA_TYPE_OBJECT_Format, SPA_PARAM_EnumFormat,
            SPA_FORMAT_mediaType, SPA_POD_Id(SPA_MEDIA_TYPE_video),
            SPA_FORMAT_mediaSubtype, SPA_POD_Id(SPA_MEDIA_SUBTYPE_raw),
            SPA_FORMAT_VIDEO_format, SPA_POD_Id(SPA_VIDEO_FORMAT_I420),
            SPA_FORMAT_VIDEO_size, SPA_POD_Rectangle(&SPA_RECTANGLE(WIDTH, HEIGHT)),
            SPA_FORMAT_VIDEO_framerate, SPA_POD_Fraction(&SPA_FRACTION(30, 1)));
    if (pw_stream_connect(app.stream, PW_DIRECTION_OUTPUT, PW_ID_ANY,
            PW_STREAM_FLAG_DRIVER | PW_STREAM_FLAG_MAP_BUFFERS,
            &format, 1) < 0) {
        app.result = 70;
        goto cleanup;
    }
    if (pthread_create(&app.camera_thread, NULL, read_camera, &app.camera) != 0) {
        app.result = 70;
        goto cleanup;
    }
    pw_main_loop_run(app.loop);
    atomic_store(&app.camera.stop, 1);
    pthread_cancel(app.camera_thread);
    pthread_join(app.camera_thread, NULL);

cleanup:
    if (app.stream != NULL) pw_stream_destroy(app.stream);
    if (app.context != NULL) pw_context_destroy(app.context);
    if (app.loop != NULL) pw_main_loop_destroy(app.loop);
    pw_deinit();
    pthread_mutex_destroy(&app.camera.lock);
    return app.result;
}
