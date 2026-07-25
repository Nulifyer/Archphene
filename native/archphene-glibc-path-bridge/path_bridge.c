#define _GNU_SOURCE

#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/stat.h>
#include <pwd.h>
#include <signal.h>
#include <spawn.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/fsuid.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <unistd.h>

/* Android PTYs are valid even when this glibc build's isatty probe is rejected. */
int isatty(int fd) {
    typedef int (*function_type)(int);
    function_type real = (function_type)dlsym(RTLD_NEXT, "isatty");
    if (real != NULL && real(fd) != 0) return 1;

    char descriptor[64];
    char target[PATH_MAX];
    int written = snprintf(descriptor, sizeof(descriptor), "/proc/self/fd/%d", fd);
    if (written <= 0 || (size_t)written >= sizeof(descriptor)) return 0;
    ssize_t length = readlink(descriptor, target, sizeof(target) - 1);
    if (length <= 0 || (size_t)length >= sizeof(target)) return 0;
    target[length] = '\0';
    return strcmp(target, "/dev/tty") == 0 || strncmp(target, "/dev/pts/", 9) == 0;
}
static bool has_parent_component(const char *path) {
    const char *part = path;
    while ((part = strstr(part, "..")) != NULL) {
        bool starts_component = part == path || part[-1] == '/';
        bool ends_component = part[2] == '\0' || part[2] == '/';
        if (starts_component && ends_component) return true;
        part += 2;
    }
    return false;
}

extern char **environ;

static char trusted_loader[PATH_MAX];
static char trusted_program_path[PATH_MAX];
static char trusted_root[PATH_MAX];
static bool fake_chroot_active;

static void report_blocked_syscall(int signal_number, siginfo_t *information,
        void *context) {
    (void)signal_number;
    (void)context;
    static const char prefix[] = "Archphene blocked Linux syscall ";
    char message[sizeof(prefix) + 16];
    size_t length = sizeof(prefix) - 1;
    memcpy(message, prefix, length);
    unsigned int value = information == NULL || information->si_syscall < 0
            ? 0U : (unsigned int)information->si_syscall;
    char digits[16];
    size_t digit_count = 0;
    do {
        digits[digit_count++] = (char)('0' + value % 10);
        value /= 10;
    } while (value != 0 && digit_count < sizeof(digits));
    while (digit_count > 0) message[length++] = digits[--digit_count];
    message[length++] = '\n';
    (void)write(STDERR_FILENO, message, length);
    _exit(128 + SIGSYS);
}

__attribute__((constructor))
static void install_sigsys_diagnostic(void) {
    struct sigaction action;
    memset(&action, 0, sizeof(action));
    action.sa_sigaction = report_blocked_syscall;
    action.sa_flags = SA_SIGINFO | SA_RESETHAND;
    sigemptyset(&action.sa_mask);
    (void)sigaction(SIGSYS, &action, NULL);
}

/*
 * The Linux root is entirely app-private, so its conventional uid 0 metadata
 * maps to the Android app uid. Pacman must see a root-like identity while the
 * kernel continues to enforce the real Android sandbox identity.
 */
uid_t getuid(void) { return 0; }
uid_t geteuid(void) { return 0; }
gid_t getgid(void) { return 0; }
gid_t getegid(void) { return 0; }

static struct passwd archphene_passwd = {
    .pw_name = "archphene",
    .pw_passwd = "x",
    .pw_uid = 0,
    .pw_gid = 0,
    .pw_gecos = "Archphene",
    .pw_dir = "/home/archphene",
    .pw_shell = "/usr/bin/bash",
};

static struct passwd root_passwd = {
    .pw_name = "root",
    .pw_passwd = "x",
    .pw_uid = 0,
    .pw_gid = 0,
    .pw_gecos = "Archphene system user",
    .pw_dir = "/home/archphene",
    .pw_shell = "/usr/bin/bash",
};

static int copy_passwd(const struct passwd *source, struct passwd *output,
        char *buffer, size_t buffer_size, struct passwd **result) {
    const char *fields[] = {
        source->pw_name, source->pw_passwd, source->pw_gecos,
        source->pw_dir, source->pw_shell
    };
    size_t lengths[sizeof(fields) / sizeof(fields[0])];
    size_t required = 0;
    for (size_t index = 0; index < sizeof(fields) / sizeof(fields[0]); index++) {
        lengths[index] = strlen(fields[index]) + 1;
        if (lengths[index] > buffer_size - required) {
            *result = NULL;
            return ERANGE;
        }
        required += lengths[index];
    }
    char *values[sizeof(fields) / sizeof(fields[0])];
    size_t offset = 0;
    for (size_t index = 0; index < sizeof(fields) / sizeof(fields[0]); index++) {
        values[index] = buffer + offset;
        memcpy(values[index], fields[index], lengths[index]);
        offset += lengths[index];
    }
    *output = (struct passwd) {
        .pw_name = values[0],
        .pw_passwd = values[1],
        .pw_uid = source->pw_uid,
        .pw_gid = source->pw_gid,
        .pw_gecos = values[2],
        .pw_dir = values[3],
        .pw_shell = values[4],
    };
    *result = output;
    return 0;
}

struct passwd *getpwuid(uid_t user) {
    return user == 0 ? &archphene_passwd : NULL;
}

int getpwuid_r(uid_t user, struct passwd *output, char *buffer,
        size_t buffer_size, struct passwd **result) {
    if (user != 0) {
        *result = NULL;
        return 0;
    }
    return copy_passwd(&archphene_passwd, output, buffer, buffer_size, result);
}

struct passwd *getpwnam(const char *name) {
    if (strcmp(name, "archphene") == 0) return &archphene_passwd;
    return strcmp(name, "root") == 0 ? &root_passwd : NULL;
}

int getpwnam_r(const char *name, struct passwd *output, char *buffer,
        size_t buffer_size, struct passwd **result) {
    const struct passwd *source = NULL;
    if (strcmp(name, "archphene") == 0) {
        source = &archphene_passwd;
    } else if (strcmp(name, "root") == 0) {
        source = &root_passwd;
    }
    if (source == NULL) {
        *result = NULL;
        return 0;
    }
    return copy_passwd(source, output, buffer, buffer_size, result);
}

