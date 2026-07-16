/*
 * Archphene Pulse source bridge backed by Android AAudio capture.
 *
 * The helper watches only streams connected to the private archphene_input
 * source. Android microphone consent is requested when the first stream is
 * attached, never when the wrapper or playback server starts.
 */

#include "../archphene-android-capability/archphene_android.h"

#include <aaudio/AAudio.h>
#include <pulse/pulseaudio.h>

#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define SOURCE_NAME "archphene_input"
#define SAMPLE_RATE 48000
#define CHANNEL_COUNT 1
#define FRAMES_PER_READ 480
#define SCAN_INTERVAL_MILLIS 250
#define PERMISSION_INTERVAL_MILLIS 250
#define DENIED_INTERVAL_MILLIS 1000

static volatile sig_atomic_t keep_running = 1;
static bool pulse_ready;
static bool source_known;
static bool scan_pending;
static bool source_active;
static uint32_t source_index = PA_INVALID_INDEX;
static unsigned scan_count;

static void signal_handler(int signal_number) {
    (void)signal_number;
    keep_running = 0;
}

static int64_t monotonic_millis(void) {
    struct timespec value;
    if (clock_gettime(CLOCK_MONOTONIC, &value) != 0) return 0;
    return (int64_t)value.tv_sec * 1000 + value.tv_nsec / 1000000;
}

static void log_line(const char *message) {
    fprintf(stderr, "audio-input: %s\n", message);
    fflush(stderr);
}

static void context_state_callback(pa_context *context, void *userdata) {
    (void)userdata;
    pa_context_state_t state = pa_context_get_state(context);
    if (state == PA_CONTEXT_READY) {
        pulse_ready = true;
        log_line("connected to private PulseAudio server");
    } else if (state == PA_CONTEXT_FAILED || state == PA_CONTEXT_TERMINATED) {
        pulse_ready = false;
        keep_running = 0;
        log_line("private PulseAudio context stopped");
    }
}

static void source_info_callback(pa_context *context, const pa_source_info *info,
        int end_of_list, void *userdata) {
    (void)context;
    (void)userdata;
    if (end_of_list < 0) {
        keep_running = 0;
        log_line("could not resolve private PulseAudio input source");
        return;
    }
    if (end_of_list > 0) {
        if (!source_known) {
            keep_running = 0;
            log_line("private PulseAudio input source is missing");
        }
        return;
    }
    if (info != NULL && info->name != NULL && strcmp(info->name, SOURCE_NAME) == 0) {
        source_index = info->index;
        source_known = true;
        log_line("private PulseAudio input source ready");
    }
}

static void source_output_callback(pa_context *context,
        const pa_source_output_info *info, int end_of_list, void *userdata) {
    (void)context;
    (void)userdata;
    if (end_of_list < 0) {
        scan_pending = false;
        return;
    }
    if (end_of_list > 0) {
        bool next = scan_count > 0;
        if (next != source_active) {
            source_active = next;
            log_line(next ? "Linux microphone stream attached"
                    : "Linux microphone stream detached");
        }
        scan_pending = false;
        return;
    }
    if (info != NULL && info->source == source_index) scan_count++;
}

static void start_source_lookup(pa_context *context) {
    pa_operation *operation = pa_context_get_source_info_by_name(
            context, SOURCE_NAME, source_info_callback, NULL);
    if (operation == NULL) {
        keep_running = 0;
        return;
    }
    pa_operation_unref(operation);
}

static void start_source_output_scan(pa_context *context) {
    scan_count = 0;
    scan_pending = true;
    pa_operation *operation = pa_context_get_source_output_info_list(
            context, source_output_callback, NULL);
    if (operation == NULL) {
        scan_pending = false;
        return;
    }
    pa_operation_unref(operation);
}

static void close_capture(AAudioStream **stream, int *fifo_fd) {
    if (*stream != NULL) {
        AAudioStream_requestStop(*stream);
        AAudioStream_close(*stream);
        *stream = NULL;
    }
    if (*fifo_fd >= 0) {
        close(*fifo_fd);
        *fifo_fd = -1;
    }
}

static int open_capture(AAudioStream **stream) {
    AAudioStreamBuilder *builder = NULL;
    aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK) return -1;
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_INPUT);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setSampleRate(builder, SAMPLE_RATE);
    AAudioStreamBuilder_setChannelCount(builder, CHANNEL_COUNT);
    result = AAudioStreamBuilder_openStream(builder, stream);
    AAudioStreamBuilder_delete(builder);
    if (result != AAUDIO_OK || *stream == NULL) return -1;
    if (AAudioStream_getFormat(*stream) != AAUDIO_FORMAT_PCM_I16
            || AAudioStream_getSampleRate(*stream) != SAMPLE_RATE
            || AAudioStream_getChannelCount(*stream) != CHANNEL_COUNT) {
        AAudioStream_close(*stream);
        *stream = NULL;
        errno = EPROTO;
        return -1;
    }
    result = AAudioStream_requestStart(*stream);
    if (result != AAUDIO_OK) {
        AAudioStream_close(*stream);
        *stream = NULL;
        return -1;
    }
    log_line("Android AAudio microphone capture started");
    return 0;
}

