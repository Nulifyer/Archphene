#define _GNU_SOURCE

#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/stat.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
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
    if (directory == NULL || directory[0] != '/' || strchr(directory, '\n') != NULL) return false;
    int length = snprintf(output, PATH_MAX, "%s/%s", directory, name);
    if (length <= 0 || length >= PATH_MAX) return false;
    struct stat metadata;
    return lstat(output, &metadata) == 0 && S_ISREG(metadata.st_mode)
            && access(output, R_OK) == 0;
}

static int launch_runtime_command(const char *name, char *const arguments[],
        char *const environment[]) {
    char command[PATH_MAX];
    if (!runtime_command(name, command)) return 1;
    const char *loader = getenv("ARCHPHENE_RUNTIME_LOADER");
    const char *library_path = getenv("ARCHPHENE_RUNTIME_LIB");
    if (loader == NULL || loader[0] != '/' || library_path == NULL
            || library_path[0] != '/' || strchr(loader, '\n') != NULL
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
    loader_arguments[0] = (char *)loader;
    loader_arguments[1] = "--library-path";
    loader_arguments[2] = (char *)library_path;
    loader_arguments[3] = "--argv0";
    loader_arguments[4] = (char *)name;
    loader_arguments[5] = command;
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
    real(loader, loader_arguments, environment == NULL ? environ : environment);
    return -1;
}

static int launch_android_system_command(const char *name, char *const arguments[],
        char *const environment[]) {
    if (strcmp(name, "cat") != 0) return 1;
    const char *path = "/system/bin/cat";
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

int execvp(const char *file, char *const arguments[]) {
    int bridged = launch_runtime_command(file, arguments, environ);
    if (bridged <= 0) return -1;
    bridged = launch_android_system_command(file, arguments, environ);
    if (bridged <= 0) return -1;
    typedef int (*function_type)(const char *, char *const[]);
    function_type real = (function_type)dlsym(RTLD_NEXT, "execvp");
    if (real == NULL) {
        errno = ENOSYS;
        return -1;
    }
    return real(file, arguments);
}

int execvpe(const char *file, char *const arguments[], char *const environment[]) {
    int bridged = launch_runtime_command(file, arguments, environment);
    if (bridged <= 0) return -1;
    bridged = launch_android_system_command(file, arguments, environment);
    if (bridged <= 0) return -1;
    typedef int (*function_type)(const char *, char *const[], char *const[]);
    function_type real = (function_type)dlsym(RTLD_NEXT, "execvpe");
    if (real == NULL) {
        errno = ENOSYS;
        return -1;
    }
    return real(file, arguments, environment);
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
    int bridged = launch_runtime_command(file, arguments, environ);
    if (bridged <= 0) return -1;
    bridged = launch_android_system_command(file, arguments, environ);
    if (bridged <= 0) return -1;
    typedef int (*function_type)(const char *, char *const[]);
    function_type real = (function_type)dlsym(RTLD_NEXT, "execvp");
    if (real == NULL) {
        errno = ENOSYS;
        return -1;
    }
    return real(file, arguments);
}

static const char *translate_path(const char *path, char output[PATH_MAX],
        bool *translated) {
    static const char *const prefixes[] = {"/usr/share", "/usr/lib/locale"};
    *translated = false;
    if (path == NULL) return path;
    bool allowed = false;
    for (size_t index = 0; index < sizeof(prefixes) / sizeof(prefixes[0]); index++) {
        size_t length = strlen(prefixes[index]);
        if (strncmp(path, prefixes[index], length) == 0
                && (path[length] == '\0' || path[length] == '/')) {
            allowed = true;
            break;
        }
    }
    if (!allowed) return path;
    if (has_parent_component(path)) {
        errno = EACCES;
        return NULL;
    }
    const char *root = getenv("ARCHPHENE_RUNTIME_ROOT");
    if (root == NULL || root[0] != '/' || strchr(root, '\n') != NULL) return path;
    size_t root_length = strlen(root);
    size_t path_length = strlen(path);
    if (root_length + path_length + 1 > PATH_MAX) {
        errno = ENAMETOOLONG;
        return NULL;
    }
    memcpy(output, root, root_length);
    memcpy(output + root_length, path, path_length + 1);
    *translated = true;
    return output;
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
    if (translated && write_flags(flags)) {
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
    if (translated && write_flags(flags)) {
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
    if (translated && write_mode(mode)) {
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
    bool translated;
    char buffer[PATH_MAX];
    const char *target = translate_path(path, buffer, &translated);
    if (target == NULL) return -1;
    REQUIRE_REAL(real);
    if (translated && (mode & W_OK) != 0) {
        errno = EROFS;
        return -1;
    }
    return real(target, mode);
}

int faccessat(int directory, const char *path, int mode, int flags) {
    typedef int (*function_type)(int, const char *, int, int);
    function_type real = RESOLVE(function_type, "faccessat");
    bool translated;
    char buffer[PATH_MAX];
    const char *target = translate_path(path, buffer, &translated);
    if (target == NULL) return -1;
    REQUIRE_REAL(real);
    if (translated && (mode & W_OK) != 0) {
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
int mkdir(const char *path, mode_t mode) {
    typedef int (*function_type)(int, const char *, mode_t);
    function_type real = RESOLVE(function_type, "mkdirat");
    bool translated;
    char buffer[PATH_MAX];
    const char *target = translate_path(path, buffer, &translated);
    if (target == NULL) return -1;
    REQUIRE_REAL(real);
    if (translated) {
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
    if (old_translated || new_translated) {
        errno = EROFS;
        return -1;
    }
    return real(AT_FDCWD, old_target, AT_FDCWD, new_target);
}