int setfsuid(uid_t user) {
    (void)user;
    return 0;
}
int setfsgid(gid_t group) {
    (void)group;
    return 0;
}
int chown(const char *path, uid_t owner, gid_t group) {
    (void)path;
    (void)owner;
    (void)group;
    return 0;
}
int lchown(const char *path, uid_t owner, gid_t group) {
    (void)path;
    (void)owner;
    (void)group;
    return 0;
}
int fchown(int fd, uid_t owner, gid_t group) {
    (void)fd;
    (void)owner;
    (void)group;
    return 0;
}
int fchownat(int directory, const char *path, uid_t owner, gid_t group, int flags) {
    (void)directory;
    (void)path;
    (void)owner;
    (void)group;
    (void)flags;
    return 0;
}

int fchmodat(int directory, const char *path, mode_t mode, int flags) {
    if (flags == 0) {
        return (int)syscall(SYS_fchmodat, directory, path, mode);
    }
    if (flags == AT_EMPTY_PATH && path[0] == '\0') {
        return fchmod(directory, mode);
    }
    if (flags == AT_SYMLINK_NOFOLLOW) {
        struct stat metadata;
        if (fstatat(directory, path, &metadata, AT_SYMLINK_NOFOLLOW) != 0) return -1;
        if (S_ISLNK(metadata.st_mode)) return 0;
        return (int)syscall(SYS_fchmodat, directory, path, mode);
    }
    errno = EINVAL;
    return -1;
}

