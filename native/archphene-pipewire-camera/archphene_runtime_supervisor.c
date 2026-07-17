#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static const char CONFIG[] =
        "context.properties = { core.daemon = true core.name = pipewire-0 support.dbus = false "
        "default.video.width = 640 default.video.height = 480 }\n"
        "context.spa-libs = { support.* = support/libspa-support "
        "video.convert.* = videoconvert/libspa-videoconvert }\n"
        "context.modules = [ { name = libpipewire-module-protocol-native } "
        "{ name = libpipewire-module-access } "
        "{ name = libpipewire-module-metadata } "
        "{ name = libpipewire-module-client-node } "
        "{ name = libpipewire-module-adapter } "
        "{ name = libpipewire-module-link-factory } ]\n";
static const char CLIENT_CONFIG[] =
        "context.properties = { support.dbus = false }\n"
        "context.spa-libs = { support.* = support/libspa-support "
        "video.convert.* = videoconvert/libspa-videoconvert }\n"
        "context.modules = [ { name = libpipewire-module-protocol-native } "
        "{ name = libpipewire-module-client-node } "
        "{ name = libpipewire-module-adapter } ]\n";

static int write_all(int fd, const void *data, size_t size) {
    size_t offset = 0;
    while (offset < size) {
        ssize_t count = write(fd, (const char *)data + offset, size - offset);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return -1;
        offset += (size_t)count;
    }
    return 0;
}

static int join_path(char *output, size_t size, const char *root, const char *leaf) {
    int length = snprintf(output, size, "%s/%s", root, leaf);
    return length > 0 && (size_t)length < size ? 0 : -1;
}

static int write_config(const char *path, const char *content, size_t size) {
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (fd < 0) return -1;
    int result = write_all(fd, content, size) == 0 && fsync(fd) == 0 ? 0 : -1;
    if (close(fd) != 0) result = -1;
    return result;
}

static pid_t spawn_helper(const char *loader, const char *root,
        const char *name, const char *executable) {
    pid_t parent = getpid();
    pid_t child = fork();
    if (child != 0) return child;
    if (prctl(PR_SET_PDEATHSIG, SIGKILL) != 0 || getppid() != parent) _exit(125);
    int null_fd = open("/dev/null", O_RDWR | O_CLOEXEC);
    if (null_fd < 0) _exit(125);
    int output_fd = null_fd;
    const char *log_path = getenv("ARCHPHENE_PIPEWIRE_DEBUG_LOG");
    if (log_path != NULL && log_path[0] == '/') {
        output_fd = open(log_path,
                O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC | O_NOFOLLOW, 0600);
        if (output_fd < 0) _exit(125);
    }
    if (dup2(null_fd, STDIN_FILENO) < 0 || dup2(output_fd, STDOUT_FILENO) < 0
            || dup2(output_fd, STDERR_FILENO) < 0) _exit(125);
    if (output_fd != null_fd && output_fd > STDERR_FILENO) close(output_fd);
    if (null_fd > STDERR_FILENO) close(null_fd);
    if (strcmp(name, "archphene-pipewire") == 0
            && setenv("PIPEWIRE_CONFIG_NAME", "archphene-pipewire.conf", 1) != 0) {
        _exit(125);
    }
    execl(loader, loader, "--library-path", root, "--argv0", name,
            executable, (char *)NULL);
    _exit(126);
}

static void stop_helper(pid_t process) {
    if (process > 0) kill(process, SIGKILL);
}

static int helper_running(pid_t process) {
    int status = 0;
    return process > 0 && waitpid(process, &status, WNOHANG) == 0;
}

static void sleep_millis(long millis) {
    struct timespec delay = {
        .tv_sec = millis / 1000,
        .tv_nsec = (millis % 1000) * 1000000
    };
    while (nanosleep(&delay, &delay) != 0 && errno == EINTR) {}
}