static int permission_request(bool request, char *response, size_t response_size) {
    int result = request
            ? archphene_android_request_audio_input(response, response_size)
            : archphene_android_check_audio_input(response, response_size);
    if (result < 0) {
        log_line("Android microphone permission broker unavailable");
        return -1;
    }
    return result;
}

int main(int argc, char **argv) {
    if (argc != 2 || argv[1][0] != '/') {
        fprintf(stderr, "usage: %s /absolute/input-fifo\n", argv[0]);
        return 64;
    }
    signal(SIGINT, signal_handler);
    signal(SIGTERM, signal_handler);
    signal(SIGPIPE, SIG_IGN);

    pa_mainloop *mainloop = pa_mainloop_new();
    if (mainloop == NULL) return 70;
    pa_context *context = pa_context_new(pa_mainloop_get_api(mainloop),
            "Archphene Android microphone bridge");
    if (context == NULL) {
        pa_mainloop_free(mainloop);
        return 70;
    }
    pa_context_set_state_callback(context, context_state_callback, NULL);
    if (pa_context_connect(context, NULL, PA_CONTEXT_NOFLAGS, NULL) < 0) {
        pa_context_unref(context);
        pa_mainloop_free(mainloop);
        return 69;
    }

    AAudioStream *stream = NULL;
    int fifo_fd = -1;
    int16_t samples[FRAMES_PER_READ * CHANNEL_COUNT];
    int permission_state = 0;
    int64_t next_scan = 0;
    int64_t next_permission = 0;
    bool lookup_started = false;
    while (keep_running) {
        int pulse_result = 0;
        if (pa_mainloop_iterate(mainloop, 0, &pulse_result) < 0) break;
        int64_t now = monotonic_millis();
        if (pulse_ready && !lookup_started) {
            start_source_lookup(context);
            lookup_started = true;
        }
        if (source_known && !scan_pending && now >= next_scan) {
            start_source_output_scan(context);
            next_scan = now + SCAN_INTERVAL_MILLIS;
        }
        if (!source_active) {
            if (stream != NULL || fifo_fd >= 0) {
                close_capture(&stream, &fifo_fd);
                log_line("Android AAudio microphone capture stopped");
            }
            usleep(10000);
            continue;
        }

        if (permission_state != 2 && now >= next_permission) {
            char response[128] = {0};
            bool request = permission_state == 0;
            int result = permission_request(request, response, sizeof(response));
            if (result == 0) {
                permission_state = 2;
                log_line("Android microphone permission granted");
            } else if (strcmp(response, "ERROR\tPERMISSION_REQUESTED") == 0) {
                permission_state = 1;
            } else if (strcmp(response, "ERROR\tPERMISSION_DENIED") == 0) {
                if (permission_state != 3) log_line("Android microphone permission denied");
                permission_state = 3;
            } else if (strcmp(response, "ERROR\tPERMISSION_NOT_REQUESTED") == 0) {
                permission_state = 0;
            }
            next_permission = now + (permission_state == 3
                    ? DENIED_INTERVAL_MILLIS : PERMISSION_INTERVAL_MILLIS);
        }
        if (permission_state != 2) {
            usleep(10000);
            continue;
        }
        if (stream == NULL && open_capture(&stream) != 0) {
            log_line("could not open Android AAudio microphone");
            permission_state = 0;
            next_permission = now + DENIED_INTERVAL_MILLIS;
            usleep(100000);
            continue;
        }
        if (fifo_fd < 0) {
            fifo_fd = open(argv[1], O_WRONLY | O_NONBLOCK | O_CLOEXEC);
            if (fifo_fd < 0) {
                usleep(10000);
                continue;
            }
        }
        aaudio_result_t frames = AAudioStream_read(stream, samples,
                FRAMES_PER_READ, 20LL * 1000 * 1000);
        if (frames == AAUDIO_ERROR_DISCONNECTED || frames < 0) {
            log_line("Android AAudio microphone disconnected");
            close_capture(&stream, &fifo_fd);
            usleep(100000);
            continue;
        }
        if (frames > 0) {
            size_t bytes = (size_t)frames * CHANNEL_COUNT * sizeof(int16_t);
            ssize_t written = write(fifo_fd, samples, bytes);
            if (written < 0 && errno != EAGAIN && errno != EWOULDBLOCK) {
                close(fifo_fd);
                fifo_fd = -1;
            }
        }
    }

    close_capture(&stream, &fifo_fd);
    pa_context_disconnect(context);
    pa_context_unref(context);
    pa_mainloop_free(mainloop);
    return keep_running ? 70 : 0;
}