static int copy_hardlink_fallback(int source_directory, const char *source,
        int destination_directory, const char *destination) {
    typedef int (*openat_type)(int, const char *, int, ...);
    typedef int (*unlinkat_type)(int, const char *, int);
    openat_type real_openat = (openat_type)dlsym(RTLD_NEXT, "openat");
    unlinkat_type real_unlinkat = (unlinkat_type)dlsym(RTLD_NEXT, "unlinkat");
    if (real_openat == NULL || real_unlinkat == NULL) {
        errno = ENOSYS;
        return -1;
    }
    int input = real_openat(source_directory, source, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (input < 0) return -1;
    int output = real_openat(destination_directory, destination,
            O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (output < 0) {
        int saved_errno = errno;
        close(input);
        errno = saved_errno;
        return -1;
    }
    char buffer[64 * 1024];
    int result = 0;
    while (true) {
        ssize_t count = read(input, buffer, sizeof(buffer));
        if (count == 0) break;
        if (count < 0) {
            if (errno == EINTR) continue;
            result = -1;
            break;
        }
        ssize_t offset = 0;
        while (offset < count) {
            ssize_t written = write(output, buffer + offset, (size_t)(count - offset));
            if (written < 0 && errno == EINTR) continue;
            if (written <= 0) {
                result = -1;
                break;
            }
            offset += written;
        }
        if (result != 0) break;
    }
    int saved_errno = errno;
    if (close(input) != 0 || close(output) != 0) result = -1;
    if (result != 0) real_unlinkat(destination_directory, destination, 0);
    errno = saved_errno;
    return result;
}

int link(const char *source, const char *destination) {
    typedef int (*function_type)(const char *, const char *);
    function_type real = (function_type)dlsym(RTLD_NEXT, "link");
    if (real == NULL) {
        errno = ENOSYS;
        return -1;
    }
    if (real(source, destination) == 0) return 0;
    if (errno != EPERM && errno != EACCES) return -1;
    return copy_hardlink_fallback(AT_FDCWD, source, AT_FDCWD, destination);
}

int linkat(int source_directory, const char *source, int destination_directory,
        const char *destination, int flags) {
    typedef int (*function_type)(int, const char *, int, const char *, int);
    function_type real = (function_type)dlsym(RTLD_NEXT, "linkat");
    if (real == NULL) {
        errno = ENOSYS;
        return -1;
    }
    if (real(source_directory, source, destination_directory, destination, flags) == 0) {
        return 0;
    }
    if ((errno != EPERM && errno != EACCES) || flags != 0) return -1;
    return copy_hardlink_fallback(
            source_directory, source, destination_directory, destination);
}

__attribute__((constructor))
static void capture_trusted_loader(void) {
    const char *candidate = getenv("ARCHPHENE_RUNTIME_LOADER");
    if (candidate == NULL || candidate[0] != '/' || has_parent_component(candidate)
            || strchr(candidate, '\n') != NULL) {
        return;
    }
    char resolved[PATH_MAX];
    if (realpath(candidate, resolved) == NULL) return;
    struct stat metadata;
    if (stat(resolved, &metadata) != 0 || !S_ISREG(metadata.st_mode)
            || (metadata.st_mode & 0111) == 0
            || (metadata.st_mode & (S_IWGRP | S_IWOTH)) != 0) {
        return;
    }
    size_t length = strlen(candidate);
    if (length >= sizeof(trusted_loader)) return;
    memcpy(trusted_loader, candidate, length + 1);
}

__attribute__((constructor))
static void capture_trusted_program_path(void) {
    const char *candidate = getenv("ARCHPHENE_RUNTIME_PROGRAM_PATH");
    if (candidate == NULL || candidate[0] != '/' || has_parent_component(candidate)
            || strchr(candidate, '\n') != NULL) {
        return;
    }
    char resolved[PATH_MAX];
    if (realpath(candidate, resolved) == NULL) return;
    struct stat metadata;
    if (stat(resolved, &metadata) != 0 || !S_ISREG(metadata.st_mode)
            || (metadata.st_mode & (S_IWGRP | S_IWOTH)) != 0) {
        return;
    }
    size_t length = strlen(resolved);
    if (length >= sizeof(trusted_program_path)) return;
    memcpy(trusted_program_path, resolved, length + 1);
}

__attribute__((constructor))
static void capture_trusted_root(void) {
    const char *candidate = getenv("ARCHPHENE_RUNTIME_ROOT");
    if (candidate == NULL || candidate[0] != '/' || has_parent_component(candidate)
            || strchr(candidate, '\n') != NULL) {
        return;
    }
    char resolved[PATH_MAX];
    if (realpath(candidate, resolved) == NULL) return;
    struct stat metadata;
    if (stat(resolved, &metadata) != 0 || !S_ISDIR(metadata.st_mode)
            || (metadata.st_mode & (S_IWGRP | S_IWOTH)) != 0) {
        return;
    }
    size_t length = strlen(resolved);
    if (length >= sizeof(trusted_root)) return;
    memcpy(trusted_root, resolved, length + 1);
    const char *active = getenv("ARCHPHENE_FAKE_CHROOT");
    fake_chroot_active = active != NULL && strcmp(active, "1") == 0;
}

static bool safe_command_name(const char *name) {
    if (name == NULL || name[0] == '\0' || strchr(name, '/') != NULL) return false;
    for (const unsigned char *cursor = (const unsigned char *)name; *cursor != '\0'; cursor++) {
        if (!((*cursor >= 'A' && *cursor <= 'Z')
                || (*cursor >= 'a' && *cursor <= 'z')
                || (*cursor >= '0' && *cursor <= '9')
                || *cursor == '.' || *cursor == '_' || *cursor == '+' || *cursor == '-')) {
            return false;
        }
    }
    return true;
}

static bool runtime_command(const char *name, char output[PATH_MAX]) {
    if (!safe_command_name(name)) return false;
    const char *directory = getenv("ARCHPHENE_RUNTIME_COMMAND_DIR");
    if (directory == NULL || directory[0] != '/' || strchr(directory, '\n') != NULL) {
        return false;
    }
    struct stat metadata;
    typedef int (*access_type)(const char *, int);
    access_type real_access = (access_type)dlsym(RTLD_NEXT, "access");
    int length = snprintf(output, PATH_MAX, "%s/%s", directory, name);
    if (length > 0 && length < PATH_MAX
            && stat(output, &metadata) == 0 && S_ISREG(metadata.st_mode)
            && (metadata.st_mode & (S_IWGRP | S_IWOTH)) == 0
            && real_access != NULL && real_access(output, R_OK) == 0) {
        return true;
    }
    if (trusted_root[0] == '\0') return false;
    length = snprintf(output, PATH_MAX, "%s/usr/bin/%s", trusted_root, name);
    return length > 0 && length < PATH_MAX
            && stat(output, &metadata) == 0 && S_ISREG(metadata.st_mode)
            && (metadata.st_mode & (S_IWGRP | S_IWOTH)) == 0
            && real_access != NULL && real_access(output, R_OK) == 0;
}

static bool conventional_linux_command(const char *path, const char *name) {
    if (path == NULL || name == NULL) return false;
    const char *component = NULL;
    if (strncmp(path, "/usr/bin/", 9) == 0) {
        component = path + 9;
    } else if (strncmp(path, "/bin/", 5) == 0) {
        component = path + 5;
    }
    return component != NULL && strcmp(component, name) == 0;
}

static bool inside_trusted_root(const char *path) {
    if (trusted_root[0] == '\0' || path == NULL) return false;
    size_t root_length = strlen(trusted_root);
    return strncmp(path, trusted_root, root_length) == 0
            && (path[root_length] == '\0' || path[root_length] == '/');
}

static bool safe_root_executable(const char *path, char output[PATH_MAX]) {
    if (path == NULL || path[0] != '/' || trusted_root[0] == '\0'
            || has_parent_component(path) || strchr(path, '\n') != NULL) {
        return false;
    }
    char candidate[PATH_MAX];
    if (inside_trusted_root(path)) {
        size_t length = strlen(path);
        if (length >= sizeof(candidate)) return false;
        memcpy(candidate, path, length + 1);
    } else {
        int length = snprintf(candidate, sizeof(candidate), "%s%s",
                trusted_root, path);
        if (length <= 0 || (size_t)length >= sizeof(candidate)) return false;
    }
    if (realpath(candidate, output) == NULL || !inside_trusted_root(output)) {
        return false;
    }
    struct stat metadata;
    if (stat(output, &metadata) != 0 || !S_ISREG(metadata.st_mode)
            || (metadata.st_mode & 0111) == 0
            || (metadata.st_mode & (S_IWGRP | S_IWOTH)) != 0) {
        return false;
    }
    typedef int (*access_type)(const char *, int);
    access_type real_access = (access_type)dlsym(RTLD_NEXT, "access");
    return real_access != NULL && real_access(output, R_OK) == 0;
}

static bool resolve_runtime_executable(const char *path, char output[PATH_MAX]) {
    if (path == NULL || path[0] != '/') return false;
    const char *name = strrchr(path, '/');
    name = name == NULL ? path : name + 1;
    char command[PATH_MAX];
    if (runtime_command(name, command)
            && (strcmp(path, command) == 0
                || conventional_linux_command(path, name))) {
        size_t length = strlen(command);
        if (length >= PATH_MAX) return false;
        memcpy(output, command, length + 1);
        return true;
    }
    return safe_root_executable(path, output);
}

static void complete_managed_maintenance_command(const char *name) {
    /*
     * Arch ships ldconfig as a static PIE, so it cannot participate in the
     * preload path translation. Archphene always supplies the root's library
     * path directly to its dynamic loader, making the conventional cache
     * redundant. Pacman runs this in a dedicated child after transactions.
     */
    if (strcmp(name, "ldconfig") == 0) _exit(EXIT_SUCCESS);
}

static int launch_runtime_executable(const char *name, const char *command,
        char *const arguments[],
        char *const environment[]) {
    complete_managed_maintenance_command(name);
    const char *library_path = getenv("ARCHPHENE_RUNTIME_LIB");
    if (trusted_loader[0] == '\0' || library_path == NULL
            || library_path[0] != '/'
            || strchr(library_path, '\n') != NULL) {
        errno = EACCES;
        return -1;
    }
    size_t count = 0;
    while (arguments[count] != NULL) {
        if (++count > 4088) {
            errno = E2BIG;
            return -1;
        }
    }
    char *loader_arguments[4096];
    loader_arguments[0] = trusted_loader;
    loader_arguments[1] = "--library-path";
    loader_arguments[2] = (char *)library_path;
    loader_arguments[3] = "--argv0";
    loader_arguments[4] = (char *)name;
    loader_arguments[5] = (char *)command;
    for (size_t index = 1; index < count; index++) {
        loader_arguments[index + 5] = arguments[index];
    }
    loader_arguments[count + 5] = NULL;
    typedef int (*function_type)(const char *, char *const[], char *const[]);
    function_type real = (function_type)dlsym(RTLD_NEXT, "execve");
    if (real == NULL) {
        errno = ENOSYS;
        return -1;
    }
    real(trusted_loader, loader_arguments,
            environment == NULL ? environ : environment);
    return -1;
}

static int launch_runtime_command(const char *name, char *const arguments[],
        char *const environment[]) {
    char command[PATH_MAX];
    if (!runtime_command(name, command)) return 1;
    return launch_runtime_executable(name, command, arguments, environment);
}

static int spawn_runtime_executable(pid_t *process, const char *name,
        const char *command,
        const posix_spawn_file_actions_t *file_actions,
        const posix_spawnattr_t *attributes, char *const arguments[],
        char *const environment[]) {
    const char *library_path = getenv("ARCHPHENE_RUNTIME_LIB");
    if (trusted_loader[0] == '\0' || library_path == NULL
            || library_path[0] != '/' || strchr(library_path, '\n') != NULL) {
        return EACCES;
    }
    size_t count = 0;
    while (arguments[count] != NULL) {
        if (++count > 4088) return E2BIG;
    }
    char *loader_arguments[4096];
    loader_arguments[0] = trusted_loader;
    loader_arguments[1] = "--library-path";
    loader_arguments[2] = (char *)library_path;
    loader_arguments[3] = "--argv0";
    loader_arguments[4] = (char *)name;
    loader_arguments[5] = (char *)command;
    for (size_t index = 1; index < count; index++) {
        loader_arguments[index + 5] = arguments[index];
    }
    loader_arguments[count + 5] = NULL;
    typedef int (*function_type)(pid_t *, const char *,
            const posix_spawn_file_actions_t *, const posix_spawnattr_t *,
            char *const[], char *const[]);
    function_type real = (function_type)dlsym(RTLD_NEXT, "posix_spawn");
    if (real == NULL) return ENOSYS;
    return real(process, trusted_loader, file_actions, attributes,
            loader_arguments, environment == NULL ? environ : environment);
}

static int spawn_runtime_command(pid_t *process, const char *name,
        const posix_spawn_file_actions_t *file_actions,
        const posix_spawnattr_t *attributes, char *const arguments[],
        char *const environment[]) {
    char command[PATH_MAX];
    if (!runtime_command(name, command)) return ENOENT;
    return spawn_runtime_executable(process, name, command, file_actions,
            attributes, arguments, environment);
}

int posix_spawn(pid_t *process, const char *path,
        const posix_spawn_file_actions_t *file_actions,
        const posix_spawnattr_t *attributes, char *const arguments[],
        char *const environment[]) {
    if (trusted_loader[0] != '\0' && strcmp(path, trusted_loader) == 0) {
        typedef int (*function_type)(pid_t *, const char *,
                const posix_spawn_file_actions_t *, const posix_spawnattr_t *,
                char *const[], char *const[]);
        function_type real = (function_type)dlsym(RTLD_NEXT, "posix_spawn");
        return real == NULL ? ENOSYS : real(process, path, file_actions,
                attributes, arguments, environment == NULL ? environ : environment);
    }
    char command[PATH_MAX];
    if (!resolve_runtime_executable(path, command)) return ENOENT;
    const char *name = strrchr(path, '/');
    name = name == NULL ? path : name + 1;
    return spawn_runtime_executable(process, name, command, file_actions,
            attributes, arguments, environment);
}

int posix_spawnp(pid_t *process, const char *file,
    const posix_spawn_file_actions_t *file_actions,
    const posix_spawnattr_t *attributes, char *const arguments[],
    char *const environment[]) {
    const char *separator = strrchr(file, '/');
    if (separator != NULL) {
        char command[PATH_MAX];
        if (!resolve_runtime_executable(file, command)) return ENOENT;
        return spawn_runtime_executable(process, separator + 1, command,
                file_actions, attributes, arguments, environment);
    }
    return spawn_runtime_command(process,
            file, file_actions, attributes, arguments, environment);
}

static int launch_android_system_command(const char *name, char *const arguments[],
        char *const environment[]) {
    const char *path;
    if (strcmp(name, "cat") == 0) {
        path = "/system/bin/cat";
    } else if (strcmp(name, "sleep") == 0) {
        path = "/system/bin/sleep";
    } else {
        return 1;
    }
    if (access(path, X_OK) != 0) return -1;
    char *const *source = environment == NULL ? environ : environment;
    char *clean_environment[4096];
    size_t output_count = 0;
    for (size_t index = 0; source[index] != NULL; index++) {
        if (index >= 4095) {
            errno = E2BIG;
            return -1;
        }
        if (strncmp(source[index], "LD_PRELOAD=", 11) != 0
                && strncmp(source[index], "LD_LIBRARY_PATH=", 16) != 0) {
            clean_environment[output_count++] = source[index];
        }
    }
    clean_environment[output_count] = NULL;
    typedef int (*function_type)(const char *, char *const[], char *const[]);
    function_type real = (function_type)dlsym(RTLD_NEXT, "execve");
    if (real == NULL) {
        errno = ENOSYS;
        return -1;
    }
    real(path, arguments, clean_environment);
    return -1;
}

static int launch_runtime_file(const char *file, char *const arguments[],
        char *const environment[]) {
    const char *separator = strrchr(file, '/');
    if (separator == NULL) {
        return launch_runtime_command(file, arguments, environment);
    }
    char command[PATH_MAX];
    if (!resolve_runtime_executable(file, command)) return 1;
    return launch_runtime_executable(separator + 1, command, arguments, environment);
}

int execve(const char *path, char *const arguments[], char *const environment[]) {
    if (trusted_loader[0] != '\0' && strcmp(path, trusted_loader) == 0) {
        typedef int (*function_type)(const char *, char *const[], char *const[]);
        function_type real = (function_type)dlsym(RTLD_NEXT, "execve");
        if (real == NULL) {
            errno = ENOSYS;
            return -1;
        }
        return real(path, arguments, environment == NULL ? environ : environment);
    }
    const char *name = strrchr(path, '/');
    name = name == NULL ? path : name + 1;
    char command[PATH_MAX];
    if (resolve_runtime_executable(path, command)) {
        return launch_runtime_executable(name, command, arguments, environment);
    }
    if (strcmp(path, "/system/bin/cat") == 0
            || strcmp(path, "/system/bin/sleep") == 0) {
        return launch_android_system_command(name, arguments, environment);
    }
    errno = ENOENT;
    return -1;
}

int execv(const char *path, char *const arguments[]) {
    const char *name = strrchr(path, '/');
    name = name == NULL ? path : name + 1;
    char command[PATH_MAX];
    if (resolve_runtime_executable(path, command)) {
        return launch_runtime_executable(name, command, arguments, environ);
    }
    errno = ENOENT;
    return -1;
}

int execvp(const char *file, char *const arguments[]) {
    int bridged = launch_runtime_file(file, arguments, environ);
    if (bridged <= 0) return -1;
    bridged = launch_android_system_command(file, arguments, environ);
    if (bridged <= 0) return -1;
    errno = ENOENT;
    return -1;
}

int execvpe(const char *file, char *const arguments[], char *const environment[]) {
    int bridged = launch_runtime_file(file, arguments, environment);
    if (bridged <= 0) return -1;
    bridged = launch_android_system_command(file, arguments, environment);
    if (bridged <= 0) return -1;
    errno = ENOENT;
    return -1;
}

int execlp(const char *file, const char *argument, ...) {
    char *arguments[4096];
    size_t count = 0;
    arguments[count++] = (char *)argument;
    va_list values;
    va_start(values, argument);
    while (count < 4096) {
        char *value = va_arg(values, char *);
        arguments[count++] = value;
        if (value == NULL) break;
    }
    va_end(values);
    if (count == 4096 && arguments[count - 1] != NULL) {
        errno = E2BIG;
        return -1;
    }
    int bridged = launch_runtime_file(file, arguments, environ);
    if (bridged <= 0) return -1;
    bridged = launch_android_system_command(file, arguments, environ);
    if (bridged <= 0) return -1;
    errno = ENOENT;
    return -1;
}

static const char *translate_path(const char *path, char output[PATH_MAX],
        bool *translated) {
    static const char *const fake_root_prefixes[] = {
        "/bin", "/etc", "/home", "/lib", "/lib64", "/opt", "/run",
        "/sbin", "/tmp", "/usr", "/var"
    };
    static const char *const resource_prefixes[] = {"/usr/share", "/usr/lib/locale"};
    *translated = false;
    if (path == NULL) return path;
    if ((fake_chroot_active || path[0] == '/') && has_parent_component(path)) {
        errno = EACCES;
        return NULL;
    }
    if (trusted_root[0] != '\0') {
        size_t root_length = strlen(trusted_root);
        if (strncmp(path, trusted_root, root_length) == 0
                && (path[root_length] == '\0' || path[root_length] == '/')) {
            return path;
        }
    }
    bool allowed = false;
    if (fake_chroot_active && strcmp(path, "/") == 0) {
        allowed = true;
    }
    const char *const *prefixes =
            fake_chroot_active ? fake_root_prefixes : resource_prefixes;
    size_t prefix_count = fake_chroot_active
            ? sizeof(fake_root_prefixes) / sizeof(fake_root_prefixes[0])
            : sizeof(resource_prefixes) / sizeof(resource_prefixes[0]);
    for (size_t index = 0; !allowed && index < prefix_count; index++) {
        size_t length = strlen(prefixes[index]);
        if (strncmp(path, prefixes[index], length) == 0
                && (path[length] == '\0' || path[length] == '/')) {
            allowed = true;
        }
    }
    if (!allowed) return path;
    if (trusted_root[0] == '\0') return path;
    size_t root_length = strlen(trusted_root);
    size_t path_length = strlen(path);
    if (root_length + path_length + 1 > PATH_MAX) {
        errno = ENAMETOOLONG;
        return NULL;
    }
    memcpy(output, trusted_root, root_length);
    memcpy(output + root_length, path, path_length + 1);
    *translated = true;
    return output;
}

static int translate_unix_address(const struct sockaddr *address,
        socklen_t address_length, struct sockaddr_un *output,
        socklen_t *output_length, bool *translated) {
    *translated = false;
    if (address == NULL || address_length < sizeof(sa_family_t)
            || address->sa_family != AF_UNIX) {
        return 0;
    }
    const size_t path_offset = offsetof(struct sockaddr_un, sun_path);
    if ((size_t)address_length <= path_offset) return 0;
    const struct sockaddr_un *unix_address =
            (const struct sockaddr_un *)address;
    size_t available = (size_t)address_length - path_offset;
    if (available > sizeof(unix_address->sun_path)) {
        available = sizeof(unix_address->sun_path);
    }
    if (unix_address->sun_path[0] == '\0') return 0;
    size_t path_length = strnlen(unix_address->sun_path, available);
    if (path_length == 0) return 0;
    if (path_length == available
            && path_length == sizeof(unix_address->sun_path)) {
        errno = ENAMETOOLONG;
        return -1;
    }
    char linux_path[sizeof(unix_address->sun_path) + 1];
    memcpy(linux_path, unix_address->sun_path, path_length);
    linux_path[path_length] = '\0';

    bool path_translated;
    char translated_path[PATH_MAX];
    const char *target =
            translate_path(linux_path, translated_path, &path_translated);
    if (target == NULL) return -1;
    if (!path_translated) return 0;
    size_t target_length = strlen(target);
    if (target_length >= sizeof(output->sun_path)) {
        errno = ENAMETOOLONG;
        return -1;
    }
    memset(output, 0, sizeof(*output));
    output->sun_family = AF_UNIX;
    memcpy(output->sun_path, target, target_length + 1);
    *output_length = (socklen_t)(path_offset + target_length + 1);
    *translated = true;
    return 0;
}

static int socket_address_call(const char *symbol, int descriptor,
        const struct sockaddr *address, socklen_t address_length) {
    typedef int (*function_type)(int, const struct sockaddr *, socklen_t);
    function_type real = (function_type)dlsym(RTLD_NEXT, symbol);
    if (real == NULL) {
        errno = ENOSYS;
        return -1;
    }
    struct sockaddr_un translated_address;
    socklen_t translated_length = 0;
    bool translated;
    if (translate_unix_address(address, address_length, &translated_address,
                &translated_length, &translated) != 0) {
        return -1;
    }
    return translated
            ? real(descriptor, (const struct sockaddr *)&translated_address,
                    translated_length)
            : real(descriptor, address, address_length);
}

int connect(int descriptor, const struct sockaddr *address,
        socklen_t address_length) {
    return socket_address_call("connect", descriptor, address, address_length);
}

int bind(int descriptor, const struct sockaddr *address,
        socklen_t address_length) {
    return socket_address_call("bind", descriptor, address, address_length);
}

int chroot(const char *path) {
    if (trusted_root[0] == '\0' || has_parent_component(path)) {
        errno = EACCES;
        return -1;
    }
    char resolved[PATH_MAX];
    if (realpath(path, resolved) == NULL || strcmp(resolved, trusted_root) != 0) {
        errno = EACCES;
        return -1;
    }
    typedef int (*function_type)(const char *);
    function_type real = (function_type)dlsym(RTLD_NEXT, "chdir");
    if (real == NULL) {
        errno = ENOSYS;
        return -1;
    }
    if (real(trusted_root) != 0) return -1;
    if (setenv("ARCHPHENE_FAKE_CHROOT", "1", 1) != 0) return -1;
    fake_chroot_active = true;
    return 0;
}

int chdir(const char *path) {
    typedef int (*function_type)(const char *);
    function_type real = (function_type)dlsym(RTLD_NEXT, "chdir");
    bool translated;
    char buffer[PATH_MAX];
    const char *target = translate_path(path, buffer, &translated);
    if (target == NULL) return -1;
    if (real == NULL) {
        errno = ENOSYS;
        return -1;
    }
    return real(target);
}

static char *linux_cwd(char *path) {
    if (path == NULL || trusted_root[0] == '\0' || !fake_chroot_active) return path;
    size_t root_length = strlen(trusted_root);
    if (strncmp(path, trusted_root, root_length) != 0
            || (path[root_length] != '\0' && path[root_length] != '/')) {
        return path;
    }
    const char *relative = path + root_length;
    if (relative[0] == '\0') {
        path[0] = '/';
        path[1] = '\0';
    } else {
        memmove(path, relative, strlen(relative) + 1);
    }
    return path;
}

char *getcwd(char *buffer, size_t size) {
    typedef char *(*function_type)(char *, size_t);
    function_type real = (function_type)dlsym(RTLD_NEXT, "getcwd");
    if (real == NULL) {
        errno = ENOSYS;
        return NULL;
    }
    return linux_cwd(real(buffer, size));
}

char *get_current_dir_name(void) {
    typedef char *(*function_type)(void);
    function_type real = (function_type)dlsym(RTLD_NEXT, "get_current_dir_name");
    if (real == NULL) {
        errno = ENOSYS;
        return NULL;
    }
    return linux_cwd(real());
}

static bool write_flags(int flags) {
    return (flags & O_ACCMODE) != O_RDONLY
            || (flags & (O_CREAT | O_TRUNC | O_APPEND | O_TMPFILE)) != 0;
}

static bool write_mode(const char *mode) {
    return mode != NULL && (strchr(mode, 'w') != NULL || strchr(mode, 'a') != NULL
            || strchr(mode, '+') != NULL);
}

#define RESOLVE(type, name) ((type)dlsym(RTLD_NEXT, name))
#define REQUIRE_REAL(real) do { if ((real) == NULL) { errno = ENOSYS; return -1; } } while (0)

static int open_shm_directory(const char *name, char component[NAME_MAX + 1]) {
    if (name == NULL || name[0] != '/' || name[1] == '\0'
            || strchr(name + 1, '/') != NULL) {
        errno = EINVAL;
        return -1;
    }
    size_t length = strlen(name + 1);
    if (length > NAME_MAX || strcmp(name + 1, ".") == 0
            || strcmp(name + 1, "..") == 0) {
        errno = EINVAL;
        return -1;
    }
    memcpy(component, name + 1, length + 1);

    const char *runtime = getenv("XDG_RUNTIME_DIR");
    if (runtime == NULL || runtime[0] != '/' || strchr(runtime, '\n') != NULL) {
        errno = ENOENT;
        return -1;
    }
    char directory[PATH_MAX];
    int written = snprintf(directory, sizeof(directory), "%s/.archphene-shm", runtime);
    if (written <= 0 || (size_t)written >= sizeof(directory)) {
        errno = ENAMETOOLONG;
        return -1;
    }
    if (mkdirat(AT_FDCWD, directory, 0700) != 0 && errno != EEXIST) return -1;
    return openat(AT_FDCWD, directory,
            O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
}

int shm_open(const char *name, int flags, mode_t mode) {
    char component[NAME_MAX + 1];
    int directory = open_shm_directory(name, component);
    if (directory < 0) return -1;
    int result = openat(directory, component,
            flags | O_CLOEXEC | O_NOFOLLOW, mode);
    int saved_errno = errno;
    close(directory);
    errno = saved_errno;
    return result;
}

int shm_unlink(const char *name) {
    char component[NAME_MAX + 1];
    int directory = open_shm_directory(name, component);
    if (directory < 0) return -1;
    int result = unlinkat(directory, component, 0);
    int saved_errno = errno;
    close(directory);
    errno = saved_errno;
    return result;
}
static int open_impl(const char *symbol, const char *path, int flags, mode_t mode,
        bool has_mode) {
    typedef int (*function_type)(const char *, int, ...);
    function_type real = RESOLVE(function_type, symbol);
    bool translated;
    char buffer[PATH_MAX];
    const char *target = translate_path(path, buffer, &translated);
    if (target == NULL) return -1;
    REQUIRE_REAL(real);
    if (translated && !fake_chroot_active && write_flags(flags)) {
        errno = EROFS;
        return -1;
    }
    return has_mode ? real(target, flags, mode) : real(target, flags);
}

int open(const char *path, int flags, ...) {
    mode_t mode = 0;
    bool has_mode = (flags & (O_CREAT | O_TMPFILE)) != 0;
    if (has_mode) {
        va_list arguments;
        va_start(arguments, flags);
        mode = (mode_t)va_arg(arguments, int);
        va_end(arguments);
    }
    return open_impl("open", path, flags, mode, has_mode);
}

int open64(const char *path, int flags, ...) {
    mode_t mode = 0;
    bool has_mode = (flags & (O_CREAT | O_TMPFILE)) != 0;
    if (has_mode) {
        va_list arguments;
        va_start(arguments, flags);
        mode = (mode_t)va_arg(arguments, int);
        va_end(arguments);
    }
    return open_impl("open64", path, flags, mode, has_mode);
}

static int openat_impl(const char *symbol, int directory, const char *path, int flags,
        mode_t mode, bool has_mode) {
    typedef int (*function_type)(int, const char *, int, ...);
    function_type real = RESOLVE(function_type, symbol);
    bool translated;
    char buffer[PATH_MAX];
    const char *target = translate_path(path, buffer, &translated);
    if (target == NULL) return -1;
    REQUIRE_REAL(real);
    if (translated && !fake_chroot_active && write_flags(flags)) {
        errno = EROFS;
        return -1;
    }
    return has_mode ? real(directory, target, flags, mode) : real(directory, target, flags);
}

int openat(int directory, const char *path, int flags, ...) {
    mode_t mode = 0;
    bool has_mode = (flags & (O_CREAT | O_TMPFILE)) != 0;
    if (has_mode) {
        va_list arguments;
        va_start(arguments, flags);
        mode = (mode_t)va_arg(arguments, int);
        va_end(arguments);
    }
    return openat_impl("openat", directory, path, flags, mode, has_mode);
}

int openat64(int directory, const char *path, int flags, ...) {
    mode_t mode = 0;
    bool has_mode = (flags & (O_CREAT | O_TMPFILE)) != 0;
    if (has_mode) {
        va_list arguments;
        va_start(arguments, flags);
        mode = (mode_t)va_arg(arguments, int);
        va_end(arguments);
    }
    return openat_impl("openat64", directory, path, flags, mode, has_mode);
}

int __open_2(const char *path, int flags) { return open(path, flags); }
int __open64_2(const char *path, int flags) { return open64(path, flags); }

static FILE *fopen_impl(const char *symbol, const char *path, const char *mode) {
    typedef FILE *(*function_type)(const char *, const char *);
    function_type real = RESOLVE(function_type, symbol);
    bool translated;
    char buffer[PATH_MAX];
    const char *target = translate_path(path, buffer, &translated);
    if (target == NULL) return NULL;
    if (real == NULL) {
        errno = ENOSYS;
        return NULL;
    }
    if (translated && !fake_chroot_active && write_mode(mode)) {
        errno = EROFS;
        return NULL;
    }
    return real(target, mode);
}

FILE *fopen(const char *path, const char *mode) { return fopen_impl("fopen", path, mode); }
FILE *fopen64(const char *path, const char *mode) { return fopen_impl("fopen64", path, mode); }

DIR *opendir(const char *path) {
    typedef DIR *(*function_type)(const char *);
    function_type real = RESOLVE(function_type, "opendir");
    bool translated;
    char buffer[PATH_MAX];
    const char *target = translate_path(path, buffer, &translated);
    if (target == NULL) return NULL;
    if (real == NULL) {
        errno = ENOSYS;
        return NULL;
    }
    return real(target);
}

#define PATH_CALL(name, value_type) \
    int name(const char *path, value_type *value) { \
        typedef int (*function_type)(const char *, value_type *); \
        function_type real = RESOLVE(function_type, #name); \
        bool translated; \
        char buffer[PATH_MAX]; \
        const char *target = translate_path(path, buffer, &translated); \
        if (target == NULL) return -1; \
        REQUIRE_REAL(real); \
        return real(target, value); \
    }

PATH_CALL(stat, struct stat)
PATH_CALL(stat64, struct stat64)
PATH_CALL(lstat, struct stat)
PATH_CALL(lstat64, struct stat64)

#define XSTAT_CALL(name, value_type) \
    int name(int version, const char *path, value_type *value) { \
        typedef int (*function_type)(int, const char *, value_type *); \
        function_type real = RESOLVE(function_type, #name); \
        bool translated; \
        char buffer[PATH_MAX]; \
        const char *target = translate_path(path, buffer, &translated); \
        if (target == NULL) return -1; \
        REQUIRE_REAL(real); \
        return real(version, target, value); \
    }

XSTAT_CALL(__xstat, struct stat)
XSTAT_CALL(__xstat64, struct stat64)
XSTAT_CALL(__lxstat, struct stat)
XSTAT_CALL(__lxstat64, struct stat64)

int access(const char *path, int mode) {
    typedef int (*function_type)(const char *, int);
    function_type real = RESOLVE(function_type, "access");
    char command[PATH_MAX];
    if ((mode & X_OK) != 0 && (mode & W_OK) == 0
            && resolve_runtime_executable(path, command)) {
        return 0;
    }
    bool translated;
    char buffer[PATH_MAX];
    const char *target = translate_path(path, buffer, &translated);
    if (target == NULL) return -1;
    REQUIRE_REAL(real);
    if (translated && !fake_chroot_active && (mode & W_OK) != 0) {
        errno = EROFS;
        return -1;
    }
    return real(target, mode);
}

int eaccess(const char *path, int mode) {
    typedef int (*function_type)(const char *, int);
    function_type real = RESOLVE(function_type, "eaccess");
    char command[PATH_MAX];
    if ((mode & X_OK) != 0 && (mode & W_OK) == 0
            && resolve_runtime_executable(path, command)) {
        return 0;
    }
    bool translated;
    char buffer[PATH_MAX];
    const char *target = translate_path(path, buffer, &translated);
    if (target == NULL) return -1;
    REQUIRE_REAL(real);
    if (translated && !fake_chroot_active && (mode & W_OK) != 0) {
        errno = EROFS;
        return -1;
    }
    return real(target, mode);
}

int faccessat(int directory, const char *path, int mode, int flags) {
    typedef int (*function_type)(int, const char *, int, int);
    function_type real = RESOLVE(function_type, "faccessat");
    char command[PATH_MAX];
    if ((mode & X_OK) != 0 && (mode & W_OK) == 0
            && resolve_runtime_executable(path, command)) {
        return 0;
    }
    bool translated;
    char buffer[PATH_MAX];
    const char *target = translate_path(path, buffer, &translated);
    if (target == NULL) return -1;
    REQUIRE_REAL(real);
    if (translated && !fake_chroot_active && (mode & W_OK) != 0) {
        errno = EROFS;
        return -1;
    }
    return real(directory, target, mode, flags);
}

int fstatat(int directory, const char *path, struct stat *value, int flags) {
    typedef int (*function_type)(int, const char *, struct stat *, int);
    function_type real = RESOLVE(function_type, "fstatat");
    bool translated;
    char buffer[PATH_MAX];
    const char *target = translate_path(path, buffer, &translated);
    if (target == NULL) return -1;
    REQUIRE_REAL(real);
    return real(directory, target, value, flags);
}

int statx(int directory, const char *path, int flags, unsigned int mask,
        struct statx *value) {
    typedef int (*function_type)(int, const char *, int, unsigned int, struct statx *);
    function_type real = RESOLVE(function_type, "statx");
    bool translated;
    char buffer[PATH_MAX];
    const char *target = translate_path(path, buffer, &translated);
    if (target == NULL) return -1;
    REQUIRE_REAL(real);
    return real(directory, target, flags, mask, value);
}

ssize_t readlink(const char *path, char *buffer, size_t size) {
    typedef ssize_t (*function_type)(const char *, char *, size_t);
    if (strcmp(path, "/proc/self/exe") == 0
            && trusted_program_path[0] != '\0') {
        size_t length = strlen(trusted_program_path);
        if (length > size) length = size;
        if (length > 0) memcpy(buffer, trusted_program_path, length);
        return (ssize_t)length;
    }
    function_type real = RESOLVE(function_type, "readlink");
    bool translated;
    char translated_path[PATH_MAX];
    const char *target = translate_path(path, translated_path, &translated);
    if (target == NULL) return -1;
    if (real == NULL) {
        errno = ENOSYS;
        return -1;
    }
    return real(target, buffer, size);
}

ssize_t readlinkat(int directory, const char *path, char *buffer, size_t size) {
    typedef ssize_t (*function_type)(int, const char *, char *, size_t);
    if (strcmp(path, "/proc/self/exe") == 0
            && trusted_program_path[0] != '\0') {
        size_t length = strlen(trusted_program_path);
        if (length > size) length = size;
        if (length > 0) memcpy(buffer, trusted_program_path, length);
        return (ssize_t)length;
    }
    function_type real = RESOLVE(function_type, "readlinkat");
    bool translated;
    char translated_path[PATH_MAX];
    const char *target = translate_path(path, translated_path, &translated);
    if (target == NULL) return -1;
    REQUIRE_REAL(real);
    return real(directory, target, buffer, size);
}
int mkdir(const char *path, mode_t mode) {
    typedef int (*function_type)(int, const char *, mode_t);
    function_type real = RESOLVE(function_type, "mkdirat");
    bool translated;
    char buffer[PATH_MAX];
    const char *target = translate_path(path, buffer, &translated);
    if (target == NULL) return -1;
    REQUIRE_REAL(real);
    if (translated && !fake_chroot_active) {
        errno = EROFS;
        return -1;
    }
    return real(AT_FDCWD, target, mode);
}

int rename(const char *old_path, const char *new_path) {
    typedef int (*function_type)(int, const char *, int, const char *);
    function_type real = RESOLVE(function_type, "renameat");
    bool old_translated;
    bool new_translated;
    char old_buffer[PATH_MAX];
    char new_buffer[PATH_MAX];
    const char *old_target = translate_path(old_path, old_buffer, &old_translated);
    const char *new_target = translate_path(new_path, new_buffer, &new_translated);
    if (old_target == NULL || new_target == NULL) return -1;
    REQUIRE_REAL(real);
    if (!fake_chroot_active && (old_translated || new_translated)) {
        errno = EROFS;
        return -1;
    }
    return real(AT_FDCWD, old_target, AT_FDCWD, new_target);
}

int mkdirat(int directory, const char *path, mode_t mode) {
    typedef int (*function_type)(int, const char *, mode_t);
    function_type real = RESOLVE(function_type, "mkdirat");
    bool translated;
    char buffer[PATH_MAX];
    const char *target = translate_path(path, buffer, &translated);
    if (target == NULL) return -1;
    REQUIRE_REAL(real);
    if (translated && !fake_chroot_active) {
        errno = EROFS;
        return -1;
    }
    return real(directory, target, mode);
}

int chmod(const char *path, mode_t mode) {
    typedef int (*function_type)(int, const char *, mode_t, int);
    function_type real = RESOLVE(function_type, "fchmodat");
    bool translated;
    char buffer[PATH_MAX];
    const char *target = translate_path(path, buffer, &translated);
    if (target == NULL) return -1;
    REQUIRE_REAL(real);
    if (translated && !fake_chroot_active) {
        errno = EROFS;
        return -1;
    }
    return real(AT_FDCWD, target, mode, 0);
}

int unlinkat(int directory, const char *path, int flags) {
    typedef int (*function_type)(int, const char *, int);
    function_type real = RESOLVE(function_type, "unlinkat");
    bool translated;
    char buffer[PATH_MAX];
    const char *target = translate_path(path, buffer, &translated);
    if (target == NULL) return -1;
    REQUIRE_REAL(real);
    if (translated && !fake_chroot_active) {
        errno = EROFS;
        return -1;
    }
    return real(directory, target, flags);
}

int unlink(const char *path) {
    return unlinkat(AT_FDCWD, path, 0);
}

int rmdir(const char *path) {
    return unlinkat(AT_FDCWD, path, AT_REMOVEDIR);
}

int remove(const char *path) {
    if (unlinkat(AT_FDCWD, path, 0) == 0) return 0;
    if (errno != EISDIR && errno != EPERM) return -1;
    return unlinkat(AT_FDCWD, path, AT_REMOVEDIR);
}

int renameat(int old_directory, const char *old_path, int new_directory,
        const char *new_path) {
    typedef int (*function_type)(int, const char *, int, const char *);
    function_type real = RESOLVE(function_type, "renameat");
    bool old_translated;
    bool new_translated;
    char old_buffer[PATH_MAX];
    char new_buffer[PATH_MAX];
    const char *old_target = translate_path(old_path, old_buffer, &old_translated);
    const char *new_target = translate_path(new_path, new_buffer, &new_translated);
    if (old_target == NULL || new_target == NULL) return -1;
    REQUIRE_REAL(real);
    if (!fake_chroot_active && (old_translated || new_translated)) {
        errno = EROFS;
        return -1;
    }
    return real(old_directory, old_target, new_directory, new_target);
}

int symlinkat(const char *target, int directory, const char *link_path) {
    typedef int (*function_type)(const char *, int, const char *);
    function_type real = RESOLVE(function_type, "symlinkat");
    bool translated;
    char buffer[PATH_MAX];
    const char *destination = translate_path(link_path, buffer, &translated);
    if (destination == NULL) return -1;
    REQUIRE_REAL(real);
    if (translated && !fake_chroot_active) {
        errno = EROFS;
        return -1;
    }
    return real(target, directory, destination);
}

int symlink(const char *target, const char *link_path) {
    return symlinkat(target, AT_FDCWD, link_path);
}