int main(int argc, char **argv) {
    if (argc < 5) return 64;
    const char *runtime = getenv("XDG_RUNTIME_DIR");
    if (runtime == NULL || runtime[0] != '/') return 64;
    char config[4096];
    char client_config[4096];
    char socket[4096];
    char module_dir[4096];
    char spa_dir[4096];
    char daemon[4096];
    char camera[4096];
    char policy_path[4096];
    if (join_path(config, sizeof(config), runtime, "archphene-pipewire.conf") != 0
            || join_path(client_config, sizeof(client_config), runtime, "client.conf") != 0
            || join_path(socket, sizeof(socket), runtime, "pipewire-0") != 0
            || join_path(module_dir, sizeof(module_dir), argv[2], "pipewire-0.3") != 0
            || join_path(spa_dir, sizeof(spa_dir), argv[2], "spa-0.2") != 0
            || join_path(daemon, sizeof(daemon), argv[2], "archphene-pipewire") != 0
            || join_path(camera, sizeof(camera), argv[2], "archphene-pipewire-camera") != 0
            || join_path(policy_path, sizeof(policy_path), argv[2],
                    "archphene-pipewire-policy") != 0) {
        return 64;
    }
    if (write_config(config, CONFIG, sizeof(CONFIG) - 1) != 0
            || write_config(client_config, CLIENT_CONFIG,
                    sizeof(CLIENT_CONFIG) - 1) != 0) {
        return 70;
    }
    if (setenv("PIPEWIRE_CONFIG_DIR", runtime, 1) != 0
            || setenv("PIPEWIRE_CONFIG_NAME", "client.conf", 1) != 0
            || setenv("PIPEWIRE_MODULE_DIR", module_dir, 1) != 0
            || setenv("SPA_PLUGIN_DIR", spa_dir, 1) != 0
            || setenv("PIPEWIRE_REMOTE", "pipewire-0", 1) != 0) return 70;

    unlink(socket);
    char stale[4096];
    if (join_path(stale, sizeof(stale), runtime, "pipewire-0.lock") == 0) unlink(stale);
    if (join_path(stale, sizeof(stale), runtime, "pipewire-0-manager") == 0) unlink(stale);
    if (join_path(stale, sizeof(stale), runtime, "pipewire-0-manager.lock") == 0) unlink(stale);

    pid_t daemon_pid = spawn_helper(argv[1], argv[2], "archphene-pipewire", daemon);
    if (daemon_pid < 0) return 70;
    struct stat info;
    int ready = 0;
    for (int attempt = 0; attempt < 100; attempt++) {
        if (stat(socket, &info) == 0 && S_ISSOCK(info.st_mode)) {
            ready = 1;
            break;
        }
        if (!helper_running(daemon_pid)) break;
        sleep_millis(50);
    }
    if (!ready) {
        stop_helper(daemon_pid);
        return 70;
    }
    pid_t policy_pid = spawn_helper(
            argv[1], argv[2], "archphene-pipewire-policy", policy_path);
    if (policy_pid < 0) {
        stop_helper(daemon_pid);
        return 70;
    }
    pid_t camera_pid = spawn_helper(
            argv[1], argv[2], "archphene-pipewire-camera", camera);
    if (camera_pid < 0) {
        stop_helper(policy_pid);
        stop_helper(daemon_pid);
        return 70;
    }
    sleep_millis(100);
    if (!helper_running(policy_pid) || !helper_running(camera_pid)) {
        stop_helper(camera_pid);
        stop_helper(policy_pid);
        stop_helper(daemon_pid);
        return 70;
    }

    size_t app_arguments = (size_t)(argc - 5);
    char **target = calloc(app_arguments + 7, sizeof(char *));
    if (target == NULL) {
        stop_helper(camera_pid);
        stop_helper(policy_pid);
        stop_helper(daemon_pid);
        return 70;
    }
    target[0] = argv[1];
    target[1] = "--library-path";
    target[2] = argv[2];
    target[3] = "--argv0";
    target[4] = argv[3];
    target[5] = argv[4];
    for (size_t index = 0; index < app_arguments; index++) {
        target[index + 6] = argv[index + 5];
    }
    execv(argv[1], target);
    stop_helper(camera_pid);
    stop_helper(policy_pid);
    stop_helper(daemon_pid);
    return 126;
}
