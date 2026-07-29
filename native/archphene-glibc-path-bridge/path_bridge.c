#define _GNU_SOURCE

#include <dirent.h>
#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/capability.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <linux/stat.h>
#include <link.h>
#include <pty.h>
#include <pwd.h>
#include <signal.h>
#include <spawn.h>
#include <stdatomic.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/fsuid.h>
#include <sys/inotify.h>
#include <sys/ipc.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/msg.h>
#include <sys/prctl.h>
#include <sys/sem.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/sysmacros.h>
#include <time.h>
#include <sys/un.h>
#include <sys/xattr.h>
#include <ucontext.h>
#include <unistd.h>

static bool supervised_process_group;
static _Thread_local bool pending_pty_session;

/*
 * GDBus authenticates to a Unix D-Bus socket with an explicit
 * SCM_CREDENTIALS message. Android's untrusted_app SELinux domain rejects
 * that redundant ancillary credential even though SO_PEERCRED on the
 * connected socket reports the same UID. Retry only that exact EPERM case
 * without ancillary data. Keep this in the generic process bridge so GTK,
 * Qt, and direct D-Bus clients all receive the same compatibility behavior.
 */
ssize_t sendmsg(int socket_fd, const struct msghdr *message, int flags) {
    typedef ssize_t (*function_type)(int, const struct msghdr *, int);
    function_type real = (function_type)dlsym(RTLD_NEXT, "sendmsg");
    if (real == NULL) {
        errno = ENOSYS;
        return -1;
    }
    ssize_t result = real(socket_fd, message, flags);
    if (result >= 0 || errno != EPERM || message == NULL
            || message->msg_control == NULL || message->msg_controllen == 0) {
        return result;
    }
    struct msghdr retry = *message;
    bool found_credentials = false;
    for (struct cmsghdr *header = CMSG_FIRSTHDR(&retry);
            header != NULL; header = CMSG_NXTHDR(&retry, header)) {
        if (header->cmsg_level != SOL_SOCKET
                || header->cmsg_type != SCM_CREDENTIALS
                || found_credentials
                || header->cmsg_len != CMSG_LEN(sizeof(struct ucred))) {
            return result;
        }
        const struct ucred *credentials =
                (const struct ucred *)CMSG_DATA(header);
        if (credentials->pid != getpid()
                || credentials->uid != getuid()
                || credentials->gid != getgid()) {
            return result;
        }
        found_credentials = true;
    }
    if (!found_credentials) return result;
    retry.msg_control = NULL;
    retry.msg_controllen = 0;
    return real(socket_fd, &retry, flags);
}

static bool process_has_terminal(void) {
    typedef int (*isatty_type)(int);
    isatty_type real_isatty = (isatty_type)dlsym(RTLD_NEXT, "isatty");
    return real_isatty != NULL
            && (real_isatty(STDIN_FILENO) != 0
                || real_isatty(STDOUT_FILENO) != 0
                || real_isatty(STDERR_FILENO) != 0);
}

/*
 * Terminal emulators call setsid() before moving their slave PTY onto the
 * standard descriptors. Keep ordinary background children in the supervised
 * GUI process group, but permit that narrow PTY-session transition.
 */
static bool process_has_pty_descriptor(void) {
    typedef ssize_t (*readlink_type)(const char *, char *, size_t);
    readlink_type real_readlink =
            (readlink_type)dlsym(RTLD_NEXT, "readlink");
    if (real_readlink == NULL) return false;
    for (int descriptor = 0; descriptor < 256; descriptor++) {
        char path[64];
        char target[PATH_MAX];
        int written = snprintf(path, sizeof(path),
                "/proc/self/fd/%d", descriptor);
        if (written <= 0 || (size_t)written >= sizeof(path)) return false;
        ssize_t length =
                real_readlink(path, target, sizeof(target) - 1);
        if (length <= 0 || (size_t)length >= sizeof(target)) continue;
        target[length] = '\0';
        if (strncmp(target, "/dev/pts/", 9) == 0
                || strcmp(target, "/dev/ptmx") == 0) {
            return true;
        }
    }
    return false;
}

/*
 * Android permits apps to allocate a PTY through /dev/ptmx, but its SELinux
 * policy does not permit reopening the corresponding /dev/pts/N pathname.
 * TIOCGPTPEER returns the same slave as an already-authorized descriptor and
 * avoids weakening that policy. This is the generic primitive used by
 * desktop terminal libraries such as node-pty.
 */
static int open_pty_peer(int *master_output, int *slave_output, char *name,
        const struct termios *terminal, const struct winsize *window) {
    typedef int (*open_type)(const char *, int, ...);
    typedef char *(*ptsname_type)(int);
    open_type real_open = (open_type)dlsym(RTLD_NEXT, "open");
    ptsname_type real_ptsname = (ptsname_type)dlsym(RTLD_NEXT, "ptsname");
    if (master_output == NULL || slave_output == NULL || real_open == NULL) {
        errno = EINVAL;
        return -1;
    }
    int master = real_open("/dev/ptmx", O_RDWR | O_NOCTTY);
    if (master < 0) return -1;
    int unlock = 0;
    if (ioctl(master, TIOCSPTLCK, &unlock) != 0) {
        int saved_errno = errno;
        close(master);
        errno = saved_errno;
        return -1;
    }
#ifdef TIOCGPTPEER
    int slave = ioctl(master, TIOCGPTPEER, O_RDWR | O_NOCTTY);
#else
    int slave = -1;
    errno = ENOSYS;
#endif
    if (slave < 0) {
        int saved_errno = errno;
        close(master);
        errno = saved_errno;
        return -1;
    }
    if ((terminal != NULL
                && tcsetattr(slave, TCSAFLUSH, terminal) != 0)
            || (window != NULL
                && ioctl(slave, TIOCSWINSZ, window) != 0)) {
        int saved_errno = errno;
        close(slave);
        close(master);
        errno = saved_errno;
        return -1;
    }
    if (name != NULL) {
        char *peer_name = real_ptsname == NULL ? NULL : real_ptsname(master);
        if (peer_name == NULL) {
            int saved_errno = errno == 0 ? EIO : errno;
            close(slave);
            close(master);
            errno = saved_errno;
            return -1;
        }
        strcpy(name, peer_name);
    }
    *master_output = master;
    *slave_output = slave;
    return 0;
}

char *ptsname(int descriptor) {
    typedef char *(*function_type)(int);
    function_type real = (function_type)dlsym(RTLD_NEXT, "ptsname");
    if (real == NULL) {
        errno = ENOSYS;
        return NULL;
    }
    char *name = real(descriptor);
    if (name != NULL && strncmp(name, "/dev/pts/", 9) == 0) {
        pending_pty_session = true;
    }
    return name;
}

int openpty(int *master, int *slave, char *name,
        const struct termios *terminal, const struct winsize *window) {
    return open_pty_peer(master, slave, name, terminal, window);
}

pid_t forkpty(int *master_output, char *name,
        const struct termios *terminal, const struct winsize *window) {
    int master;
    int slave;
    if (open_pty_peer(&master, &slave, name, terminal, window) != 0) {
        return -1;
    }
    pid_t child = fork();
    if (child < 0) {
        int saved_errno = errno;
        close(slave);
        close(master);
        errno = saved_errno;
        return -1;
    }
    if (child == 0) {
        close(master);
        pid_t parent = getppid();
        typedef pid_t (*setsid_type)(void);
        setsid_type real_setsid =
                (setsid_type)dlsym(RTLD_NEXT, "setsid");
        if (prctl(PR_SET_PDEATHSIG, SIGKILL) != 0
                || getppid() != parent
                || real_setsid == NULL || real_setsid() < 0
                || ioctl(slave, TIOCSCTTY, 0) != 0) {
            _exit(125);
        }
        if (dup2(slave, STDIN_FILENO) < 0
                || dup2(slave, STDOUT_FILENO) < 0
                || dup2(slave, STDERR_FILENO) < 0) {
            _exit(125);
        }
        if (slave > STDERR_FILENO) close(slave);
        return 0;
    }
    close(slave);
    *master_output = master;
    return child;
}

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
static char trusted_library_path[PATH_MAX];
static char trusted_command_directory[PATH_MAX];
static char trusted_preload_environment[PATH_MAX + 12];
static char trusted_loader_environment[PATH_MAX + 28];
static char trusted_library_environment[PATH_MAX + 24];
static char trusted_command_environment[PATH_MAX + 32];
static char trusted_root_environment[PATH_MAX + 27];
static char trusted_program_path[PATH_MAX];
static char trusted_root[PATH_MAX];
static char trusted_shm_root[PATH_MAX];
static bool fake_chroot_active;
static bool root_identity_active;
static atomic_uint message_queue_sequence = 1;
static atomic_uint semaphore_sequence = 1;
__attribute__((visibility("hidden")))
void *archphene_real_syscall_function;

#define ARCHPHENE_STRINGIFY_INNER(value) #value
#define ARCHPHENE_STRINGIFY(value) ARCHPHENE_STRINGIFY_INNER(value)

/*
 * Chromium and libuv sometimes call the exported syscall(2) function
 * directly. Preserve the complete platform calling convention for all
 * unhandled syscalls, while routing raw path calls through the same policy as
 * their libc entry points. A C variadic forwarding wrapper would have to read
 * arguments the caller did not supply, so use a minimal tail-call trampoline
 * instead.
 */
__attribute__((used, visibility("hidden")))
long archphene_syscall_openat(
        int directory, const char *path, int flags, mode_t mode) {
    return openat(directory, path, flags, mode);
}

__attribute__((used, visibility("hidden")))
long archphene_syscall_readlinkat(
        int directory, const char *path, char *buffer, size_t size) {
    return readlinkat(directory, path, buffer, size);
}

#ifdef __NR_creat
__attribute__((used, visibility("hidden")))
long archphene_syscall_creat(const char *path, mode_t mode) {
    return open(path, O_WRONLY | O_CREAT | O_TRUNC, mode);
}
#endif

#ifdef __NR_readlink
__attribute__((used, visibility("hidden")))
long archphene_syscall_readlink(
        const char *path, char *buffer, size_t size) {
    return readlink(path, buffer, size);
}
#endif

#ifdef __NR_capset
__attribute__((used, visibility("hidden")))
long archphene_syscall_capset(const void *header, const void *data) {
    if (fake_chroot_active && root_identity_active) return 0;
    typedef long (*syscall_type)(long, ...);
    syscall_type real = (syscall_type)archphene_real_syscall_function;
    if (real == NULL) {
        errno = ENOSYS;
        return -1;
    }
    return real(__NR_capset, header, data);
}
#endif

struct archphene_linux_dirent64 {
    uint64_t inode;
    int64_t offset;
    unsigned short record_length;
    unsigned char type;
    char name[];
};

static bool numeric_process_name(const char *name) {
    if (name == NULL || name[0] == '\0') return false;
    for (const unsigned char *cursor = (const unsigned char *)name;
            *cursor != '\0'; cursor++) {
        if (*cursor < (unsigned char)'0' || *cursor > (unsigned char)'9') {
            return false;
        }
    }
    return true;
}

static bool proc_root_descriptor(int descriptor) {
    typedef ssize_t (*readlink_type)(const char *, char *, size_t);
    readlink_type real_readlink = (readlink_type)dlsym(RTLD_NEXT, "readlink");
    if (descriptor < 0 || real_readlink == NULL) return false;
    char link[64];
    int written = snprintf(link, sizeof(link), "/proc/self/fd/%d", descriptor);
    if (written <= 0 || (size_t)written >= sizeof(link)) return false;
    char target[PATH_MAX];
    ssize_t length = real_readlink(link, target, sizeof(target) - 1);
    if (length <= 0 || (size_t)length >= sizeof(target)) return false;
    target[length] = '\0';
    return strcmp(target, "/proc") == 0;
}

static bool proc_root_path(const char *path) {
    if (path == NULL || strncmp(path, "/proc", sizeof("/proc") - 1) != 0) {
        return false;
    }
    path += sizeof("/proc") - 1;
    while (*path == '/') path++;
    return *path == '\0';
}

static bool visible_proc_entry(int proc_descriptor, const char *name) {
    if (!numeric_process_name(name)) return true;
    typedef int (*openat_type)(int, const char *, int, ...);
    openat_type real_openat = (openat_type)dlsym(RTLD_NEXT, "openat");
    if (real_openat == NULL) return false;
    char status[NAME_MAX + sizeof("/status")];
    int written = snprintf(status, sizeof(status), "%s/status", name);
    if (written <= 0 || (size_t)written >= sizeof(status)) return false;
    int descriptor = real_openat(proc_descriptor, status,
            O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (descriptor < 0) return false;
    close(descriptor);
    return true;
}

#ifdef __NR_getdents64
__attribute__((used, visibility("hidden")))
long archphene_syscall_getdents64(
        int directory, void *buffer, size_t size) {
    typedef long (*syscall_type)(long, ...);
    syscall_type real = (syscall_type)archphene_real_syscall_function;
    if (real == NULL) {
        errno = ENOSYS;
        return -1;
    }
    long result = real(__NR_getdents64, directory, buffer, size);
    if (result <= 0 || !proc_root_descriptor(directory)) return result;
    size_t input = 0;
    size_t output = 0;
    while (input < (size_t)result) {
        struct archphene_linux_dirent64 *entry =
                (struct archphene_linux_dirent64 *)((char *)buffer + input);
        size_t minimum = offsetof(struct archphene_linux_dirent64, name) + 1;
        if (entry->record_length < minimum
                || entry->record_length > (size_t)result - input) {
            errno = EIO;
            return -1;
        }
        if (visible_proc_entry(directory, entry->name)) {
            if (output != input) {
                memmove((char *)buffer + output, entry, entry->record_length);
            }
            output += entry->record_length;
        }
        input += entry->record_length;
    }
    return (long)output;
}
#endif

#if defined(__aarch64__)
__asm__(
    ".text\n"
    ".global syscall\n"
    ".type syscall, %function\n"
    "syscall:\n"
    "cmp x0, #" ARCHPHENE_STRINGIFY(__NR_openat) "\n"
    "b.eq 1f\n"
    "cmp x0, #" ARCHPHENE_STRINGIFY(__NR_readlinkat) "\n"
    "b.eq 3f\n"
#ifdef __NR_getdents64
    "cmp x0, #" ARCHPHENE_STRINGIFY(__NR_getdents64) "\n"
    "b.eq 4f\n"
#endif
#ifdef __NR_capset
    "cmp x0, #" ARCHPHENE_STRINGIFY(__NR_capset) "\n"
    "b.eq 5f\n"
#endif
    "adrp x16, archphene_real_syscall_function\n"
    "ldr x16, [x16, #:lo12:archphene_real_syscall_function]\n"
    "cbz x16, 2f\n"
    "br x16\n"
    "1:\n"
    "mov x0, x1\n"
    "mov x1, x2\n"
    "mov x2, x3\n"
    "mov x3, x4\n"
    "b archphene_syscall_openat\n"
    "3:\n"
    "mov x0, x1\n"
    "mov x1, x2\n"
    "mov x2, x3\n"
    "mov x3, x4\n"
    "b archphene_syscall_readlinkat\n"
#ifdef __NR_getdents64
    "4:\n"
    "mov x0, x1\n"
    "mov x1, x2\n"
    "mov x2, x3\n"
    "b archphene_syscall_getdents64\n"
#endif
#ifdef __NR_capset
    "5:\n"
    "mov x0, x1\n"
    "mov x1, x2\n"
    "b archphene_syscall_capset\n"
#endif
    "2:\n"
    "mov x0, #-" ARCHPHENE_STRINGIFY(ENOSYS) "\n"
    "ret\n"
    ".size syscall, .-syscall\n");
#elif defined(__x86_64__)
__asm__(
    ".text\n"
    ".global syscall\n"
    ".type syscall, @function\n"
    "syscall:\n"
    "cmpq $" ARCHPHENE_STRINGIFY(__NR_openat) ", %rdi\n"
    "je 1f\n"
    "cmpq $" ARCHPHENE_STRINGIFY(__NR_readlinkat) ", %rdi\n"
    "je 3f\n"
    "cmpq $" ARCHPHENE_STRINGIFY(__NR_readlink) ", %rdi\n"
    "je 4f\n"
    "cmpq $" ARCHPHENE_STRINGIFY(__NR_creat) ", %rdi\n"
    "je 5f\n"
#ifdef __NR_getdents64
    "cmpq $" ARCHPHENE_STRINGIFY(__NR_getdents64) ", %rdi\n"
    "je 6f\n"
#endif
#ifdef __NR_capset
    "cmpq $" ARCHPHENE_STRINGIFY(__NR_capset) ", %rdi\n"
    "je 7f\n"
#endif
    "movq archphene_real_syscall_function(%rip), %rax\n"
    "testq %rax, %rax\n"
    "je 2f\n"
    "jmp *%rax\n"
    "1:\n"
    "movq %rsi, %rdi\n"
    "movq %rdx, %rsi\n"
    "movq %rcx, %rdx\n"
    "movq %r8, %rcx\n"
    "jmp archphene_syscall_openat\n"
    "3:\n"
    "movq %rsi, %rdi\n"
    "movq %rdx, %rsi\n"
    "movq %rcx, %rdx\n"
    "movq %r8, %rcx\n"
    "jmp archphene_syscall_readlinkat\n"
    "4:\n"
    "movq %rsi, %rdi\n"
    "movq %rdx, %rsi\n"
    "movq %rcx, %rdx\n"
    "jmp archphene_syscall_readlink\n"
    "5:\n"
    "movq %rsi, %rdi\n"
    "movq %rdx, %rsi\n"
    "jmp archphene_syscall_creat\n"
#ifdef __NR_getdents64
    "6:\n"
    "movq %rsi, %rdi\n"
    "movq %rdx, %rsi\n"
    "movq %rcx, %rdx\n"
    "jmp archphene_syscall_getdents64\n"
#endif
#ifdef __NR_capset
    "7:\n"
    "movq %rsi, %rdi\n"
    "movq %rdx, %rsi\n"
    "jmp archphene_syscall_capset\n"
#endif
    "2:\n"
    "movq $-" ARCHPHENE_STRINGIFY(ENOSYS) ", %rax\n"
    "ret\n"
    ".size syscall, .-syscall\n");
#endif

__attribute__((constructor(101)))
static void capture_real_syscall(void) {
    archphene_real_syscall_function = dlsym(RTLD_NEXT, "syscall");
}

__attribute__((constructor(102)))
static void capture_trusted_runtime_environment(void) {
    Dl_info information;
    if (dladdr((void *)&capture_trusted_runtime_environment, &information) != 0
            && information.dli_fname != NULL) {
        char resolved[PATH_MAX];
        struct stat metadata;
        if (realpath(information.dli_fname, resolved) != NULL
                && stat(resolved, &metadata) == 0
                && S_ISREG(metadata.st_mode)
                && (metadata.st_mode & (S_IWGRP | S_IWOTH)) == 0) {
            int written = snprintf(trusted_preload_environment,
                    sizeof(trusted_preload_environment), "LD_PRELOAD=%s", resolved);
            if (written <= 0
                    || (size_t)written >= sizeof(trusted_preload_environment)) {
                trusted_preload_environment[0] = '\0';
            }
        }
    }

    const char *library_path = getenv("ARCHPHENE_RUNTIME_LIB");
    if (library_path != NULL && library_path[0] == '/'
            && strchr(library_path, '\n') == NULL
            && strlen(library_path) < sizeof(trusted_library_path)) {
        memcpy(trusted_library_path, library_path, strlen(library_path) + 1);
        int written = snprintf(trusted_library_environment,
                sizeof(trusted_library_environment),
                "ARCHPHENE_RUNTIME_LIB=%s", trusted_library_path);
        if (written <= 0
                || (size_t)written >= sizeof(trusted_library_environment)) {
            trusted_library_path[0] = '\0';
            trusted_library_environment[0] = '\0';
        }
    }

    const char *command_directory = getenv("ARCHPHENE_RUNTIME_COMMAND_DIR");
    if (command_directory != NULL && command_directory[0] == '/'
            && !has_parent_component(command_directory)
            && strchr(command_directory, '\n') == NULL
            && strlen(command_directory) < sizeof(trusted_command_directory)) {
        memcpy(trusted_command_directory, command_directory,
                strlen(command_directory) + 1);
        int written = snprintf(trusted_command_environment,
                sizeof(trusted_command_environment),
                "ARCHPHENE_RUNTIME_COMMAND_DIR=%s", trusted_command_directory);
        if (written <= 0
                || (size_t)written >= sizeof(trusted_command_environment)) {
            trusted_command_directory[0] = '\0';
            trusted_command_environment[0] = '\0';
        }
    }
}

static const char *translate_path(const char *path, char output[PATH_MAX],
        bool *translated);
static const char *translate_follow_path(const char *path,
        char output[PATH_MAX], bool *translated);
static const char *translate_at_path(int directory, const char *path,
        char output[PATH_MAX], bool *translated, bool follow);

static bool inside_shared_memory_path(const char *path) {
    if (path == NULL) return false;
    static const char prefix[] = "/dev/shm";
    return strncmp(path, prefix, sizeof(prefix) - 1) == 0
            && (path[sizeof(prefix) - 1] == '\0'
                || path[sizeof(prefix) - 1] == '/');
}

static const char *trusted_linux_program_path(void) {
    if (trusted_program_path[0] == '\0') return NULL;
    size_t root_length = strlen(trusted_root);
    if (root_length == 0
            || strncmp(trusted_program_path, trusted_root, root_length) != 0
            || (trusted_program_path[root_length] != '\0'
                && trusted_program_path[root_length] != '/')) {
        return trusted_program_path;
    }
    const char *logical = trusted_program_path + root_length;
    return logical[0] == '\0' ? "/" : logical;
}

static bool reject_optional_sandbox_syscall(siginfo_t *information, void *context) {
    if (information == NULL || context == NULL) return false;
    bool optional = false;
#ifdef __NR_landlock_create_ruleset
    optional = information->si_syscall == __NR_landlock_create_ruleset
            || information->si_syscall == __NR_landlock_add_rule
            || information->si_syscall == __NR_landlock_restrict_self;
#endif
#ifdef __NR_io_uring_setup
    optional = optional || information->si_syscall == __NR_io_uring_setup
            || information->si_syscall == __NR_io_uring_enter
            || information->si_syscall == __NR_io_uring_register;
#endif
#ifdef __NR_statx
    /*
     * libuv invokes statx through syscall(2), bypassing the preloadable statx
     * wrapper and therefore fake-root path translation. Report it unavailable
     * so callers use their fstatat/stat fallback, which is translated.
     */
    optional = optional || information->si_syscall == __NR_statx;
#endif
#ifdef __NR_get_mempolicy
    /*
     * Managed runtimes probe NUMA topology before deciding whether to enable
     * NUMA-aware allocation. Android app seccomp blocks get_mempolicy even
     * though the runtime can operate normally without NUMA support.
     */
    optional = optional || information->si_syscall == __NR_get_mempolicy;
#endif
#ifdef __NR_pkey_alloc
    /*
     * Chromium probes protection keys before selecting its allocator path.
     * Android app seccomp does not expose this optional CPU facility.
     */
    optional = optional || information->si_syscall == __NR_pkey_alloc
            || information->si_syscall == __NR_pkey_free
            || information->si_syscall == __NR_pkey_mprotect;
#endif
    if (!optional) return false;
#if defined(__aarch64__)
    ((ucontext_t *)context)->uc_mcontext.regs[0] = (unsigned long)-ENOSYS;
    return true;
#elif defined(__x86_64__)
    ((ucontext_t *)context)->uc_mcontext.gregs[REG_RAX] = -ENOSYS;
    return true;
#else
    return false;
#endif
}

static void report_blocked_syscall(int signal_number, siginfo_t *information,
        void *context) {
    (void)signal_number;
    if (reject_optional_sandbox_syscall(information, context)) return;
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
    action.sa_flags = SA_SIGINFO;
    sigemptyset(&action.sa_mask);
    (void)sigaction(SIGSYS, &action, NULL);
}

static bool install_runtime_compatibility_filter(void) {
#if defined(__NR_statx)
    struct sock_filter compatibility_filter[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS,
                (unsigned int)offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_statx, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog program;
    program.len = (unsigned short)(sizeof(compatibility_filter)
            / sizeof(compatibility_filter[0]));
    program.filter = compatibility_filter;
    return prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) == 0
            && prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program) == 0;
#else
    return false;
#endif
}

/*
 * Android app seccomp rejects the SysV message-queue syscalls used by
 * fakeroot's faked daemon. Keep equivalent bounded queues as private regular
 * files under the already-private Linux runtime root. File locking preserves
 * the message ordering/type semantics across faked and its client processes;
 * no Android sandbox boundary is weakened.
 */
#define ARCHPHENE_MESSAGE_HEADER_BYTES 16U
#define ARCHPHENE_MESSAGE_MAX_BYTES (128U * 1024U)
#define ARCHPHENE_QUEUE_MAX_BYTES (4U * 1024U * 1024U)

static bool message_queue_path(int queue, char output[PATH_MAX]) {
    if (trusted_root[0] == '\0' || queue <= 0) {
        errno = EINVAL;
        return false;
    }
    int length = snprintf(output, PATH_MAX, "%s/run/.archphene-msg-%d",
            trusted_root, queue);
    if (length <= 0 || length >= PATH_MAX) {
        errno = ENAMETOOLONG;
        return false;
    }
    return true;
}

static void message_store_u64(unsigned char *output, uint64_t value) {
    for (size_t index = 0; index < 8; index++) {
        output[index] = (unsigned char)(value >> (index * 8));
    }
}

static uint64_t message_load_u64(const unsigned char *input) {
    uint64_t value = 0;
    for (size_t index = 0; index < 8; index++) {
        value |= (uint64_t)input[index] << (index * 8);
    }
    return value;
}

int msgget(key_t key, int flags) {
    typedef int (*open_type)(const char *, int, ...);
    open_type real_open = (open_type)dlsym(RTLD_NEXT, "open");
    if (real_open == NULL || trusted_root[0] == '\0') {
        errno = ENOSYS;
        return -1;
    }
    int queue;
    if (key == IPC_PRIVATE) {
        unsigned int sequence =
                atomic_fetch_add_explicit(&message_queue_sequence, 1,
                        memory_order_relaxed);
        unsigned int candidate =
                (((unsigned int)getpid() & 0x001fffffU) << 9)
                ^ (sequence & 0x1ffU);
        queue = (int)(candidate == 0 ? 1 : candidate);
    } else {
        uint32_t candidate = (uint32_t)key & 0x3fffffffU;
        queue = (int)(candidate == 0 ? 1 : candidate);
    }
    char path[PATH_MAX];
    if (!message_queue_path(queue, path)) return -1;
    int open_flags = O_RDWR | O_CLOEXEC;
    if (key == IPC_PRIVATE || (flags & IPC_CREAT) != 0) open_flags |= O_CREAT;
    if (key == IPC_PRIVATE || (flags & IPC_EXCL) != 0) open_flags |= O_EXCL;
    int descriptor = real_open(path, open_flags, 0600);
    if (descriptor < 0) return -1;
    close(descriptor);
    return queue;
}

int msgsnd(int queue, const void *message, size_t size, int flags) {
    typedef int (*open_type)(const char *, int, ...);
    open_type real_open = (open_type)dlsym(RTLD_NEXT, "open");
    if (real_open == NULL || message == NULL || size > ARCHPHENE_MESSAGE_MAX_BYTES) {
        errno = message == NULL ? EFAULT : EINVAL;
        return -1;
    }
    long type;
    memcpy(&type, message, sizeof(type));
    if (type <= 0) {
        errno = EINVAL;
        return -1;
    }
    char path[PATH_MAX];
    if (!message_queue_path(queue, path)) return -1;
    for (;;) {
        int descriptor = real_open(path, O_RDWR | O_CLOEXEC);
        if (descriptor < 0) return -1;
        if (flock(descriptor, LOCK_EX) != 0) {
            int saved_errno = errno;
            close(descriptor);
            errno = saved_errno;
            return -1;
        }
        struct stat metadata;
        int result = fstat(descriptor, &metadata);
        uint64_t record_size = ARCHPHENE_MESSAGE_HEADER_BYTES + size;
        if (result == 0 && metadata.st_size >= 0
                && (uint64_t)metadata.st_size + record_size
                        <= ARCHPHENE_QUEUE_MAX_BYTES) {
            unsigned char header[ARCHPHENE_MESSAGE_HEADER_BYTES];
            message_store_u64(header, (uint64_t)type);
            message_store_u64(header + 8, size);
            off_t offset = metadata.st_size;
            ssize_t written = pwrite(descriptor, header, sizeof(header), offset);
            if (written == (ssize_t)sizeof(header)) {
                written = pwrite(descriptor,
                        (const unsigned char *)message + sizeof(long), size,
                        offset + (off_t)sizeof(header));
            }
            result = written == (ssize_t)size ? 0 : -1;
            if (result != 0 && errno == 0) errno = EIO;
        } else if (result == 0) {
            errno = EAGAIN;
            result = -1;
        }
        int saved_errno = errno;
        (void)flock(descriptor, LOCK_UN);
        close(descriptor);
        errno = saved_errno;
        if (result == 0 || (flags & IPC_NOWAIT) != 0 || errno != EAGAIN) {
            return result;
        }
        usleep(10000);
    }
}

static bool message_type_matches(long available, long requested,
        long *selected_type) {
    if (requested == 0) return true;
    if (requested > 0) return available == requested;
    long maximum = requested == LONG_MIN ? LONG_MAX : -requested;
    if (available > maximum) return false;
    if (*selected_type == 0 || available < *selected_type) {
        *selected_type = available;
        return true;
    }
    return false;
}

ssize_t msgrcv(int queue, void *message, size_t size, long requested_type,
        int flags) {
    typedef int (*open_type)(const char *, int, ...);
    open_type real_open = (open_type)dlsym(RTLD_NEXT, "open");
    if (real_open == NULL || message == NULL || size > ARCHPHENE_MESSAGE_MAX_BYTES) {
        errno = message == NULL ? EFAULT : EINVAL;
        return -1;
    }
    char path[PATH_MAX];
    if (!message_queue_path(queue, path)) return -1;
    for (;;) {
        int descriptor = real_open(path, O_RDWR | O_CLOEXEC);
        if (descriptor < 0) return -1;
        if (flock(descriptor, LOCK_EX) != 0) {
            int saved_errno = errno;
            close(descriptor);
            errno = saved_errno;
            return -1;
        }
        struct stat metadata;
        if (fstat(descriptor, &metadata) != 0 || metadata.st_size < 0
                || (uint64_t)metadata.st_size > ARCHPHENE_QUEUE_MAX_BYTES) {
            int saved_errno = errno == 0 ? EIO : errno;
            (void)flock(descriptor, LOCK_UN);
            close(descriptor);
            errno = saved_errno;
            return -1;
        }
        size_t file_size = (size_t)metadata.st_size;
        unsigned char *bytes = NULL;
        if (file_size != 0) {
            bytes = mmap(NULL, file_size, PROT_READ | PROT_WRITE,
                    MAP_SHARED, descriptor, 0);
            if (bytes == MAP_FAILED) bytes = NULL;
        }
        size_t selected_offset = SIZE_MAX;
        size_t selected_size = 0;
        long selected_type = 0;
        size_t offset = 0;
        bool valid = bytes != NULL || file_size == 0;
        while (valid && offset < file_size) {
            if (file_size - offset < ARCHPHENE_MESSAGE_HEADER_BYTES) {
                valid = false;
                break;
            }
            uint64_t raw_type = message_load_u64(bytes + offset);
            uint64_t raw_size = message_load_u64(bytes + offset + 8);
            if (raw_type == 0 || raw_type > LONG_MAX
                    || raw_size > ARCHPHENE_MESSAGE_MAX_BYTES
                    || raw_size > file_size - offset
                            - ARCHPHENE_MESSAGE_HEADER_BYTES) {
                valid = false;
                break;
            }
            long type = (long)raw_type;
            bool matches = message_type_matches(
                    type, requested_type, &selected_type);
            if (matches && (requested_type >= 0 || type == selected_type)) {
                selected_offset = offset;
                selected_size = (size_t)raw_size;
                if (requested_type >= 0) break;
            }
            offset += ARCHPHENE_MESSAGE_HEADER_BYTES + (size_t)raw_size;
        }
        ssize_t result = -1;
        if (!valid) {
            errno = EIO;
        } else if (selected_offset != SIZE_MAX) {
            if (selected_size > size && (flags & MSG_NOERROR) == 0) {
                errno = E2BIG;
            } else {
                size_t copied = selected_size > size ? size : selected_size;
                long type = (long)message_load_u64(bytes + selected_offset);
                memcpy(message, &type, sizeof(type));
                memcpy((unsigned char *)message + sizeof(long),
                        bytes + selected_offset + ARCHPHENE_MESSAGE_HEADER_BYTES,
                        copied);
                size_t record_size =
                        ARCHPHENE_MESSAGE_HEADER_BYTES + selected_size;
                memmove(bytes + selected_offset,
                        bytes + selected_offset + record_size,
                        file_size - selected_offset - record_size);
                if (msync(bytes, file_size, MS_SYNC) == 0
                        && ftruncate(descriptor,
                                (off_t)(file_size - record_size)) == 0) {
                    result = (ssize_t)copied;
                }
            }
        } else {
            errno = ENOMSG;
        }
        int saved_errno = errno;
        if (bytes != NULL) munmap(bytes, file_size);
        (void)flock(descriptor, LOCK_UN);
        close(descriptor);
        errno = saved_errno;
        if (result >= 0 || (flags & IPC_NOWAIT) != 0 || errno != ENOMSG) {
            return result;
        }
        usleep(10000);
    }
}

int msgctl(int queue, int command, struct msqid_ds *status) {
    typedef int (*open_type)(const char *, int, ...);
    typedef int (*unlink_type)(const char *);
    open_type real_open = (open_type)dlsym(RTLD_NEXT, "open");
    unlink_type real_unlink = (unlink_type)dlsym(RTLD_NEXT, "unlink");
    char path[PATH_MAX];
    if (!message_queue_path(queue, path)) return -1;
    if (command == IPC_RMID) {
        if (real_unlink == NULL) {
            errno = ENOSYS;
            return -1;
        }
        return real_unlink(path);
    }
    if (command != IPC_STAT || status == NULL || real_open == NULL) {
        errno = command == IPC_STAT ? EFAULT : EINVAL;
        return -1;
    }
    int descriptor = real_open(path, O_RDONLY | O_CLOEXEC);
    if (descriptor < 0) return -1;
    struct stat metadata;
    int result = fstat(descriptor, &metadata);
    int saved_errno = errno;
    close(descriptor);
    if (result != 0) {
        errno = saved_errno;
        return -1;
    }
    memset(status, 0, sizeof(*status));
    status->msg_perm.mode = 0600;
    status->msg_qbytes = ARCHPHENE_QUEUE_MAX_BYTES;
    status->msg_ctime = metadata.st_ctime;
    return 0;
}

#define ARCHPHENE_SEMAPHORE_MAX_COUNT 64U
#define ARCHPHENE_SEMAPHORE_MAX_VALUE 32767

static bool semaphore_path(int semaphore, char output[PATH_MAX]) {
    if (trusted_root[0] == '\0' || semaphore <= 0) {
        errno = EINVAL;
        return false;
    }
    int length = snprintf(output, PATH_MAX, "%s/run/.archphene-sem-%d",
            trusted_root, semaphore);
    if (length <= 0 || length >= PATH_MAX) {
        errno = ENAMETOOLONG;
        return false;
    }
    return true;
}

int semget(key_t key, int count, int flags) {
    typedef int (*open_type)(const char *, int, ...);
    open_type real_open = (open_type)dlsym(RTLD_NEXT, "open");
    if (real_open == NULL || count < 0
            || (unsigned int)count > ARCHPHENE_SEMAPHORE_MAX_COUNT) {
        errno = count < 0 ? EINVAL : ENOSYS;
        return -1;
    }
    int semaphore;
    if (key == IPC_PRIVATE) {
        unsigned int sequence =
                atomic_fetch_add_explicit(&semaphore_sequence, 1,
                        memory_order_relaxed);
        unsigned int candidate =
                (((unsigned int)getpid() & 0x001fffffU) << 9)
                ^ (sequence & 0x1ffU);
        semaphore = (int)(candidate == 0 ? 1 : candidate);
    } else {
        uint32_t candidate = (uint32_t)key & 0x3fffffffU;
        semaphore = (int)(candidate == 0 ? 1 : candidate);
    }
    char path[PATH_MAX];
    if (!semaphore_path(semaphore, path)) return -1;
    int open_flags = O_RDWR | O_CLOEXEC;
    bool create = key == IPC_PRIVATE || (flags & IPC_CREAT) != 0;
    if (create) open_flags |= O_CREAT;
    if (key == IPC_PRIVATE || (flags & IPC_EXCL) != 0) open_flags |= O_EXCL;
    int descriptor = real_open(path, open_flags, 0600);
    if (descriptor < 0) return -1;
    if (flock(descriptor, LOCK_EX) != 0) {
        int saved_errno = errno;
        close(descriptor);
        errno = saved_errno;
        return -1;
    }
    struct stat metadata;
    int result = fstat(descriptor, &metadata);
    uint64_t existing_count = 0;
    if (result == 0 && metadata.st_size == 0 && create) {
        if (count == 0) {
            errno = EINVAL;
            result = -1;
        } else {
            unsigned char bytes[8 + ARCHPHENE_SEMAPHORE_MAX_COUNT * 8];
            memset(bytes, 0, sizeof(bytes));
            message_store_u64(bytes, (uint64_t)count);
            size_t length = 8 + (size_t)count * 8;
            result = pwrite(descriptor, bytes, length, 0) == (ssize_t)length
                    ? 0 : -1;
        }
    } else if (result == 0 && metadata.st_size >= 8) {
        unsigned char header[8];
        if (pread(descriptor, header, sizeof(header), 0)
                != (ssize_t)sizeof(header)) {
            result = -1;
        } else {
            existing_count = message_load_u64(header);
            if (existing_count == 0
                    || existing_count > ARCHPHENE_SEMAPHORE_MAX_COUNT
                    || metadata.st_size != (off_t)(8 + existing_count * 8)) {
                errno = EIO;
                result = -1;
            } else if (count > 0 && (uint64_t)count > existing_count) {
                errno = EINVAL;
                result = -1;
            }
        }
    } else if (result == 0) {
        errno = EIO;
        result = -1;
    }
    int saved_errno = errno;
    (void)flock(descriptor, LOCK_UN);
    close(descriptor);
    errno = saved_errno;
    return result == 0 ? semaphore : -1;
}

static int semop_internal(int semaphore, struct sembuf *operations,
        size_t operation_count, const struct timespec *timeout) {
    typedef int (*open_type)(const char *, int, ...);
    open_type real_open = (open_type)dlsym(RTLD_NEXT, "open");
    if (real_open == NULL || operations == NULL || operation_count == 0
            || operation_count > ARCHPHENE_SEMAPHORE_MAX_COUNT) {
        errno = operations == NULL ? EFAULT : EINVAL;
        return -1;
    }
    struct timespec started = {0, 0};
    if (timeout != NULL
            && (timeout->tv_sec < 0 || timeout->tv_nsec < 0
                || timeout->tv_nsec >= 1000000000L)) {
        errno = EINVAL;
        return -1;
    }
    if (timeout != NULL) (void)clock_gettime(CLOCK_MONOTONIC, &started);
    char path[PATH_MAX];
    if (!semaphore_path(semaphore, path)) return -1;
    for (;;) {
        int descriptor = real_open(path, O_RDWR | O_CLOEXEC);
        if (descriptor < 0) return -1;
        if (flock(descriptor, LOCK_EX) != 0) {
            int saved_errno = errno;
            close(descriptor);
            errno = saved_errno;
            return -1;
        }
        unsigned char bytes[8 + ARCHPHENE_SEMAPHORE_MAX_COUNT * 8];
        ssize_t length = pread(descriptor, bytes, sizeof(bytes), 0);
        uint64_t count =
                length >= 8 ? message_load_u64(bytes) : 0;
        bool valid = count > 0 && count <= ARCHPHENE_SEMAPHORE_MAX_COUNT
                && length == (ssize_t)(8 + count * 8);
        int64_t values[ARCHPHENE_SEMAPHORE_MAX_COUNT];
        if (valid) {
            for (size_t index = 0; index < count; index++) {
                values[index] =
                        (int64_t)message_load_u64(bytes + 8 + index * 8);
            }
        }
        bool ready = valid;
        bool no_wait = false;
        for (size_t index = 0; ready && index < operation_count; index++) {
            struct sembuf operation = operations[index];
            if (operation.sem_num >= count) {
                errno = EFBIG;
                ready = false;
                valid = false;
                break;
            }
            no_wait |= (operation.sem_flg & IPC_NOWAIT) != 0;
            int64_t current = values[operation.sem_num];
            if (operation.sem_op < 0) {
                int64_t required = -(int64_t)operation.sem_op;
                if (current < required) {
                    ready = false;
                } else {
                    values[operation.sem_num] = current - required;
                }
            } else if (operation.sem_op > 0) {
                if (current + operation.sem_op
                        > ARCHPHENE_SEMAPHORE_MAX_VALUE) {
                    errno = ERANGE;
                    ready = false;
                    valid = false;
                } else {
                    values[operation.sem_num] =
                            current + operation.sem_op;
                }
            } else if (current != 0) {
                ready = false;
            }
        }
        int result = -1;
        if (!valid) {
            if (errno == 0) errno = EIO;
        } else if (ready) {
            for (size_t index = 0; index < count; index++) {
                message_store_u64(bytes + 8 + index * 8,
                        (uint64_t)values[index]);
            }
            result = pwrite(descriptor, bytes, (size_t)length, 0) == length
                    ? 0 : -1;
        } else {
            errno = EAGAIN;
        }
        int saved_errno = errno;
        (void)flock(descriptor, LOCK_UN);
        close(descriptor);
        errno = saved_errno;
        if (result == 0 || !valid || no_wait || errno != EAGAIN) return result;
        if (timeout != NULL) {
            struct timespec now;
            if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return -1;
            time_t seconds = now.tv_sec - started.tv_sec;
            long nanoseconds = now.tv_nsec - started.tv_nsec;
            if (nanoseconds < 0) {
                seconds--;
                nanoseconds += 1000000000L;
            }
            if (seconds > timeout->tv_sec
                    || (seconds == timeout->tv_sec
                        && nanoseconds >= timeout->tv_nsec)) {
                errno = EAGAIN;
                return -1;
            }
        }
        usleep(10000);
    }
}

int semop(int semaphore, struct sembuf *operations, size_t operation_count) {
    return semop_internal(semaphore, operations, operation_count, NULL);
}

int semtimedop(int semaphore, struct sembuf *operations,
        size_t operation_count, const struct timespec *timeout) {
    return semop_internal(semaphore, operations, operation_count, timeout);
}

int semctl(int semaphore, int number, int command, ...) {
    typedef int (*open_type)(const char *, int, ...);
    typedef int (*unlink_type)(const char *);
    open_type real_open = (open_type)dlsym(RTLD_NEXT, "open");
    unlink_type real_unlink = (unlink_type)dlsym(RTLD_NEXT, "unlink");
    char path[PATH_MAX];
    if (!semaphore_path(semaphore, path)) return -1;
    if (command == IPC_RMID) {
        if (real_unlink == NULL) {
            errno = ENOSYS;
            return -1;
        }
        return real_unlink(path);
    }
    if (real_open == NULL) {
        errno = ENOSYS;
        return -1;
    }
    va_list arguments;
    va_start(arguments, command);
    int set_value = 0;
    void *pointer = NULL;
    if (command == SETVAL) {
        set_value = va_arg(arguments, int);
    } else if (command == IPC_STAT || command == GETALL || command == SETALL) {
        pointer = va_arg(arguments, void *);
    }
    va_end(arguments);
    int descriptor = real_open(path, O_RDWR | O_CLOEXEC);
    if (descriptor < 0) return -1;
    if (flock(descriptor, LOCK_EX) != 0) {
        int saved_errno = errno;
        close(descriptor);
        errno = saved_errno;
        return -1;
    }
    unsigned char bytes[8 + ARCHPHENE_SEMAPHORE_MAX_COUNT * 8];
    ssize_t length = pread(descriptor, bytes, sizeof(bytes), 0);
    uint64_t count = length >= 8 ? message_load_u64(bytes) : 0;
    int result = -1;
    if (count == 0 || count > ARCHPHENE_SEMAPHORE_MAX_COUNT
            || length != (ssize_t)(8 + count * 8)) {
        errno = EIO;
    } else if (number < 0 || (uint64_t)number >= count) {
        errno = EINVAL;
    } else if (command == GETVAL) {
        result = (int)message_load_u64(bytes + 8 + (size_t)number * 8);
    } else if (command == SETVAL) {
        if (set_value < 0 || set_value > ARCHPHENE_SEMAPHORE_MAX_VALUE) {
            errno = ERANGE;
        } else {
            message_store_u64(bytes + 8 + (size_t)number * 8,
                    (uint64_t)set_value);
            result = pwrite(descriptor, bytes, (size_t)length, 0) == length
                    ? 0 : -1;
        }
    } else if (command == GETALL && pointer != NULL) {
        unsigned short *values = pointer;
        for (size_t index = 0; index < count; index++) {
            values[index] =
                    (unsigned short)message_load_u64(bytes + 8 + index * 8);
        }
        result = 0;
    } else if (command == SETALL && pointer != NULL) {
        unsigned short *values = pointer;
        bool valid = true;
        for (size_t index = 0; index < count; index++) {
            if (values[index] > ARCHPHENE_SEMAPHORE_MAX_VALUE) {
                valid = false;
                break;
            }
            message_store_u64(bytes + 8 + index * 8, values[index]);
        }
        if (!valid) {
            errno = ERANGE;
        } else {
            result = pwrite(descriptor, bytes, (size_t)length, 0) == length
                    ? 0 : -1;
        }
    } else if (command == IPC_STAT && pointer != NULL) {
        struct semid_ds *status = pointer;
        memset(status, 0, sizeof(*status));
        status->sem_perm.mode = 0600;
        status->sem_nsems = count;
        result = 0;
    } else if (command == GETPID || command == GETNCNT || command == GETZCNT) {
        result = 0;
    } else {
        errno = EINVAL;
    }
    int saved_errno = errno;
    (void)flock(descriptor, LOCK_UN);
    close(descriptor);
    errno = saved_errno;
    return result;
}

/*
 * Package transactions need a conventional root identity, while interactive
 * applications must see an ordinary Linux user. Both identities still map to
 * the one kernel-enforced Android app uid.
 */
#define ARCHPHENE_USER_ID 1000

uid_t getuid(void) { return root_identity_active ? 0 : ARCHPHENE_USER_ID; }
uid_t geteuid(void) { return root_identity_active ? 0 : ARCHPHENE_USER_ID; }
gid_t getgid(void) { return root_identity_active ? 0 : ARCHPHENE_USER_ID; }
gid_t getegid(void) { return root_identity_active ? 0 : ARCHPHENE_USER_ID; }

int getresuid(uid_t *real, uid_t *effective, uid_t *saved) {
    uid_t identity = root_identity_active ? 0 : ARCHPHENE_USER_ID;
    if (real != NULL) *real = identity;
    if (effective != NULL) *effective = identity;
    if (saved != NULL) *saved = identity;
    return 0;
}

int getresgid(gid_t *real, gid_t *effective, gid_t *saved) {
    gid_t identity = root_identity_active ? 0 : ARCHPHENE_USER_ID;
    if (real != NULL) *real = identity;
    if (effective != NULL) *effective = identity;
    if (saved != NULL) *saved = identity;
    return 0;
}

pid_t setsid(void) {
    bool preparing_terminal =
            pending_pty_session || process_has_pty_descriptor();
    pending_pty_session = false;
    if (supervised_process_group && !process_has_terminal()
            && !preparing_terminal) {
        return getsid(0);
    }
    typedef pid_t (*function_type)(void);
    function_type real = (function_type)dlsym(RTLD_NEXT, "setsid");
    if (real == NULL) {
        errno = ENOSYS;
        return -1;
    }
    return real();
}

int setpgid(pid_t process, pid_t group) {
    if (supervised_process_group && !process_has_terminal()
            && !process_has_pty_descriptor()) {
        return 0;
    }
    typedef int (*function_type)(pid_t, pid_t);
    function_type real = (function_type)dlsym(RTLD_NEXT, "setpgid");
    if (real == NULL) {
        errno = ENOSYS;
        return -1;
    }
    return real(process, group);
}

static struct passwd archphene_passwd = {
    .pw_name = "archphene",
    .pw_passwd = "x",
    .pw_uid = ARCHPHENE_USER_ID,
    .pw_gid = ARCHPHENE_USER_ID,
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

static struct passwd alpm_passwd = {
    .pw_name = "alpm",
    .pw_passwd = "x",
    .pw_uid = 0,
    .pw_gid = 0,
    .pw_gecos = "Arch Linux Package Management",
    .pw_dir = "/",
    .pw_shell = "/usr/bin/nologin",
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
    if (user == ARCHPHENE_USER_ID) return &archphene_passwd;
    return user == 0 ? &root_passwd : NULL;
}

int getpwuid_r(uid_t user, struct passwd *output, char *buffer,
        size_t buffer_size, struct passwd **result) {
    const struct passwd *source = NULL;
    if (user == ARCHPHENE_USER_ID) {
        source = &archphene_passwd;
    } else if (user == 0) {
        source = &root_passwd;
    }
    if (source == NULL) {
        *result = NULL;
        return 0;
    }
    return copy_passwd(source, output, buffer, buffer_size, result);
}

struct passwd *getpwnam(const char *name) {
    if (strcmp(name, "archphene") == 0) return &archphene_passwd;
    if (strcmp(name, "root") == 0) return &root_passwd;
    return strcmp(name, "alpm") == 0 ? &alpm_passwd : NULL;
}

int getpwnam_r(const char *name, struct passwd *output, char *buffer,
        size_t buffer_size, struct passwd **result) {
    const struct passwd *source = NULL;
    if (strcmp(name, "archphene") == 0) {
        source = &archphene_passwd;
    } else if (strcmp(name, "root") == 0) {
        source = &root_passwd;
    } else if (strcmp(name, "alpm") == 0) {
        source = &alpm_passwd;
    }
    if (source == NULL) {
        *result = NULL;
        return 0;
    }
    return copy_passwd(source, output, buffer, buffer_size, result);
}

int setfsuid(uid_t user) {
    (void)user;
    return root_identity_active ? 0 : ARCHPHENE_USER_ID;
}
int setfsgid(gid_t group) {
    (void)group;
    return root_identity_active ? 0 : ARCHPHENE_USER_ID;
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
    bool translated;
    char buffer[PATH_MAX];
    const char *target = translate_at_path(directory, path, buffer, &translated,
            (flags & AT_SYMLINK_NOFOLLOW) == 0);
    if (target == NULL) return -1;
    if (flags == 0) {
        return (int)syscall(SYS_fchmodat, directory, target, mode);
    }
    if (flags == AT_EMPTY_PATH && target[0] == '\0') {
        return fchmod(directory, mode);
    }
    if (flags == AT_SYMLINK_NOFOLLOW) {
        struct stat metadata;
        if (fstatat(directory, target, &metadata, AT_SYMLINK_NOFOLLOW) != 0) return -1;
        if (S_ISLNK(metadata.st_mode)) return 0;
        return (int)syscall(SYS_fchmodat, directory, target, mode);
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
    return linkat(AT_FDCWD, source, AT_FDCWD, destination, 0);
}

int linkat(int source_directory, const char *source, int destination_directory,
        const char *destination, int flags) {
    typedef int (*function_type)(int, const char *, int, const char *, int);
    function_type real = (function_type)dlsym(RTLD_NEXT, "linkat");
    bool source_translated;
    bool destination_translated;
    char source_buffer[PATH_MAX];
    char destination_buffer[PATH_MAX];
    const char *source_target = translate_at_path(source_directory, source,
            source_buffer, &source_translated, (flags & AT_SYMLINK_FOLLOW) != 0);
    const char *destination_target = translate_at_path(destination_directory,
            destination, destination_buffer, &destination_translated, false);
    if (source_target == NULL || destination_target == NULL) return -1;
    if (real == NULL) {
        errno = ENOSYS;
        return -1;
    }
    if (!fake_chroot_active && (source_translated || destination_translated)) {
        errno = EROFS;
        return -1;
    }
    if (real(source_directory, source_target, destination_directory,
            destination_target, flags) == 0) {
        return 0;
    }
    if ((errno != EPERM && errno != EACCES) || flags != 0) return -1;
    return copy_hardlink_fallback(source_directory, source_target,
            destination_directory, destination_target);
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
    int written = snprintf(trusted_loader_environment,
            sizeof(trusted_loader_environment),
            "ARCHPHENE_RUNTIME_LOADER=%s", trusted_loader);
    if (written <= 0
            || (size_t)written >= sizeof(trusted_loader_environment)) {
        trusted_loader[0] = '\0';
        trusted_loader_environment[0] = '\0';
    }
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

    /*
     * Executing a glibc program through its verified loader otherwise leaves
     * Android and terminal clients reporting the sealed loader filename.
     * Restore the bounded Linux executable name for process inspection.
     */
    const char *name = strrchr(resolved, '/');
    name = name == NULL ? resolved : name + 1;
    char process_name[16];
    size_t name_length = strlen(name);
    if (name_length > sizeof(process_name) - 1) {
        name_length = sizeof(process_name) - 1;
    }
    memcpy(process_name, name, name_length);
    process_name[name_length] = '\0';
    (void)prctl(PR_SET_NAME, process_name);
}

static bool initialize_trusted_shm_root(void) {
    const char *runtime = getenv("XDG_RUNTIME_DIR");
    size_t root_length = strlen(trusted_root);
    int length;
    if (runtime != NULL && strncmp(runtime, trusted_root, root_length) == 0
            && (runtime[root_length] == '\0' || runtime[root_length] == '/')) {
        length = snprintf(trusted_shm_root, sizeof(trusted_shm_root),
                "%s/.archphene-shm", runtime);
    } else if (runtime == NULL || strcmp(runtime, "/run") == 0) {
        length = snprintf(trusted_shm_root, sizeof(trusted_shm_root),
                "%s/run/.archphene-shm", trusted_root);
    } else {
        return false;
    }
    if (length <= 0 || (size_t)length >= sizeof(trusted_shm_root)) return false;

    typedef int (*lstat_type)(const char *, struct stat *);
    typedef int (*mkdir_type)(const char *, mode_t);
    lstat_type real_lstat = (lstat_type)dlsym(RTLD_NEXT, "lstat");
    mkdir_type real_mkdir = (mkdir_type)dlsym(RTLD_NEXT, "mkdir");
    if (real_lstat == NULL || real_mkdir == NULL) return false;
    struct stat metadata;
    if (real_lstat(trusted_shm_root, &metadata) == 0) {
        return S_ISDIR(metadata.st_mode) && !S_ISLNK(metadata.st_mode);
    }
    return errno == ENOENT && real_mkdir(trusted_shm_root, 0700) == 0;
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
    int written = snprintf(trusted_root_environment,
            sizeof(trusted_root_environment),
            "ARCHPHENE_RUNTIME_ROOT=%s", trusted_root);
    if (written <= 0
            || (size_t)written >= sizeof(trusted_root_environment)) {
        trusted_root[0] = '\0';
        trusted_root_environment[0] = '\0';
        return;
    }
    const char *active = getenv("ARCHPHENE_FAKE_CHROOT");
    fake_chroot_active = active != NULL && strcmp(active, "1") == 0;
    active = getenv("ARCHPHENE_ROOT_IDENTITY");
    root_identity_active = active != NULL && strcmp(active, "1") == 0;
    active = getenv("ARCHPHENE_SUPERVISED_PROCESS_GROUP");
    supervised_process_group = active != NULL && strcmp(active, "1") == 0;
    if (fake_chroot_active && (!initialize_trusted_shm_root()
            || !install_runtime_compatibility_filter())) {
        static const char failure[] =
                "Archphene could not initialize Linux compatibility\n";
        (void)write(STDERR_FILENO, failure, sizeof(failure) - 1);
        _exit(EXIT_FAILURE);
    }
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

static bool safe_root_executable(const char *path, char output[PATH_MAX]);

static bool runtime_command(const char *name, char output[PATH_MAX]) {
    if (!safe_command_name(name)) return false;
    const char *directory = trusted_command_directory;
    if (directory[0] == '\0') {
        return false;
    }
    struct stat metadata;
    typedef int (*access_type)(const char *, int);
    typedef int (*lstat_type)(const char *, struct stat *);
    typedef int (*stat_type)(const char *, struct stat *);
    typedef char *(*realpath_type)(const char *, char *);
    access_type real_access = (access_type)dlsym(RTLD_NEXT, "access");
    lstat_type real_lstat = (lstat_type)dlsym(RTLD_NEXT, "lstat");
    stat_type real_stat = (stat_type)dlsym(RTLD_NEXT, "stat");
    realpath_type real_realpath =
            (realpath_type)dlsym(RTLD_NEXT, "realpath");
    int length = snprintf(output, PATH_MAX, "%s/%s", directory, name);
    if (length > 0 && length < PATH_MAX
            && stat(output, &metadata) == 0 && S_ISREG(metadata.st_mode)
            && (metadata.st_mode & (S_IWGRP | S_IWOTH)) == 0
            && real_access != NULL && real_access(output, R_OK) == 0) {
        struct stat link_metadata;
        if (real_lstat != NULL && real_lstat(output, &link_metadata) == 0
                && !S_ISLNK(link_metadata.st_mode)) {
            return true;
        }
        if (real_lstat == NULL || real_stat == NULL || real_realpath == NULL) {
            return false;
        }
        /*
         * The sealed command directory contains manifest-verified symlinks
         * into the APK native-library directory. Passing such an alias back
         * to the explicit loader from a nested exec makes the preload bridge
         * translate it again and can form a symlink loop. Resolve only this
         * manager-authorized command entry to its already verified regular
         * target; package paths continue through safe_root_executable().
        */
        char resolved[PATH_MAX];
        if (real_realpath(output, resolved) == NULL
                || real_stat(resolved, &metadata) != 0
                || !S_ISREG(metadata.st_mode)
                || (metadata.st_mode & (S_IWGRP | S_IWOTH)) != 0
                || real_access(resolved, R_OK) != 0) {
            return false;
        }
        size_t resolved_length = strlen(resolved);
        if (resolved_length >= PATH_MAX) return false;
        memcpy(output, resolved, resolved_length + 1);
        return true;
    }
    if (trusted_root[0] == '\0') return false;
    char logical[PATH_MAX];
    length = snprintf(logical, sizeof(logical), "/usr/bin/%s", name);
    return length > 0 && (size_t)length < sizeof(logical)
            && safe_root_executable(logical, output);
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

static bool trusted_runtime_command_path(const char *path, const char *name) {
    if (path == NULL || name == NULL || trusted_command_directory[0] == '\0') {
        return false;
    }
    char expected[PATH_MAX];
    int length = snprintf(expected, sizeof(expected), "%s/%s",
            trusted_command_directory, name);
    return length > 0 && (size_t)length < sizeof(expected)
            && strcmp(path, expected) == 0;
}

static bool inside_trusted_root(const char *path) {
    if (trusted_root[0] == '\0' || path == NULL) return false;
    size_t root_length = strlen(trusted_root);
    return strncmp(path, trusted_root, root_length) == 0
            && (path[root_length] == '\0' || path[root_length] == '/');
}

static bool inside_kernel_filesystem(const char *path) {
    if (path == NULL) return false;
    static const char *const prefixes[] = {"/dev", "/proc", "/sys"};
    for (size_t index = 0; index < sizeof(prefixes) / sizeof(prefixes[0]);
            index++) {
        size_t length = strlen(prefixes[index]);
        if (strncmp(path, prefixes[index], length) == 0
                && (path[length] == '\0' || path[length] == '/')) {
            return true;
        }
    }
    return false;
}

int capset(struct __user_cap_header_struct *header,
        const struct __user_cap_data_struct *data) {
    if (fake_chroot_active && root_identity_active) return 0;
    typedef int (*function_type)(struct __user_cap_header_struct *,
            const struct __user_cap_data_struct *);
    function_type real = (function_type)dlsym(RTLD_NEXT, "capset");
    if (real == NULL) {
        errno = ENOSYS;
        return -1;
    }
    return real(header, data);
}

static bool normalize_logical_path(const char *path, char output[PATH_MAX]) {
    if (path == NULL || path[0] != '/' || strchr(path, '\n') != NULL) {
        return false;
    }
    if (inside_trusted_root(path)) {
        path += strlen(trusted_root);
        if (path[0] == '\0') path = "/";
    }
    size_t output_length = 1;
    output[0] = '/';
    output[1] = '\0';
    const char *cursor = path + 1;
    while (*cursor != '\0') {
        while (*cursor == '/') cursor++;
        const char *component = cursor;
        while (*cursor != '\0' && *cursor != '/') cursor++;
        size_t length = (size_t)(cursor - component);
        if (length == 0 || (length == 1 && component[0] == '.')) continue;
        if (length == 2 && component[0] == '.' && component[1] == '.') {
            if (output_length == 1) return false;
            while (output_length > 1 && output[output_length - 1] != '/') {
                output_length--;
            }
            if (output_length > 1) output_length--;
            output[output_length] = '\0';
            continue;
        }
        size_t separator = output_length == 1 ? 0 : 1;
        if (length > PATH_MAX - output_length - separator - 1) return false;
        if (separator != 0) output[output_length++] = '/';
        memcpy(output + output_length, component, length);
        output_length += length;
        output[output_length] = '\0';
    }
    return true;
}

static const char *translate_at_path(int directory, const char *path,
        char output[PATH_MAX], bool *translated, bool follow) {
    if (path == NULL || path[0] == '/' || directory == AT_FDCWD) {
        return follow
                ? translate_follow_path(path, output, translated)
                : translate_path(path, output, translated);
    }
    *translated = false;
    if (!fake_chroot_active || path[0] == '\0') return path;
    typedef ssize_t (*readlink_type)(const char *, char *, size_t);
    readlink_type real_readlink = (readlink_type)dlsym(RTLD_NEXT, "readlink");
    if (real_readlink == NULL || trusted_root[0] == '\0') {
        errno = EACCES;
        return NULL;
    }
    char descriptor[64];
    int descriptor_length = snprintf(descriptor, sizeof(descriptor),
            "/proc/self/fd/%d", directory);
    if (descriptor_length <= 0
            || (size_t)descriptor_length >= sizeof(descriptor)) {
        errno = EBADF;
        return NULL;
    }
    char base[PATH_MAX];
    ssize_t base_length =
            real_readlink(descriptor, base, sizeof(base) - 1);
    if (base_length <= 0 || (size_t)base_length >= sizeof(base)) return NULL;
    base[base_length] = '\0';
    size_t root_length = strlen(trusted_root);
    if (strncmp(base, trusted_root, root_length) != 0
            || (base[root_length] != '\0' && base[root_length] != '/')) {
        /*
         * Kernel-backed filesystems are deliberately visible to Linux
         * applications and remain constrained by Android's app sandbox.
         * Preserve directory-relative operations below them just as their
         * absolute paths are preserved by translate_path(). Chromium, for
         * example, opens /proc once and checks self/task with fstatat().
         */
        if (inside_kernel_filesystem(base) && !has_parent_component(path)) {
            return path;
        }
        errno = EACCES;
        return NULL;
    }
    const char *logical_base = base + root_length;
    if (logical_base[0] == '\0') logical_base = "/";
    char combined[PATH_MAX];
    int combined_length = snprintf(combined, sizeof(combined), "%s%s%s",
            logical_base, strcmp(logical_base, "/") == 0 ? "" : "/", path);
    char logical[PATH_MAX];
    if (combined_length <= 0 || (size_t)combined_length >= sizeof(combined)
            || !normalize_logical_path(combined, logical)) {
        errno = EACCES;
        return NULL;
    }
    int output_length =
            snprintf(output, PATH_MAX, "%s%s", trusted_root, logical);
    if (output_length <= 0 || output_length >= PATH_MAX) {
        errno = ENAMETOOLONG;
        return NULL;
    }
    *translated = true;
    return output;
}

static bool resolve_root_path(const char *path, char output[PATH_MAX]) {
    char logical[PATH_MAX];
    if (!normalize_logical_path(path, logical)) return false;
    typedef int (*lstat_type)(const char *, struct stat *);
    typedef ssize_t (*readlink_type)(const char *, char *, size_t);
    lstat_type real_lstat = (lstat_type)dlsym(RTLD_NEXT, "lstat");
    readlink_type real_readlink = (readlink_type)dlsym(RTLD_NEXT, "readlink");
    if (real_lstat == NULL || real_readlink == NULL) return false;

    for (size_t symlink_count = 0; symlink_count <= 40; symlink_count++) {
        size_t prefix_length = 0;
        char prefix[PATH_MAX];
        prefix[0] = '\0';
        const char *cursor = logical + 1;
        bool restarted = false;
        while (*cursor != '\0') {
            const char *component = cursor;
            while (*cursor != '\0' && *cursor != '/') cursor++;
            size_t component_length = (size_t)(cursor - component);
            size_t parent_length = prefix_length;
            if (component_length > PATH_MAX - prefix_length - 2) return false;
            prefix[prefix_length++] = '/';
            memcpy(prefix + prefix_length, component, component_length);
            prefix_length += component_length;
            prefix[prefix_length] = '\0';

            char candidate[PATH_MAX];
            int candidate_length = snprintf(candidate, sizeof(candidate), "%s%s",
                    trusted_root, prefix);
            if (candidate_length <= 0
                    || (size_t)candidate_length >= sizeof(candidate)) {
                return false;
            }
            struct stat metadata;
            if (real_lstat(candidate, &metadata) != 0) {
                if (errno != ENOENT) return false;
                /*
                 * Every existing ancestor has already been checked and any
                 * symlink among them resolved inside the logical root. Let
                 * the eventual filesystem operation report ENOENT for a
                 * contained probe instead of turning normal compiler and
                 * configure checks into EACCES failures.
                 */
                int output_length = snprintf(output, PATH_MAX, "%s%s",
                        trusted_root, logical);
                return output_length > 0 && output_length < PATH_MAX;
            }
            if (S_ISLNK(metadata.st_mode)) {
                if (symlink_count == 40) return false;
                char target[PATH_MAX];
                ssize_t target_length =
                        real_readlink(candidate, target, sizeof(target) - 1);
                if (target_length <= 0
                        || (size_t)target_length >= sizeof(target)) {
                    return false;
                }
                target[target_length] = '\0';
                char combined[PATH_MAX];
                int combined_length;
                if (target[0] == '/') {
                    combined_length = snprintf(combined, sizeof(combined), "%s%s",
                            target, cursor);
                } else {
                    prefix[parent_length] = '\0';
                    combined_length = snprintf(combined, sizeof(combined),
                            "%s/%s%s", parent_length == 0 ? "" : prefix,
                            target, cursor);
                }
                if (combined_length <= 0
                        || (size_t)combined_length >= sizeof(combined)
                        || !normalize_logical_path(combined, logical)) {
                    return false;
                }
                restarted = true;
                break;
            }
            if (*cursor == '/') cursor++;
        }
        if (restarted) continue;
        int output_length = snprintf(output, PATH_MAX, "%s%s",
                trusted_root, logical);
        return output_length > 0 && output_length < PATH_MAX;
    }
    return false;
}

static bool safe_root_executable(const char *path, char output[PATH_MAX]) {
    if (path == NULL || path[0] == '\0' || trusted_root[0] == '\0'
            || strchr(path, '\n') != NULL) {
        return false;
    }
    if (path[0] == '/') {
        if (!resolve_root_path(path, output)) return false;
    } else {
        bool translated;
        char candidate[PATH_MAX];
        const char *target =
                translate_follow_path(path, candidate, &translated);
        size_t length = target == NULL ? 0 : strlen(target);
        if (target == NULL || !inside_trusted_root(target)
                || length == 0 || length >= PATH_MAX) {
            return false;
        }
        memcpy(output, target, length + 1);
    }
    struct stat metadata;
    if (stat(output, &metadata) != 0 || !S_ISREG(metadata.st_mode)
            || (metadata.st_mode & 0111) == 0
            || (metadata.st_mode & S_IWOTH) != 0) {
        return false;
    }
    typedef int (*access_type)(const char *, int);
    access_type real_access = (access_type)dlsym(RTLD_NEXT, "access");
    return real_access != NULL && real_access(output, R_OK) == 0;
}

static bool resolve_runtime_executable(const char *path, char output[PATH_MAX]) {
    if (path == NULL || path[0] == '\0') return false;
    if (strcmp(path, "/proc/self/exe") == 0
            && trusted_program_path[0] != '\0') {
        size_t length = strlen(trusted_program_path);
        if (length >= PATH_MAX) return false;
        memcpy(output, trusted_program_path, length + 1);
        return true;
    }
    const char *name = strrchr(path, '/');
    name = name == NULL ? path : name + 1;
    char command[PATH_MAX];
    if (runtime_command(name, command)
            && (trusted_runtime_command_path(path, name)
                || conventional_linux_command(path, name))) {
        size_t length = strlen(command);
        if (length >= PATH_MAX) return false;
        memcpy(output, command, length + 1);
        return true;
    }
    return safe_root_executable(path, output);
}

#define RUNTIME_SHEBANG_LIMIT 256

struct runtime_launch {
    char program[PATH_MAX];
    char argv0[PATH_MAX];
    char script[PATH_MAX];
    char interpreter_argument[RUNTIME_SHEBANG_LIMIT];
    bool script_program;
    bool has_interpreter_argument;
};

static bool copy_runtime_string(char *output, size_t capacity,
        const char *source, size_t length) {
    if (length == 0 || length >= capacity) return false;
    memcpy(output, source, length);
    output[length] = '\0';
    return true;
}

static bool read_runtime_header(const char *path, unsigned char *output,
        size_t capacity, size_t *length) {
    typedef int (*open_type)(const char *, int, ...);
    open_type real_open = (open_type)dlsym(RTLD_NEXT, "open");
    if (real_open == NULL) return false;
    int descriptor = real_open(path, O_RDONLY | O_CLOEXEC);
    if (descriptor < 0) return false;
    ssize_t count;
    do {
        count = read(descriptor, output, capacity);
    } while (count < 0 && errno == EINTR);
    int saved_errno = errno;
    close(descriptor);
    errno = saved_errno;
    if (count < 0) return false;
    *length = (size_t)count;
    return true;
}

static bool runtime_header_is_elf(const char *path) {
    unsigned char header[4];
    size_t length;
    return read_runtime_header(path, header, sizeof(header), &length)
            && length == sizeof(header)
            && memcmp(header, "\x7f" "ELF", sizeof(header)) == 0;
}

static int prepare_runtime_launch(const char *name, const char *requested,
        const char *command, struct runtime_launch *launch) {
    memset(launch, 0, sizeof(*launch));
    size_t command_length = strlen(command);
    size_t name_length = strlen(name);
    if (!copy_runtime_string(
                launch->program, sizeof(launch->program), command, command_length)
            || !copy_runtime_string(
                launch->argv0, sizeof(launch->argv0), name, name_length)) {
        return ENAMETOOLONG;
    }
    if (strcmp(requested, "/proc/self/exe") == 0) {
        const char *logical_program = trusted_linux_program_path();
        const char *logical_name = logical_program == NULL
                ? NULL : strrchr(logical_program, '/');
        logical_name = logical_name == NULL ? logical_program : logical_name + 1;
        if (logical_name == NULL || logical_name[0] == '\0'
                || !copy_runtime_string(launch->argv0, sizeof(launch->argv0),
                    logical_name, strlen(logical_name))) {
            return ENOEXEC;
        }
    }
    unsigned char header[RUNTIME_SHEBANG_LIMIT];
    size_t header_length;
    if (!read_runtime_header(command, header, sizeof(header), &header_length)) {
        return errno == 0 ? EIO : errno;
    }
    if (header_length >= 4
            && memcmp(header, "\x7f" "ELF", 4) == 0) {
        return 0;
    }
    if (header_length < 3 || header[0] != '#' || header[1] != '!') {
        return ENOEXEC;
    }
    size_t line_end = 2;
    while (line_end < header_length && header[line_end] != '\n') line_end++;
    if (line_end == header_length && header_length == sizeof(header)) {
        return ENOEXEC;
    }
    for (size_t index = 2; index < line_end; index++) {
        if (header[index] == '\0') return ENOEXEC;
    }
    while (line_end > 2
            && (header[line_end - 1] == '\r'
                || header[line_end - 1] == ' '
                || header[line_end - 1] == '\t')) {
        line_end--;
    }
    size_t interpreter_start = 2;
    while (interpreter_start < line_end
            && (header[interpreter_start] == ' '
                || header[interpreter_start] == '\t')) {
        interpreter_start++;
    }
    size_t interpreter_end = interpreter_start;
    while (interpreter_end < line_end
            && header[interpreter_end] != ' '
            && header[interpreter_end] != '\t') {
        interpreter_end++;
    }
    char interpreter[PATH_MAX];
    if (!copy_runtime_string(interpreter, sizeof(interpreter),
                (const char *)header + interpreter_start,
                interpreter_end - interpreter_start)
            || interpreter[0] != '/') {
        return ENOEXEC;
    }
    while (interpreter_end < line_end
            && (header[interpreter_end] == ' '
                || header[interpreter_end] == '\t')) {
        interpreter_end++;
    }
    if (interpreter_end < line_end) {
        if (!copy_runtime_string(
                    launch->interpreter_argument,
                    sizeof(launch->interpreter_argument),
                    (const char *)header + interpreter_end,
                    line_end - interpreter_end)) {
            return ENOEXEC;
        }
        launch->has_interpreter_argument = true;
    }
    if (!resolve_runtime_executable(interpreter, launch->program)
            || !runtime_header_is_elf(launch->program)) {
        return ENOEXEC;
    }
    const char *interpreter_name = strrchr(interpreter, '/');
    interpreter_name =
            interpreter_name == NULL ? interpreter : interpreter_name + 1;
    if (!copy_runtime_string(launch->argv0, sizeof(launch->argv0),
                interpreter_name, strlen(interpreter_name))) {
        return ENOEXEC;
    }
    const char *script = requested;
    char conventional[PATH_MAX];
    if (strchr(requested, '/') == NULL) {
        int length = snprintf(conventional, sizeof(conventional),
                "/usr/bin/%s", requested);
        if (length <= 0 || (size_t)length >= sizeof(conventional)) {
            return ENAMETOOLONG;
        }
        script = conventional;
    } else if (inside_trusted_root(requested)) {
        const char *logical = requested + strlen(trusted_root);
        static const char *const prefixes[] = {
            "/bin", "/etc", "/home", "/lib", "/opt", "/run",
            "/sbin", "/tmp", "/usr", "/var"
        };
        for (size_t index = 0;
                index < sizeof(prefixes) / sizeof(prefixes[0]); index++) {
            size_t length = strlen(prefixes[index]);
            if (strncmp(logical, prefixes[index], length) == 0
                    && (logical[length] == '\0' || logical[length] == '/')) {
                script = logical;
                break;
            }
        }
    }
    if (!copy_runtime_string(
                launch->script, sizeof(launch->script), script, strlen(script))) {
        return ENAMETOOLONG;
    }
    launch->script_program = true;
    return 0;
}

#define RUNTIME_PROGRAM_ENVIRONMENT "ARCHPHENE_RUNTIME_PROGRAM_PATH="

static int prepare_runtime_environment(char *const environment[],
        const char *program, char entry[PATH_MAX + 34],
        char *output[4096]) {
    int written = snprintf(entry, PATH_MAX + 34, "%s%s",
            RUNTIME_PROGRAM_ENVIRONMENT, program);
    if (written <= 0 || written >= PATH_MAX + 34) return ENAMETOOLONG;
    char *const *source = environment == NULL ? environ : environment;
    size_t output_count = 0;
    for (size_t index = 0; source[index] != NULL; index++) {
        if (strncmp(source[index], RUNTIME_PROGRAM_ENVIRONMENT,
                    sizeof(RUNTIME_PROGRAM_ENVIRONMENT) - 1) == 0
                || strncmp(source[index], "LD_PRELOAD=", 11) == 0
                || strncmp(source[index], "ARCHPHENE_RUNTIME_LOADER=",
                    sizeof("ARCHPHENE_RUNTIME_LOADER=") - 1) == 0
                || strncmp(source[index], "ARCHPHENE_RUNTIME_LIB=",
                    sizeof("ARCHPHENE_RUNTIME_LIB=") - 1) == 0
                || strncmp(source[index], "ARCHPHENE_RUNTIME_COMMAND_DIR=",
                    sizeof("ARCHPHENE_RUNTIME_COMMAND_DIR=") - 1) == 0
                || strncmp(source[index], "ARCHPHENE_RUNTIME_ROOT=",
                    sizeof("ARCHPHENE_RUNTIME_ROOT=") - 1) == 0
                || strncmp(source[index], "ARCHPHENE_FAKE_CHROOT=",
                    sizeof("ARCHPHENE_FAKE_CHROOT=") - 1) == 0
                || strncmp(source[index], "ARCHPHENE_ROOT_IDENTITY=",
                    sizeof("ARCHPHENE_ROOT_IDENTITY=") - 1) == 0
                || strncmp(source[index],
                    "ARCHPHENE_SUPERVISED_PROCESS_GROUP=",
                    sizeof("ARCHPHENE_SUPERVISED_PROCESS_GROUP=") - 1) == 0) {
            continue;
        }
        if (output_count >= 4087) return E2BIG;
        output[output_count++] = source[index];
    }
    if (trusted_preload_environment[0] == '\0'
            || trusted_loader_environment[0] == '\0'
            || trusted_library_environment[0] == '\0'
            || trusted_root_environment[0] == '\0') {
        return EACCES;
    }
    output[output_count++] = trusted_preload_environment;
    output[output_count++] = trusted_loader_environment;
    output[output_count++] = trusted_library_environment;
    output[output_count++] = trusted_root_environment;
    if (trusted_command_environment[0] != '\0') {
        output[output_count++] = trusted_command_environment;
    }
    output[output_count++] = fake_chroot_active
            ? "ARCHPHENE_FAKE_CHROOT=1"
            : "ARCHPHENE_FAKE_CHROOT=0";
    if (root_identity_active) {
        output[output_count++] = "ARCHPHENE_ROOT_IDENTITY=1";
    }
    if (supervised_process_group) {
        output[output_count++] = "ARCHPHENE_SUPERVISED_PROCESS_GROUP=1";
    }
    output[output_count++] = entry;
    output[output_count] = NULL;
    return 0;
}

static bool runtime_vaddr_offset(const Elf64_Phdr *headers, size_t count,
        Elf64_Addr address, Elf64_Off *offset) {
    for (size_t index = 0; index < count; index++) {
        const Elf64_Phdr *header = &headers[index];
        if (header->p_type != PT_LOAD || address < header->p_vaddr
                || address - header->p_vaddr >= header->p_filesz) {
            continue;
        }
        *offset = header->p_offset + (address - header->p_vaddr);
        return true;
    }
    return false;
}

#define RUNTIME_LINKAGE_OBJECT_LIMIT 256
#define RUNTIME_LINKAGE_OBJECT_PATH_BYTES 1024
#define RUNTIME_LINKAGE_NEEDED_LIMIT 256
#define RUNTIME_LINKAGE_SEARCH_PATH_LIMIT 64

struct runtime_linkage_queue {
    size_t count;
    size_t next;
    char objects[RUNTIME_LINKAGE_OBJECT_LIMIT]
            [RUNTIME_LINKAGE_OBJECT_PATH_BYTES];
};

struct runtime_linkage_search {
    size_t count;
    char paths[RUNTIME_LINKAGE_SEARCH_PATH_LIMIT]
            [RUNTIME_LINKAGE_OBJECT_PATH_BYTES];
};

static bool runtime_library_path_contains(
        const char *library_path, const char *directory) {
    size_t directory_length = strlen(directory);
    const char *entry = library_path;
    while (entry != NULL) {
        const char *separator = strchr(entry, ':');
        size_t length = separator == NULL
                ? strlen(entry) : (size_t)(separator - entry);
        if (length == directory_length
                && memcmp(entry, directory, length) == 0) {
            return true;
        }
        entry = separator == NULL ? NULL : separator + 1;
    }
    return false;
}

static int runtime_linkage_queue_add(
        struct runtime_linkage_queue *queue, const char *path) {
    size_t length = strlen(path);
    if (!inside_trusted_root(path)
            || length == 0 || length >= RUNTIME_LINKAGE_OBJECT_PATH_BYTES) {
        return EACCES;
    }
    for (size_t index = 0; index < queue->count; index++) {
        if (strcmp(queue->objects[index], path) == 0) return 0;
    }
    if (queue->count >= RUNTIME_LINKAGE_OBJECT_LIMIT) return E2BIG;
    memcpy(queue->objects[queue->count++], path, length + 1);
    return 0;
}

static int runtime_linkage_search_add(
        struct runtime_linkage_search *search, const char *path) {
    size_t length = strlen(path);
    if (!inside_trusted_root(path)
            || length == 0 || length >= RUNTIME_LINKAGE_OBJECT_PATH_BYTES) {
        return EACCES;
    }
    for (size_t index = 0; index < search->count; index++) {
        if (strcmp(search->paths[index], path) == 0) return 0;
    }
    if (search->count >= RUNTIME_LINKAGE_SEARCH_PATH_LIMIT) return E2BIG;
    memcpy(search->paths[search->count++], path, length + 1);
    return 0;
}

static int runtime_linkage_directory(
        const char *object, const char *entry, char physical[PATH_MAX]) {
    if (entry[0] == '/') {
        if (has_parent_component(entry)
                || !resolve_root_path(entry, physical)) {
            return EACCES;
        }
    } else {
        const char *suffix = NULL;
        if (strcmp(entry, "$ORIGIN") == 0
                || strcmp(entry, "${ORIGIN}") == 0) {
            suffix = "";
        } else if (strncmp(entry, "$ORIGIN/", 8) == 0) {
            suffix = entry + 8;
        } else if (strncmp(entry, "${ORIGIN}/", 10) == 0) {
            suffix = entry + 10;
        } else {
            return ENOENT;
        }
        char parent[PATH_MAX];
        size_t object_length = strlen(object);
        if (object_length >= sizeof(parent)) return ENAMETOOLONG;
        memcpy(parent, object, object_length + 1);
        char *separator = strrchr(parent, '/');
        if (separator == NULL) return EACCES;
        *separator = '\0';
        char candidate[PATH_MAX];
        int length = snprintf(candidate, sizeof(candidate), "%s/%s",
                parent, suffix);
        if (length <= 0 || (size_t)length >= sizeof(candidate)) {
            return ENAMETOOLONG;
        }
        if (realpath(candidate, physical) == NULL) {
            return errno == ENOENT ? ENOENT : EACCES;
        }
    }
    struct stat metadata;
    if (stat(physical, &metadata) != 0) {
        return errno == ENOENT ? ENOENT : EACCES;
    }
    if (!inside_trusted_root(physical)
            || !S_ISDIR(metadata.st_mode)
            || (metadata.st_mode & S_IWOTH) != 0) {
        return EACCES;
    }
    return 0;
}

static int runtime_linkage_candidate(
        struct runtime_linkage_queue *queue,
        const char *directory, const char *name) {
    char candidate[PATH_MAX];
    int length = snprintf(candidate, sizeof(candidate), "%s/%s",
            directory, name);
    if (length <= 0 || (size_t)length >= sizeof(candidate)) {
        return ENAMETOOLONG;
    }
    char resolved[PATH_MAX];
    if (realpath(candidate, resolved) == NULL) {
        return errno == ENOENT ? ENOENT : EACCES;
    }
    struct stat metadata;
    if (stat(resolved, &metadata) != 0
            || !inside_trusted_root(resolved)
            || !S_ISREG(metadata.st_mode)
            || (metadata.st_mode & S_IWOTH) != 0) {
        return EACCES;
    }
    return runtime_linkage_queue_add(queue, resolved);
}

static int runtime_linkage_dependency(
        struct runtime_linkage_queue *queue,
        const struct runtime_linkage_search *search,
        const char *object, const char *name) {
    size_t name_length = strlen(name);
    if (name_length == 0 || name_length > 255 || strchr(name, '/') != NULL) {
        return ENOEXEC;
    }
    for (const unsigned char *cursor = (const unsigned char *)name;
            *cursor != '\0'; cursor++) {
        if (*cursor < 32 || *cursor == 127) return ENOEXEC;
    }
    (void)object;
    static const char *const defaults[] = {"/usr/lib", "/lib"};
    for (size_t index = 0;
            index < sizeof(defaults) / sizeof(defaults[0]); index++) {
        char directory[PATH_MAX];
        if (!resolve_root_path(defaults[index], directory)) return EACCES;
        int result = runtime_linkage_candidate(queue, directory, name);
        if (result == 0) return 0;
        if (result != ENOENT) return result;
    }
    for (size_t index = 0; index < search->count; index++) {
        int result = runtime_linkage_candidate(
                queue, search->paths[index], name);
        if (result == 0) return 0;
        if (result != ENOENT) return result;
    }
    return 0;
}

static int runtime_dynamic_string(int descriptor,
        const struct stat *metadata, Elf64_Off table_offset,
        Elf64_Xword table_bytes, Elf64_Xword index,
        char *output, size_t output_bytes) {
    if (index >= table_bytes || output_bytes < 2
            || table_offset > (Elf64_Off)metadata->st_size
            || index > (Elf64_Xword)metadata->st_size - table_offset) {
        return ENOEXEC;
    }
    size_t maximum = (size_t)(table_bytes - index);
    if (maximum > output_bytes) maximum = output_bytes;
    if ((Elf64_Xword)maximum > (Elf64_Xword)metadata->st_size
            - table_offset - index) {
        maximum = (size_t)((Elf64_Xword)metadata->st_size
                - table_offset - index);
    }
    if (maximum == 0
            || pread(descriptor, output, maximum,
                (off_t)(table_offset + index)) != (ssize_t)maximum) {
        return EIO;
    }
    char *terminator = memchr(output, '\0', maximum);
    if (terminator == NULL) return ENAMETOOLONG;
    return 0;
}

static int runtime_collect_linkage_object(const char *object,
        char output[PATH_MAX], size_t *output_length,
        struct runtime_linkage_queue *queue) {
    typedef int (*open_type)(const char *, int, ...);
    open_type real_open = (open_type)dlsym(RTLD_NEXT, "open");
    if (real_open == NULL) return ENOSYS;
    int descriptor = real_open(object, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (descriptor < 0) return errno;
    int result = 0;
    struct stat metadata;
    Elf64_Ehdr elf;
    if (fstat(descriptor, &metadata) != 0
            || !S_ISREG(metadata.st_mode)
            || pread(descriptor, &elf, sizeof(elf), 0) != (ssize_t)sizeof(elf)
            || memcmp(elf.e_ident, ELFMAG, SELFMAG) != 0
            || elf.e_ident[EI_CLASS] != ELFCLASS64
            || elf.e_ident[EI_DATA] != ELFDATA2LSB
            || elf.e_phentsize != sizeof(Elf64_Phdr)
            || elf.e_phnum == 0 || elf.e_phnum > 128
            || elf.e_phoff > (Elf64_Off)metadata.st_size
            || (Elf64_Xword)elf.e_phnum * sizeof(Elf64_Phdr)
                > (Elf64_Xword)metadata.st_size - elf.e_phoff) {
        result = ENOEXEC;
        goto complete;
    }
    Elf64_Phdr headers[128];
    size_t header_bytes = (size_t)elf.e_phnum * sizeof(Elf64_Phdr);
    if (pread(descriptor, headers, header_bytes, (off_t)elf.e_phoff)
            != (ssize_t)header_bytes) {
        result = EIO;
        goto complete;
    }
    const Elf64_Phdr *dynamic = NULL;
    for (size_t index = 0; index < elf.e_phnum; index++) {
        if (headers[index].p_type == PT_DYNAMIC) {
            dynamic = &headers[index];
            break;
        }
    }
    if (dynamic == NULL) goto complete;
    if (dynamic->p_offset > (Elf64_Off)metadata.st_size
            || dynamic->p_filesz > (Elf64_Xword)metadata.st_size
                    - dynamic->p_offset
            || dynamic->p_filesz / sizeof(Elf64_Dyn) > 4096) {
        result = ENOEXEC;
        goto complete;
    }
    Elf64_Addr string_table_address = 0;
    Elf64_Xword string_table_bytes = 0;
    Elf64_Xword runpath_index = 0;
    bool has_runpath = false;
    bool has_path = false;
    Elf64_Xword needed[RUNTIME_LINKAGE_NEEDED_LIMIT];
    size_t needed_count = 0;
    size_t dynamic_count = (size_t)(dynamic->p_filesz / sizeof(Elf64_Dyn));
    for (size_t index = 0; index < dynamic_count; index++) {
        Elf64_Dyn entry;
        off_t offset = (off_t)(dynamic->p_offset
                + index * sizeof(Elf64_Dyn));
        if (pread(descriptor, &entry, sizeof(entry), offset)
                != (ssize_t)sizeof(entry)) {
            result = EIO;
            goto complete;
        }
        if (entry.d_tag == DT_NULL) break;
        if (entry.d_tag == DT_STRTAB) {
            string_table_address = entry.d_un.d_ptr;
        } else if (entry.d_tag == DT_STRSZ) {
            string_table_bytes = entry.d_un.d_val;
        } else if (entry.d_tag == DT_NEEDED) {
            if (needed_count >= RUNTIME_LINKAGE_NEEDED_LIMIT) {
                result = E2BIG;
                goto complete;
            }
            needed[needed_count++] = entry.d_un.d_val;
        } else if (entry.d_tag == DT_RUNPATH
                || (entry.d_tag == DT_RPATH && !has_runpath)) {
            runpath_index = entry.d_un.d_val;
            has_runpath = entry.d_tag == DT_RUNPATH;
            has_path = true;
        }
    }
    if (needed_count == 0 && !has_path) goto complete;
    if (string_table_address == 0 || string_table_bytes == 0) {
        result = ENOEXEC;
        goto complete;
    }
    Elf64_Off string_table_offset;
    if (!runtime_vaddr_offset(headers, elf.e_phnum,
                string_table_address, &string_table_offset)
            || string_table_offset > (Elf64_Off)metadata.st_size) {
        result = ENOEXEC;
        goto complete;
    }
    struct runtime_linkage_search search = {0};
    char runpath[2049] = {0};
    if (has_path) {
        result = runtime_dynamic_string(descriptor, &metadata,
                string_table_offset, string_table_bytes, runpath_index,
                runpath, sizeof(runpath));
        if (result != 0) goto complete;
        for (char *entry = runpath; entry != NULL;) {
            char *separator = strchr(entry, ':');
            if (separator != NULL) *separator = '\0';
            char physical[PATH_MAX];
            int path_result =
                    runtime_linkage_directory(object, entry, physical);
            if (path_result == 0) {
                result = runtime_linkage_search_add(&search, physical);
                if (result != 0) goto complete;
                if (entry[0] == '/'
                        && !runtime_library_path_contains(output, physical)) {
                    size_t length = strlen(physical);
                    if (length > PATH_MAX - *output_length - 2) {
                        result = ENAMETOOLONG;
                        goto complete;
                    }
                    output[(*output_length)++] = ':';
                    memcpy(output + *output_length, physical, length + 1);
                    *output_length += length;
                }
            } else if (path_result != ENOENT) {
                result = path_result;
                goto complete;
            }
            entry = separator == NULL ? NULL : separator + 1;
        }
    }
    for (size_t index = 0; index < needed_count; index++) {
        char name[256];
        result = runtime_dynamic_string(descriptor, &metadata,
                string_table_offset, string_table_bytes, needed[index],
                name, sizeof(name));
        if (result != 0) goto complete;
        result = runtime_linkage_dependency(
                queue, &search, object, name);
        if (result != 0) goto complete;
    }

complete:
    {
        int saved_errno = errno;
        close(descriptor);
        errno = saved_errno;
    }
    return result;
}

/*
 * The Android loader resolves ELF search paths before the preload bridge can
 * translate them. Walk only the launched program's bounded, root-contained
 * DT_NEEDED graph and append reachable absolute RUNPATH/RPATH directories to
 * this process's explicit loader path. Relative $ORIGIN paths remain scoped to
 * dependency discovery and are never promoted globally.
 */
static int prepare_runtime_library_path(const char *program,
        char output[PATH_MAX]) {
    size_t output_length = strlen(trusted_library_path);
    if (output_length == 0 || output_length >= PATH_MAX) return EACCES;
    memcpy(output, trusted_library_path, output_length + 1);
    struct runtime_linkage_queue *queue = calloc(1, sizeof(*queue));
    if (queue == NULL) return ENOMEM;
    char resolved[PATH_MAX];
    int result;
    if (realpath(program, resolved) == NULL) {
        result = EACCES;
        goto complete;
    }
    if (!inside_trusted_root(resolved)) {
        /*
         * Verified runtime-tool aliases resolve into the APK's native library
         * directory rather than the shared Arch root. They already use the
         * sealed base path and cannot contribute root-private RUNPATHs.
         */
        result = 0;
        goto complete;
    }
    result = runtime_linkage_queue_add(queue, resolved);
    while (result == 0 && queue->next < queue->count) {
        result = runtime_collect_linkage_object(
                queue->objects[queue->next++],
                output, &output_length, queue);
    }

complete:
    free(queue);
    return result;
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

static int launch_runtime_executable(const char *name, const char *requested,
        const char *command, char *const arguments[],
        char *const environment[]) {
    complete_managed_maintenance_command(name);
    if (trusted_loader[0] == '\0' || trusted_library_path[0] == '\0') {
        errno = EACCES;
        return -1;
    }
    if (arguments == NULL || arguments[0] == NULL) {
        errno = EINVAL;
        return -1;
    }
    struct runtime_launch launch;
    int preparation =
            prepare_runtime_launch(name, requested, command, &launch);
    if (preparation != 0) {
        errno = preparation;
        return -1;
    }
    char library_path[PATH_MAX];
    preparation = prepare_runtime_library_path(launch.program, library_path);
    if (preparation != 0) {
        errno = preparation;
        return -1;
    }
    char *loader_arguments[4096];
    loader_arguments[0] = trusted_loader;
    loader_arguments[1] = "--library-path";
    loader_arguments[2] = library_path;
    loader_arguments[3] = "--argv0";
    loader_arguments[4] = launch.argv0;
    loader_arguments[5] = launch.program;
    size_t output_count = 6;
    if (launch.script_program) {
        if (launch.has_interpreter_argument) {
            loader_arguments[output_count++] = launch.interpreter_argument;
        }
        loader_arguments[output_count++] = launch.script;
    }
    for (size_t index = 1; arguments[index] != NULL; index++) {
        if (output_count >= 4095) {
            errno = E2BIG;
            return -1;
        }
        /*
         * Keep Linux argv in the logical namespace. The child inherits this
         * bridge and translates its own filesystem calls; exposing physical
         * /data paths here breaks component-walking tools such as mkdir -p
         * and leaks Android-private implementation details.
         */
        loader_arguments[output_count++] = arguments[index];
    }
    loader_arguments[output_count] = NULL;
    typedef int (*function_type)(const char *, char *const[], char *const[]);
    function_type real = (function_type)dlsym(RTLD_NEXT, "execve");
    if (real == NULL) {
        errno = ENOSYS;
        return -1;
    }
    char program_environment[PATH_MAX + 34];
    char *runtime_environment[4096];
    int environment_error = prepare_runtime_environment(environment,
            launch.program, program_environment, runtime_environment);
    if (environment_error != 0) {
        errno = environment_error;
        return -1;
    }
    real(trusted_loader, loader_arguments, runtime_environment);
    return -1;
}

static int launch_runtime_command(const char *name, char *const arguments[],
        char *const environment[]) {
    char command[PATH_MAX];
    if (!runtime_command(name, command)) return 1;
    return launch_runtime_executable(
            name, name, command, arguments, environment);
}

static int spawn_runtime_executable(pid_t *process, const char *name,
        const char *requested, const char *command,
        const posix_spawn_file_actions_t *file_actions,
        const posix_spawnattr_t *attributes, char *const arguments[],
        char *const environment[]) {
    if (trusted_loader[0] == '\0' || trusted_library_path[0] == '\0') {
        return EACCES;
    }
    if (arguments == NULL || arguments[0] == NULL) return EINVAL;
    struct runtime_launch launch;
    int preparation =
            prepare_runtime_launch(name, requested, command, &launch);
    if (preparation != 0) return preparation;
    char library_path[PATH_MAX];
    preparation = prepare_runtime_library_path(launch.program, library_path);
    if (preparation != 0) return preparation;
    char *loader_arguments[4096];
    loader_arguments[0] = trusted_loader;
    loader_arguments[1] = "--library-path";
    loader_arguments[2] = library_path;
    loader_arguments[3] = "--argv0";
    loader_arguments[4] = launch.argv0;
    loader_arguments[5] = launch.program;
    size_t output_count = 6;
    if (launch.script_program) {
        if (launch.has_interpreter_argument) {
            loader_arguments[output_count++] = launch.interpreter_argument;
        }
        loader_arguments[output_count++] = launch.script;
    }
    for (size_t index = 1; arguments[index] != NULL; index++) {
        if (output_count >= 4095) return E2BIG;
        loader_arguments[output_count++] = arguments[index];
    }
    loader_arguments[output_count] = NULL;
    typedef int (*function_type)(pid_t *, const char *,
            const posix_spawn_file_actions_t *, const posix_spawnattr_t *,
            char *const[], char *const[]);
    function_type real = (function_type)dlsym(RTLD_NEXT, "posix_spawn");
    if (real == NULL) return ENOSYS;
    char program_environment[PATH_MAX + 34];
    char *runtime_environment[4096];
    int environment_error = prepare_runtime_environment(environment,
            launch.program, program_environment, runtime_environment);
    if (environment_error != 0) return environment_error;
    return real(process, trusted_loader, file_actions, attributes,
            loader_arguments, runtime_environment);
}

static int spawn_runtime_command(pid_t *process, const char *name,
        const posix_spawn_file_actions_t *file_actions,
        const posix_spawnattr_t *attributes, char *const arguments[],
        char *const environment[]) {
    char command[PATH_MAX];
    if (!runtime_command(name, command)) return ENOENT;
    return spawn_runtime_executable(process, name, name, command, file_actions,
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
    return spawn_runtime_executable(process, name, path, command, file_actions,
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
        return spawn_runtime_executable(process, separator + 1, file, command,
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
    return launch_runtime_executable(
            separator + 1, file, command, arguments, environment);
}

static int launch_fakeroot_compat(const char *name, char *const arguments[],
        char *const environment[]) {
    if (!fake_chroot_active || strcmp(name, "fakeroot") != 0) return 1;
    if (arguments == NULL || arguments[0] == NULL
            || arguments[1] == NULL || strcmp(arguments[1], "--") != 0
            || arguments[2] == NULL) {
        return 1;
    }
    char *const *source = environment == NULL ? environ : environment;
    char *fakeroot_environment[4096];
    size_t output_count = 0;
    for (size_t index = 0; source[index] != NULL; index++) {
        if (strncmp(source[index], "FAKEROOTKEY=", 12) == 0) continue;
        if (output_count >= 4094) {
            errno = E2BIG;
            return -1;
        }
        fakeroot_environment[output_count++] = source[index];
    }
    /*
     * makepkg's private -F re-entry requires proof that fakeroot launched it.
     * The path bridge provides the UID/GID and metadata virtualization itself,
     * so retain only that bounded protocol marker instead of starting faked or
     * preloading libfakeroot ahead of path translation.
     */
    fakeroot_environment[output_count++] = "FAKEROOTKEY=archphene";
    fakeroot_environment[output_count] = NULL;
    return launch_runtime_file(
            arguments[2], arguments + 2, fakeroot_environment);
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
    int fakeroot = launch_fakeroot_compat(name, arguments, environment);
    if (fakeroot <= 0) return -1;
    char command[PATH_MAX];
    if (resolve_runtime_executable(path, command)) {
        return launch_runtime_executable(
                name, path, command, arguments, environment);
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
    int fakeroot = launch_fakeroot_compat(name, arguments, environ);
    if (fakeroot <= 0) return -1;
    char command[PATH_MAX];
    if (resolve_runtime_executable(path, command)) {
        return launch_runtime_executable(name, path, command, arguments, environ);
    }
    errno = ENOENT;
    return -1;
}

int execvp(const char *file, char *const arguments[]) {
    const char *name = strrchr(file, '/');
    name = name == NULL ? file : name + 1;
    int fakeroot = launch_fakeroot_compat(name, arguments, environ);
    if (fakeroot <= 0) return -1;
    int bridged = launch_runtime_file(file, arguments, environ);
    if (bridged <= 0) return -1;
    bridged = launch_android_system_command(file, arguments, environ);
    if (bridged <= 0) return -1;
    errno = ENOENT;
    return -1;
}

int execvpe(const char *file, char *const arguments[], char *const environment[]) {
    const char *name = strrchr(file, '/');
    name = name == NULL ? file : name + 1;
    int fakeroot = launch_fakeroot_compat(name, arguments, environment);
    if (fakeroot <= 0) return -1;
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
    char normalized[PATH_MAX];
    if (has_parent_component(path)) {
        /*
         * GCC and other normal Linux tools probe absolute, lexically
         * contained paths such as /usr/lib/gcc/<target>/<version>/../../..
         * with lstat/openat(O_NOFOLLOW). Those calls intentionally use this
         * non-following translator, so normalize the logical path before
         * placing it below the trusted root. normalize_logical_path rejects
         * any attempt to walk above that root. Relative parent paths remain
         * forbidden because the kernel would resolve them from a physical
         * Android working directory.
         */
        if (!fake_chroot_active || path[0] != '/'
                || !normalize_logical_path(path, normalized)) {
            errno = EACCES;
            return NULL;
        }
        bool allowed = strcmp(normalized, "/") == 0;
        for (size_t index = 0; !allowed
                && index < sizeof(fake_root_prefixes)
                        / sizeof(fake_root_prefixes[0]);
                index++) {
            size_t length = strlen(fake_root_prefixes[index]);
            if (strncmp(normalized, fake_root_prefixes[index], length) == 0
                    && (normalized[length] == '\0'
                        || normalized[length] == '/')) {
                allowed = true;
            }
        }
        size_t root_length = strlen(trusted_root);
        size_t path_length = strlen(normalized);
        if (!allowed || root_length == 0) {
            errno = EACCES;
            return NULL;
        }
        if (root_length + path_length + 1 > PATH_MAX) {
            errno = ENAMETOOLONG;
            return NULL;
        }
        memcpy(output, trusted_root, root_length);
        memcpy(output + root_length, normalized, path_length + 1);
        *translated = true;
        return output;
    }
    if (fake_chroot_active && inside_shared_memory_path(path)) {
        size_t root_length = strlen(trusted_shm_root);
        const char *suffix = path + sizeof("/dev/shm") - 1;
        size_t suffix_length = strlen(suffix);
        if (root_length == 0 || root_length + suffix_length + 1 > PATH_MAX) {
            errno = ENAMETOOLONG;
            return NULL;
        }
        memcpy(output, trusted_shm_root, root_length);
        memcpy(output + root_length, suffix, suffix_length + 1);
        *translated = true;
        return output;
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
    if (!allowed || trusted_root[0] == '\0') return path;
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

static const char *translate_follow_path(const char *path,
        char output[PATH_MAX], bool *translated) {
    if (!fake_chroot_active || path == NULL) {
        return translate_path(path, output, translated);
    }
    /*
     * Some native clients must receive a private physical path (notably
     * TMPDIR for Chromium's inline syscalls). Their libc fallbacks and GLib
     * helpers may subsequently pass that same path through a following API.
     * Treat an already root-contained physical path as final; interpreting it
     * as a logical absolute path would prefix trusted_root a second time and
     * turn a valid create into ENOENT.
     */
    size_t root_length = strlen(trusted_root);
    if (root_length > 0
            && strncmp(path, trusted_root, root_length) == 0
            && (path[root_length] == '\0' || path[root_length] == '/')) {
        *translated = false;
        return path;
    }
    if (path[0] == '\0') {
        *translated = false;
        return path;
    }
    if (inside_shared_memory_path(path)) {
        return translate_path(path, output, translated);
    }
    if (inside_kernel_filesystem(path)) {
        *translated = false;
        return path;
    }
    char logical[PATH_MAX];
    if (path[0] == '/') {
        size_t length = strlen(path);
        if (length >= sizeof(logical)) {
            errno = ENAMETOOLONG;
            return NULL;
        }
        memcpy(logical, path, length + 1);
    } else {
        typedef char *(*getcwd_type)(char *, size_t);
        getcwd_type real_getcwd = (getcwd_type)dlsym(RTLD_NEXT, "getcwd");
        char current[PATH_MAX];
        if (real_getcwd == NULL || real_getcwd(current, sizeof(current)) == NULL) {
            return NULL;
        }
        size_t root_length = strlen(trusted_root);
        if (strncmp(current, trusted_root, root_length) != 0
                || (current[root_length] != '\0'
                    && current[root_length] != '/')) {
            return translate_path(path, output, translated);
        }
        const char *relative = current + root_length;
        if (relative[0] == '\0') relative = "/";
        char combined[PATH_MAX];
        int length = snprintf(combined, sizeof(combined), "%s%s%s",
                relative, strcmp(relative, "/") == 0 ? "" : "/", path);
        if (length <= 0 || (size_t)length >= sizeof(combined)
                || !normalize_logical_path(combined, logical)) {
            errno = ENAMETOOLONG;
            return NULL;
        }
    }
    if (resolve_root_path(logical, output)) {
        *translated = true;
        return output;
    }
    return translate_path(path, output, translated);
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
    const char *target = translate_follow_path(path, buffer, &translated);
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

#define PROCESS_ENVIRONMENT_LIMIT (256U * 1024U)

static bool logical_runtime_program(
        const char *candidate, char output[PATH_MAX]) {
    if (candidate == NULL || candidate[0] != '/'
            || has_parent_component(candidate)
            || strchr(candidate, '\n') != NULL) {
        return false;
    }
    typedef char *(*realpath_type)(const char *, char *);
    typedef int (*stat_type)(const char *, struct stat *);
    realpath_type real_realpath =
            (realpath_type)dlsym(RTLD_NEXT, "realpath");
    stat_type real_stat = (stat_type)dlsym(RTLD_NEXT, "stat");
    char resolved[PATH_MAX];
    struct stat metadata;
    if (real_realpath == NULL || real_stat == NULL
            || real_realpath(candidate, resolved) == NULL
            || real_stat(resolved, &metadata) != 0
            || !S_ISREG(metadata.st_mode)
            || (metadata.st_mode & (S_IWGRP | S_IWOTH)) != 0) {
        return false;
    }
    size_t root_length = strlen(trusted_root);
    const char *logical = resolved;
    if (root_length > 0
            && strncmp(resolved, trusted_root, root_length) == 0
            && (resolved[root_length] == '\0'
                || resolved[root_length] == '/')) {
        logical = resolved + root_length;
        if (logical[0] == '\0') logical = "/";
    }
    size_t length = strlen(logical);
    if (length == 0 || length >= PATH_MAX) return false;
    memcpy(output, logical, length + 1);
    return true;
}

static bool managed_process_executable(
        const char *path, char output[PATH_MAX]) {
    static const char proc_prefix[] = "/proc/";
    static const char exe_suffix[] = "/exe";
    if (trusted_root[0] == '\0'
            || path == NULL
            || strncmp(path, proc_prefix, sizeof(proc_prefix) - 1) != 0) {
        return false;
    }
    const char *process = path + sizeof(proc_prefix) - 1;
    const char *cursor = process;
    while (*cursor >= '0' && *cursor <= '9') cursor++;
    if (cursor == process || strcmp(cursor, exe_suffix) != 0) return false;
    size_t process_length = (size_t)(cursor - process);
    char environment_path[64];
    int written = snprintf(environment_path, sizeof(environment_path),
            "/proc/%.*s/environ", (int)process_length, process);
    if (written <= 0 || (size_t)written >= sizeof(environment_path)) {
        return false;
    }

    typedef int (*open_type)(const char *, int, ...);
    open_type real_open = (open_type)dlsym(RTLD_NEXT, "open");
    if (real_open == NULL) return false;
    int descriptor =
            real_open(environment_path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (descriptor < 0) return false;

    static const char variable[] = RUNTIME_PROGRAM_ENVIRONMENT;
    char entry[PATH_MAX + sizeof(variable)];
    size_t entry_length = 0;
    size_t total = 0;
    bool overflow = false;
    bool found = false;
    char chunk[4096];
    while (total < PROCESS_ENVIRONMENT_LIMIT && !found) {
        size_t remaining = PROCESS_ENVIRONMENT_LIMIT - total;
        size_t capacity = remaining < sizeof(chunk) ? remaining : sizeof(chunk);
        ssize_t count;
        do {
            count = read(descriptor, chunk, capacity);
        } while (count < 0 && errno == EINTR);
        if (count <= 0) break;
        total += (size_t)count;
        for (ssize_t index = 0; index < count; index++) {
            unsigned char value = (unsigned char)chunk[index];
            if (value == '\0') {
                if (!overflow
                        && entry_length >= sizeof(variable) - 1
                        && memcmp(entry, variable, sizeof(variable) - 1) == 0) {
                    entry[entry_length] = '\0';
                    found = logical_runtime_program(
                            entry + sizeof(variable) - 1, output);
                }
                entry_length = 0;
                overflow = false;
            } else if (!overflow) {
                if (entry_length + 1 < sizeof(entry)) {
                    entry[entry_length++] = (char)value;
                } else {
                    overflow = true;
                }
            }
        }
    }
    int saved_errno = errno;
    close(descriptor);
    errno = saved_errno;
    return found;
}

#define PROCESS_COMMANDLINE_LIMIT (64U * 1024U)

static bool managed_process_commandline_path(const char *path) {
    static const char prefix[] = "/proc/";
    if (path == NULL || strncmp(path, prefix, sizeof(prefix) - 1) != 0) {
        return false;
    }
    const char *cursor = path + sizeof(prefix) - 1;
    const char *process = cursor;
    while (*cursor >= '0' && *cursor <= '9') cursor++;
    return cursor != process && strcmp(cursor, "/cmdline") == 0;
}

__attribute__((noinline))
static int open_managed_process_commandline(const char *path, int flags) {
    static const char prefix[] = "/proc/";
    const char *process = path + sizeof(prefix) - 1;
    const char *cursor = process;
    while (*cursor >= '0' && *cursor <= '9') cursor++;
    size_t process_length = (size_t)(cursor - process);
    char executable_path[64];
    int written = snprintf(executable_path, sizeof(executable_path),
            "/proc/%.*s/exe", (int)process_length, process);
    char logical_program[PATH_MAX];
    if (written <= 0 || (size_t)written >= sizeof(executable_path)
            || !managed_process_executable(
                executable_path, logical_program)) {
        return -1;
    }

    typedef int (*open_type)(const char *, int, ...);
    open_type real_open = (open_type)dlsym(RTLD_NEXT, "open");
    if (real_open == NULL) return -1;
    int source = real_open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (source < 0) return -1;
    char commandline[PROCESS_COMMANDLINE_LIMIT];
    size_t length = 0;
    while (length < sizeof(commandline)) {
        ssize_t count;
        do {
            count = read(
                    source, commandline + length,
                    sizeof(commandline) - length);
        } while (count < 0 && errno == EINTR);
        if (count <= 0) break;
        length += (size_t)count;
    }
    int saved_errno = errno;
    close(source);
    errno = saved_errno;
    if (length == 0 || length == sizeof(commandline)
            || commandline[length - 1] != '\0') {
        return -1;
    }

    size_t token_offsets[7];
    size_t token_count = 0;
    size_t offset = 0;
    while (offset < length && token_count < 7) {
        token_offsets[token_count++] = offset;
        while (offset < length && commandline[offset] != '\0') offset++;
        if (offset == length) return -1;
        offset++;
    }
    if (token_count < 6
            || trusted_loader[0] == '\0'
            || strcmp(commandline + token_offsets[0], trusted_loader) != 0
            || strcmp(commandline + token_offsets[1], "--library-path") != 0
            || commandline[token_offsets[2]] == '\0'
            || strcmp(commandline + token_offsets[3], "--argv0") != 0
            || commandline[token_offsets[4]] == '\0'
            || commandline[token_offsets[5]] == '\0') {
        return -1;
    }
    char command_program[PATH_MAX];
    if (!logical_runtime_program(
                commandline + token_offsets[5], command_program)
            || strcmp(command_program, logical_program) != 0) {
        return -1;
    }
    const char *name = strrchr(logical_program, '/');
    name = name == NULL ? logical_program : name + 1;
    const char *argv0 = commandline + token_offsets[4];
    if (name[0] == '\0' || strcmp(argv0, name) != 0) return -1;

    size_t argv0_length = strlen(argv0) + 1;
    char argv0_value[NAME_MAX + 1];
    if (argv0_length > sizeof(argv0_value)) return -1;
    memcpy(argv0_value, argv0, argv0_length);
    size_t tail_offset = token_count >= 7 ? token_offsets[6] : length;
    size_t tail_length = length - tail_offset;
    if (argv0_length > sizeof(commandline) - tail_length) return -1;
    memmove(commandline + argv0_length,
            commandline + tail_offset, tail_length);
    memcpy(commandline, argv0_value, argv0_length);
    size_t output_length = argv0_length + tail_length;

    if (output_length > 32U * 1024U) return -1;
    int descriptors[2];
    if (pipe2(
                descriptors,
                flags & (O_CLOEXEC | O_NONBLOCK))
            != 0) {
        return -1;
    }
    int pipe_capacity = fcntl(descriptors[1], F_GETPIPE_SZ);
    if (pipe_capacity < 0 || output_length > (size_t)pipe_capacity) {
        saved_errno = pipe_capacity < 0 ? errno : E2BIG;
        close(descriptors[0]);
        close(descriptors[1]);
        errno = saved_errno;
        return -1;
    }
    size_t written_bytes = 0;
    while (written_bytes < output_length) {
        ssize_t count;
        do {
            count = write(
                    descriptors[1], commandline + written_bytes,
                    output_length - written_bytes);
        } while (count < 0 && errno == EINTR);
        if (count <= 0) {
            saved_errno = errno;
            close(descriptors[0]);
            close(descriptors[1]);
            errno = saved_errno;
            return -1;
        }
        written_bytes += (size_t)count;
    }
    close(descriptors[1]);
    return descriptors[0];
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

char *realpath(const char *path, char *resolved_path) {
    typedef char *(*function_type)(const char *, char *);
    const char *program = trusted_linux_program_path();
    char managed_program[PATH_MAX];
    if (path != NULL && managed_process_executable(path, managed_program)) {
        size_t length = strlen(managed_program);
        char *output = resolved_path;
        if (output == NULL) {
            output = malloc(length + 1);
            if (output == NULL) return NULL;
        }
        memcpy(output, managed_program, length + 1);
        return output;
    }
    if (path != NULL && strcmp(path, "/proc/self/exe") == 0
            && program != NULL) {
        size_t length = strlen(program);
        char *output = resolved_path;
        if (output == NULL) {
            output = malloc(length + 1);
            if (output == NULL) return NULL;
        }
        memcpy(output, program, length + 1);
        return output;
    }
    function_type real = (function_type)dlsym(RTLD_NEXT, "realpath");
    bool translated;
    char buffer[PATH_MAX];
    const char *target = translate_follow_path(path, buffer, &translated);
    if (target == NULL) return NULL;
    if (real == NULL) {
        errno = ENOSYS;
        return NULL;
    }
    return linux_cwd(real(target, resolved_path));
}

char *__realpath_chk(const char *path, char *resolved_path,
        size_t resolved_size) {
    typedef char *(*function_type)(const char *, char *, size_t);
    const char *program = trusted_linux_program_path();
    char managed_program[PATH_MAX];
    if (path != NULL && managed_process_executable(path, managed_program)) {
        size_t length = strlen(managed_program);
        if (resolved_path == NULL) {
            char *output = malloc(length + 1);
            if (output == NULL) return NULL;
            memcpy(output, managed_program, length + 1);
            return output;
        }
        if (length >= resolved_size) {
            errno = ENAMETOOLONG;
            return NULL;
        }
        memcpy(resolved_path, managed_program, length + 1);
        return resolved_path;
    }
    if (path != NULL && strcmp(path, "/proc/self/exe") == 0
            && program != NULL) {
        size_t length = strlen(program);
        if (resolved_path == NULL) {
            char *output = malloc(length + 1);
            if (output == NULL) return NULL;
            memcpy(output, program, length + 1);
            return output;
        }
        if (length >= resolved_size) {
            errno = ENAMETOOLONG;
            return NULL;
        }
        memcpy(resolved_path, program, length + 1);
        return resolved_path;
    }
    function_type real =
            (function_type)dlsym(RTLD_NEXT, "__realpath_chk");
    bool translated;
    char buffer[PATH_MAX];
    const char *target = translate_follow_path(path, buffer, &translated);
    if (target == NULL) return NULL;
    if (real == NULL) {
        errno = ENOSYS;
        return NULL;
    }
    return linux_cwd(real(target, resolved_path, resolved_size));
}

char *canonicalize_file_name(const char *path) {
    return realpath(path, NULL);
}

static void *dlopen_impl(const char *path, int flags) {
    typedef void *(*function_type)(const char *, int);
    function_type real = (function_type)dlsym(RTLD_NEXT, "dlopen");
    if (real == NULL) {
        errno = ENOSYS;
        return NULL;
    }
    if (path == NULL) return real(NULL, flags);
    bool translated;
    char buffer[PATH_MAX];
    const char *target = translate_follow_path(path, buffer, &translated);
    if (target == NULL) return NULL;
    return real(target, flags);
}

void *dlopen(const char *path, int flags) {
    return dlopen_impl(path, flags);
}

static void *dlmopen_impl(Lmid_t namespace, const char *path, int flags) {
    typedef void *(*function_type)(Lmid_t, const char *, int);
    function_type real = (function_type)dlsym(RTLD_NEXT, "dlmopen");
    if (real == NULL) {
        errno = ENOSYS;
        return NULL;
    }
    if (path == NULL) return real(namespace, NULL, flags);
    bool translated;
    char buffer[PATH_MAX];
    const char *target = translate_follow_path(path, buffer, &translated);
    if (target == NULL) return NULL;
    return real(namespace, target, flags);
}

void *dlmopen(Lmid_t namespace, const char *path, int flags) {
    return dlmopen_impl(namespace, path, flags);
}

#if defined(__aarch64__)
void *archphene_dlopen_glibc_2_34(const char *path, int flags) {
    return dlopen_impl(path, flags);
}

void *archphene_dlmopen_glibc_2_34(
        Lmid_t namespace, const char *path, int flags) {
    return dlmopen_impl(namespace, path, flags);
}

__asm__(".symver archphene_dlopen_glibc_2_34,dlopen@GLIBC_2.34, remove");
__asm__(".symver archphene_dlmopen_glibc_2_34,dlmopen@GLIBC_2.34, remove");
#endif

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
    if (!has_mode
            && (flags & O_ACCMODE) == O_RDONLY
            && (flags & (O_DIRECTORY | O_PATH)) == 0
            && managed_process_commandline_path(path)) {
        int managed = open_managed_process_commandline(path, flags);
        if (managed >= 0) return managed;
    }
    typedef int (*function_type)(const char *, int, ...);
    function_type real = RESOLVE(function_type, symbol);
    bool translated;
    char buffer[PATH_MAX];
    const char *target = translate_follow_path(path, buffer, &translated);
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

int creat(const char *path, mode_t mode) {
    return open(path, O_WRONLY | O_CREAT | O_TRUNC, mode);
}

int creat64(const char *path, mode_t mode) {
    return open64(path, O_WRONLY | O_CREAT | O_TRUNC, mode);
}

static int openat_impl(const char *symbol, int directory, const char *path, int flags,
        mode_t mode, bool has_mode) {
    if (!has_mode
            && (flags & O_ACCMODE) == O_RDONLY
            && (flags & (O_DIRECTORY | O_PATH)) == 0
            && managed_process_commandline_path(path)) {
        int managed = open_managed_process_commandline(path, flags);
        if (managed >= 0) return managed;
    }
    typedef int (*function_type)(int, const char *, int, ...);
    function_type real = RESOLVE(function_type, symbol);
    bool translated;
    char buffer[PATH_MAX];
    const char *target = translate_at_path(directory, path, buffer, &translated,
            (flags & O_NOFOLLOW) == 0);
    if (target == NULL) return -1;
    REQUIRE_REAL(real);
    if (translated && !fake_chroot_active && write_flags(flags)) {
        errno = EROFS;
        return -1;
    }
    return has_mode
            ? real(directory, target, flags, mode)
            : real(directory, target, flags);
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

static int finish_temporary_file(int descriptor, char *logical_template,
        const char *physical_template, size_t logical_length, bool translated) {
    if (descriptor < 0 || !translated) return descriptor;
    size_t root_length = strlen(trusted_root);
    size_t physical_length = strlen(physical_template);
    if (root_length == 0 || physical_length != root_length + logical_length
            || strncmp(physical_template, trusted_root, root_length) != 0
            || physical_template[root_length] != '/') {
        int saved_errno = errno == 0 ? EIO : errno;
        close(descriptor);
        unlink(physical_template);
        errno = saved_errno;
        return -1;
    }
    memcpy(logical_template, physical_template + root_length, logical_length + 1);
    return descriptor;
}

static char *finish_temporary_directory(char *result, char *logical_template,
        const char *physical_template, size_t logical_length, bool translated) {
    if (result == NULL || !translated) return result;
    size_t root_length = strlen(trusted_root);
    size_t physical_length = strlen(physical_template);
    if (root_length == 0 || physical_length != root_length + logical_length
            || strncmp(physical_template, trusted_root, root_length) != 0
            || physical_template[root_length] != '/') {
        int saved_errno = errno == 0 ? EIO : errno;
        (void)rmdir(physical_template);
        errno = saved_errno;
        return NULL;
    }
    memcpy(logical_template, physical_template + root_length, logical_length + 1);
    return logical_template;
}

char *mkdtemp(char *template) {
    typedef char *(*function_type)(char *);
    function_type real = RESOLVE(function_type, "mkdtemp");
    bool translated;
    char buffer[PATH_MAX];
    size_t logical_length = strlen(template);
    const char *target = translate_path(template, buffer, &translated);
    if (target == NULL) return NULL;
    if (real == NULL) {
        errno = ENOSYS;
        return NULL;
    }
    return finish_temporary_directory(real((char *)target), template, target,
            logical_length, translated);
}

int mkstemp(char *template) {
    typedef int (*function_type)(char *);
    function_type real = RESOLVE(function_type, "mkstemp");
    bool translated;
    char buffer[PATH_MAX];
    size_t logical_length = strlen(template);
    const char *target = translate_path(template, buffer, &translated);
    if (target == NULL) return -1;
    REQUIRE_REAL(real);
    return finish_temporary_file(
            real((char *)target), template, target, logical_length, translated);
}

int mkstemp64(char *template) {
    typedef int (*function_type)(char *);
    function_type real = RESOLVE(function_type, "mkstemp64");
    bool translated;
    char buffer[PATH_MAX];
    size_t logical_length = strlen(template);
    const char *target = translate_path(template, buffer, &translated);
    if (target == NULL) return -1;
    REQUIRE_REAL(real);
    return finish_temporary_file(
            real((char *)target), template, target, logical_length, translated);
}

int mkostemp(char *template, int flags) {
    typedef int (*function_type)(char *, int);
    function_type real = RESOLVE(function_type, "mkostemp");
    bool translated;
    char buffer[PATH_MAX];
    size_t logical_length = strlen(template);
    const char *target = translate_path(template, buffer, &translated);
    if (target == NULL) return -1;
    REQUIRE_REAL(real);
    return finish_temporary_file(
            real((char *)target, flags), template, target, logical_length, translated);
}

int mkostemp64(char *template, int flags) {
    typedef int (*function_type)(char *, int);
    function_type real = RESOLVE(function_type, "mkostemp64");
    bool translated;
    char buffer[PATH_MAX];
    size_t logical_length = strlen(template);
    const char *target = translate_path(template, buffer, &translated);
    if (target == NULL) return -1;
    REQUIRE_REAL(real);
    return finish_temporary_file(
            real((char *)target, flags), template, target, logical_length, translated);
}

int mkstemps(char *template, int suffix_length) {
    typedef int (*function_type)(char *, int);
    function_type real = RESOLVE(function_type, "mkstemps");
    bool translated;
    char buffer[PATH_MAX];
    size_t logical_length = strlen(template);
    const char *target = translate_path(template, buffer, &translated);
    if (target == NULL) return -1;
    REQUIRE_REAL(real);
    return finish_temporary_file(real((char *)target, suffix_length), template,
            target, logical_length, translated);
}

int mkstemps64(char *template, int suffix_length) {
    typedef int (*function_type)(char *, int);
    function_type real = RESOLVE(function_type, "mkstemps64");
    bool translated;
    char buffer[PATH_MAX];
    size_t logical_length = strlen(template);
    const char *target = translate_path(template, buffer, &translated);
    if (target == NULL) return -1;
    REQUIRE_REAL(real);
    return finish_temporary_file(real((char *)target, suffix_length), template,
            target, logical_length, translated);
}

int mkostemps(char *template, int suffix_length, int flags) {
    typedef int (*function_type)(char *, int, int);
    function_type real = RESOLVE(function_type, "mkostemps");
    bool translated;
    char buffer[PATH_MAX];
    size_t logical_length = strlen(template);
    const char *target = translate_path(template, buffer, &translated);
    if (target == NULL) return -1;
    REQUIRE_REAL(real);
    return finish_temporary_file(real((char *)target, suffix_length, flags),
            template, target, logical_length, translated);
}

int mkostemps64(char *template, int suffix_length, int flags) {
    typedef int (*function_type)(char *, int, int);
    function_type real = RESOLVE(function_type, "mkostemps64");
    bool translated;
    char buffer[PATH_MAX];
    size_t logical_length = strlen(template);
    const char *target = translate_path(template, buffer, &translated);
    if (target == NULL) return -1;
    REQUIRE_REAL(real);
    return finish_temporary_file(real((char *)target, suffix_length, flags),
            template, target, logical_length, translated);
}

static FILE *fopen_impl(const char *symbol, const char *path, const char *mode) {
    if (mode != NULL
            && mode[0] == 'r'
            && strchr(mode, '+') == NULL
            && managed_process_commandline_path(path)) {
        int descriptor =
                open_managed_process_commandline(path, O_RDONLY | O_CLOEXEC);
        if (descriptor >= 0) {
            typedef FILE *(*fdopen_type)(int, const char *);
            fdopen_type real_fdopen =
                    (fdopen_type)dlsym(RTLD_NEXT, "fdopen");
            if (real_fdopen != NULL) {
                FILE *stream = real_fdopen(descriptor, mode);
                if (stream != NULL) return stream;
            }
            int saved_errno = errno;
            close(descriptor);
            errno = saved_errno;
        }
    }
    typedef FILE *(*function_type)(const char *, const char *);
    function_type real = RESOLVE(function_type, symbol);
    bool translated;
    char buffer[PATH_MAX];
    const char *target = translate_follow_path(path, buffer, &translated);
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
    const char *target = translate_follow_path(path, buffer, &translated);
    if (target == NULL) return NULL;
    if (real == NULL) {
        errno = ENOSYS;
        return NULL;
    }
    return real(target);
}

struct dirent *readdir(DIR *directory) {
    typedef struct dirent *(*function_type)(DIR *);
    function_type real = RESOLVE(function_type, "readdir");
    if (real == NULL) {
        errno = ENOSYS;
        return NULL;
    }
    bool filter = proc_root_descriptor(dirfd(directory));
    struct dirent *entry;
    do {
        entry = real(directory);
    } while (filter && entry != NULL
            && !visible_proc_entry(dirfd(directory), entry->d_name));
    return entry;
}

struct dirent64 *readdir64(DIR *directory) {
    typedef struct dirent64 *(*function_type)(DIR *);
    function_type real = RESOLVE(function_type, "readdir64");
    if (real == NULL) {
        errno = ENOSYS;
        return NULL;
    }
    bool filter = proc_root_descriptor(dirfd(directory));
    struct dirent64 *entry;
    do {
        entry = real(directory);
    } while (filter && entry != NULL
            && !visible_proc_entry(dirfd(directory), entry->d_name));
    return entry;
}

static _Thread_local int proc_scandir_descriptor = -1;
static _Thread_local int (*proc_scandir_filter)(const struct dirent *);
static _Thread_local int (*proc_scandir64_filter)(const struct dirent64 *);

static int filtered_proc_scandir_entry(const struct dirent *entry) {
    return visible_proc_entry(proc_scandir_descriptor, entry->d_name)
            && (proc_scandir_filter == NULL || proc_scandir_filter(entry));
}

static int filtered_proc_scandir64_entry(const struct dirent64 *entry) {
    return visible_proc_entry(proc_scandir_descriptor, entry->d_name)
            && (proc_scandir64_filter == NULL || proc_scandir64_filter(entry));
}

static int open_proc_root(void) {
    typedef int (*open_type)(const char *, int, ...);
    open_type real_open = RESOLVE(open_type, "open");
    if (real_open == NULL) {
        errno = ENOSYS;
        return -1;
    }
    return real_open("/proc", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
}

int scandir(const char *path, struct dirent ***entries,
        int (*filter)(const struct dirent *),
        int (*compare)(const struct dirent **, const struct dirent **)) {
    typedef int (*function_type)(const char *, struct dirent ***,
            int (*)(const struct dirent *),
            int (*)(const struct dirent **, const struct dirent **));
    function_type real = RESOLVE(function_type, "scandir");
    bool translated;
    char buffer[PATH_MAX];
    const char *target = translate_follow_path(path, buffer, &translated);
    if (target == NULL) return -1;
    REQUIRE_REAL(real);
    if (!proc_root_path(target)) return real(target, entries, filter, compare);
    int descriptor = open_proc_root();
    if (descriptor < 0) return -1;
    int previous_descriptor = proc_scandir_descriptor;
    int (*previous_filter)(const struct dirent *) = proc_scandir_filter;
    proc_scandir_descriptor = descriptor;
    proc_scandir_filter = filter;
    int result = real(target, entries, filtered_proc_scandir_entry, compare);
    int saved_errno = errno;
    proc_scandir_descriptor = previous_descriptor;
    proc_scandir_filter = previous_filter;
    close(descriptor);
    errno = saved_errno;
    return result;
}

int scandir64(const char *path, struct dirent64 ***entries,
        int (*filter)(const struct dirent64 *),
        int (*compare)(const struct dirent64 **, const struct dirent64 **)) {
    typedef int (*function_type)(const char *, struct dirent64 ***,
            int (*)(const struct dirent64 *),
            int (*)(const struct dirent64 **, const struct dirent64 **));
    function_type real = RESOLVE(function_type, "scandir64");
    bool translated;
    char buffer[PATH_MAX];
    const char *target = translate_follow_path(path, buffer, &translated);
    if (target == NULL) return -1;
    REQUIRE_REAL(real);
    if (!proc_root_path(target)) return real(target, entries, filter, compare);
    int descriptor = open_proc_root();
    if (descriptor < 0) return -1;
    int previous_descriptor = proc_scandir_descriptor;
    int (*previous_filter)(const struct dirent64 *) = proc_scandir64_filter;
    proc_scandir_descriptor = descriptor;
    proc_scandir64_filter = filter;
    int result = real(target, entries, filtered_proc_scandir64_entry, compare);
    int saved_errno = errno;
    proc_scandir_descriptor = previous_descriptor;
    proc_scandir64_filter = previous_filter;
    close(descriptor);
    errno = saved_errno;
    return result;
}

int inotify_add_watch(int descriptor, const char *path, uint32_t mask) {
    typedef int (*function_type)(int, const char *, uint32_t);
    function_type real = RESOLVE(function_type, "inotify_add_watch");
    bool translated;
    char buffer[PATH_MAX];
    const char *target = translate_follow_path(path, buffer, &translated);
    if (target == NULL) return -1;
    REQUIRE_REAL(real);
    return real(descriptor, target, mask);
}

static void normalize_stat_identity(struct stat *value) {
    if (fake_chroot_active && value != NULL) {
        value->st_uid = root_identity_active ? 0 : ARCHPHENE_USER_ID;
        value->st_gid = root_identity_active ? 0 : ARCHPHENE_USER_ID;
    }
}

static void normalize_stat64_identity(struct stat64 *value) {
    if (fake_chroot_active && value != NULL) {
        value->st_uid = root_identity_active ? 0 : ARCHPHENE_USER_ID;
        value->st_gid = root_identity_active ? 0 : ARCHPHENE_USER_ID;
    }
}

#define PATH_CALL(name, value_type, normalizer, follow) \
    int name(const char *path, value_type *value) { \
        typedef int (*function_type)(const char *, value_type *); \
        function_type real = RESOLVE(function_type, #name); \
        bool translated; \
        char buffer[PATH_MAX]; \
        const char *target = follow \
                ? translate_follow_path(path, buffer, &translated) \
                : translate_path(path, buffer, &translated); \
        if (target == NULL) return -1; \
        REQUIRE_REAL(real); \
        int result = real(target, value); \
        if (result == 0) normalizer(value); \
        return result; \
    }

PATH_CALL(stat, struct stat, normalize_stat_identity, true)
PATH_CALL(stat64, struct stat64, normalize_stat64_identity, true)
PATH_CALL(lstat, struct stat, normalize_stat_identity, false)
PATH_CALL(lstat64, struct stat64, normalize_stat64_identity, false)

#define XSTAT_CALL(name, value_type, normalizer, follow) \
    int name(int version, const char *path, value_type *value) { \
        typedef int (*function_type)(int, const char *, value_type *); \
        function_type real = RESOLVE(function_type, #name); \
        bool translated; \
        char buffer[PATH_MAX]; \
        const char *target = follow \
                ? translate_follow_path(path, buffer, &translated) \
                : translate_path(path, buffer, &translated); \
        if (target == NULL) return -1; \
        REQUIRE_REAL(real); \
        int result = real(version, target, value); \
        if (result == 0) normalizer(value); \
        return result; \
    }

XSTAT_CALL(__xstat, struct stat, normalize_stat_identity, true)
XSTAT_CALL(__xstat64, struct stat64, normalize_stat64_identity, true)
XSTAT_CALL(__lxstat, struct stat, normalize_stat_identity, false)
XSTAT_CALL(__lxstat64, struct stat64, normalize_stat64_identity, false)

int fstat(int descriptor, struct stat *value) {
    typedef int (*function_type)(int, struct stat *);
    function_type real = RESOLVE(function_type, "fstat");
    REQUIRE_REAL(real);
    int result = real(descriptor, value);
    if (result == 0) normalize_stat_identity(value);
    return result;
}

int fstat64(int descriptor, struct stat64 *value) {
    typedef int (*function_type)(int, struct stat64 *);
    function_type real = RESOLVE(function_type, "fstat64");
    REQUIRE_REAL(real);
    int result = real(descriptor, value);
    if (result == 0) normalize_stat64_identity(value);
    return result;
}

int __fxstat(int version, int descriptor, struct stat *value) {
    typedef int (*function_type)(int, int, struct stat *);
    function_type real = RESOLVE(function_type, "__fxstat");
    REQUIRE_REAL(real);
    int result = real(version, descriptor, value);
    if (result == 0) normalize_stat_identity(value);
    return result;
}

int __fxstat64(int version, int descriptor, struct stat64 *value) {
    typedef int (*function_type)(int, int, struct stat64 *);
    function_type real = RESOLVE(function_type, "__fxstat64");
    REQUIRE_REAL(real);
    int result = real(version, descriptor, value);
    if (result == 0) normalize_stat64_identity(value);
    return result;
}

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
    const char *target = translate_follow_path(path, buffer, &translated);
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
    const char *target = translate_follow_path(path, buffer, &translated);
    if (target == NULL) return -1;
    REQUIRE_REAL(real);
    if (translated && !fake_chroot_active && (mode & W_OK) != 0) {
        errno = EROFS;
        return -1;
    }
    return real(target, mode);
}

static const char *translated_xattr_name(
        const char *target, const char *name, bool write_access) {
    static const char capability[] = "security.capability";
    static const char virtual_capability[] =
            "user.archphene.security.capability";
    if (!fake_chroot_active || !inside_trusted_root(target)
            || name == NULL || strcmp(name, capability) != 0) {
        return name;
    }
    if (write_access && !root_identity_active) {
        errno = EPERM;
        return NULL;
    }
    /*
     * Android app UIDs cannot own kernel file capabilities, and Android would
     * not honor them when launching through the managed loader in any case.
     * Preserve the signed Arch metadata in a private user xattr so setcap,
     * getcap, and package scriptlets retain normal fake-root semantics without
     * granting an Android capability or weakening the app sandbox.
     */
    return virtual_capability;
}

static const char *descriptor_xattr_name(
        int descriptor, const char *name, bool write_access) {
    typedef ssize_t (*readlink_type)(const char *, char *, size_t);
    readlink_type real_readlink = (readlink_type)dlsym(RTLD_NEXT, "readlink");
    if (real_readlink == NULL) return name;
    char link[64];
    int written = snprintf(
            link, sizeof(link), "/proc/self/fd/%d", descriptor);
    if (written <= 0 || (size_t)written >= sizeof(link)) return name;
    char target[PATH_MAX];
    ssize_t length = real_readlink(link, target, sizeof(target) - 1);
    if (length <= 0 || (size_t)length >= sizeof(target)) return name;
    target[length] = '\0';
    return translated_xattr_name(target, name, write_access);
}

ssize_t getxattr(const char *path, const char *name, void *value, size_t size) {
    typedef ssize_t (*function_type)(const char *, const char *, void *, size_t);
    function_type real = RESOLVE(function_type, "getxattr");
    bool translated;
    char buffer[PATH_MAX];
    const char *target = translate_follow_path(path, buffer, &translated);
    if (target == NULL) return -1;
    name = translated_xattr_name(target, name, false);
    if (name == NULL) return -1;
    REQUIRE_REAL(real);
    return real(target, name, value, size);
}

ssize_t lgetxattr(const char *path, const char *name, void *value, size_t size) {
    typedef ssize_t (*function_type)(const char *, const char *, void *, size_t);
    function_type real = RESOLVE(function_type, "lgetxattr");
    bool translated;
    char buffer[PATH_MAX];
    const char *target = translate_path(path, buffer, &translated);
    if (target == NULL) return -1;
    name = translated_xattr_name(target, name, false);
    if (name == NULL) return -1;
    REQUIRE_REAL(real);
    return real(target, name, value, size);
}

ssize_t fgetxattr(int descriptor, const char *name, void *value, size_t size) {
    typedef ssize_t (*function_type)(int, const char *, void *, size_t);
    function_type real = RESOLVE(function_type, "fgetxattr");
    name = descriptor_xattr_name(descriptor, name, false);
    if (name == NULL) return -1;
    REQUIRE_REAL(real);
    return real(descriptor, name, value, size);
}

ssize_t listxattr(const char *path, char *list, size_t size) {
    typedef ssize_t (*function_type)(const char *, char *, size_t);
    function_type real = RESOLVE(function_type, "listxattr");
    bool translated;
    char buffer[PATH_MAX];
    const char *target = translate_follow_path(path, buffer, &translated);
    if (target == NULL) return -1;
    REQUIRE_REAL(real);
    return real(target, list, size);
}

ssize_t llistxattr(const char *path, char *list, size_t size) {
    typedef ssize_t (*function_type)(const char *, char *, size_t);
    function_type real = RESOLVE(function_type, "llistxattr");
    bool translated;
    char buffer[PATH_MAX];
    const char *target = translate_path(path, buffer, &translated);
    if (target == NULL) return -1;
    REQUIRE_REAL(real);
    return real(target, list, size);
}

int setxattr(const char *path, const char *name, const void *value,
        size_t size, int flags) {
    typedef int (*function_type)(
            const char *, const char *, const void *, size_t, int);
    function_type real = RESOLVE(function_type, "setxattr");
    bool translated;
    char buffer[PATH_MAX];
    const char *target = translate_follow_path(path, buffer, &translated);
    if (target == NULL) return -1;
    name = translated_xattr_name(target, name, true);
    if (name == NULL) return -1;
    REQUIRE_REAL(real);
    if (translated && !fake_chroot_active) {
        errno = EROFS;
        return -1;
    }
    return real(target, name, value, size, flags);
}

int lsetxattr(const char *path, const char *name, const void *value,
        size_t size, int flags) {
    typedef int (*function_type)(
            const char *, const char *, const void *, size_t, int);
    function_type real = RESOLVE(function_type, "lsetxattr");
    bool translated;
    char buffer[PATH_MAX];
    const char *target = translate_path(path, buffer, &translated);
    if (target == NULL) return -1;
    name = translated_xattr_name(target, name, true);
    if (name == NULL) return -1;
    REQUIRE_REAL(real);
    if (translated && !fake_chroot_active) {
        errno = EROFS;
        return -1;
    }
    return real(target, name, value, size, flags);
}

int fsetxattr(int descriptor, const char *name, const void *value,
        size_t size, int flags) {
    typedef int (*function_type)(int, const char *, const void *, size_t, int);
    function_type real = RESOLVE(function_type, "fsetxattr");
    name = descriptor_xattr_name(descriptor, name, true);
    if (name == NULL) return -1;
    REQUIRE_REAL(real);
    return real(descriptor, name, value, size, flags);
}

int removexattr(const char *path, const char *name) {
    typedef int (*function_type)(const char *, const char *);
    function_type real = RESOLVE(function_type, "removexattr");
    bool translated;
    char buffer[PATH_MAX];
    const char *target = translate_follow_path(path, buffer, &translated);
    if (target == NULL) return -1;
    name = translated_xattr_name(target, name, true);
    if (name == NULL) return -1;
    REQUIRE_REAL(real);
    if (translated && !fake_chroot_active) {
        errno = EROFS;
        return -1;
    }
    return real(target, name);
}

int fremovexattr(int descriptor, const char *name) {
    typedef int (*function_type)(int, const char *);
    function_type real = RESOLVE(function_type, "fremovexattr");
    name = descriptor_xattr_name(descriptor, name, true);
    if (name == NULL) return -1;
    REQUIRE_REAL(real);
    return real(descriptor, name);
}

int lremovexattr(const char *path, const char *name) {
    typedef int (*function_type)(const char *, const char *);
    function_type real = RESOLVE(function_type, "lremovexattr");
    bool translated;
    char buffer[PATH_MAX];
    const char *target = translate_path(path, buffer, &translated);
    if (target == NULL) return -1;
    name = translated_xattr_name(target, name, true);
    if (name == NULL) return -1;
    REQUIRE_REAL(real);
    if (translated && !fake_chroot_active) {
        errno = EROFS;
        return -1;
    }
    return real(target, name);
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
    const char *target = translate_at_path(directory, path, buffer, &translated,
            (flags & AT_SYMLINK_NOFOLLOW) == 0);
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
    const char *target = translate_at_path(directory, path, buffer, &translated,
            (flags & AT_SYMLINK_NOFOLLOW) == 0);
    if (target == NULL) return -1;
    REQUIRE_REAL(real);
    int result = real(directory, target, value, flags);
    if (result == 0) normalize_stat_identity(value);
    return result;
}

int statx(int directory, const char *path, int flags, unsigned int mask,
        struct statx *value) {
    typedef int (*function_type)(int, const char *, int, unsigned int, struct statx *);
    typedef int (*fstatat_type)(int, const char *, struct stat *, int);
    function_type real = RESOLVE(function_type, "statx");
    bool translated;
    char buffer[PATH_MAX];
    const char *target = translate_at_path(directory, path, buffer, &translated,
            (flags & AT_SYMLINK_NOFOLLOW) == 0);
    if (target == NULL) return -1;
    int result;
    if (real == NULL) {
        result = -1;
        errno = ENOSYS;
    } else {
        result = real(directory, target, flags, mask, value);
    }
    if (result == 0 && fake_chroot_active) {
        value->stx_uid = root_identity_active ? 0 : ARCHPHENE_USER_ID;
        value->stx_gid = root_identity_active ? 0 : ARCHPHENE_USER_ID;
    }
    if (result == 0 || errno != ENOSYS) return result;

    /*
     * The compatibility filter deliberately reports raw statx syscalls as
     * unavailable so callers that bypass symbol interposition cannot inspect
     * an untranslated Android path. A caller that reached this wrapper has
     * already been translated safely, so provide the normal fstatat fallback
     * here. This also covers tools such as GNU stat that do not retry after an
     * interposed statx function returns ENOSYS.
     */
    unsigned int accepted_flags = AT_SYMLINK_NOFOLLOW | AT_NO_AUTOMOUNT
            | AT_EMPTY_PATH | AT_STATX_SYNC_TYPE;
    if (((unsigned int)flags & ~accepted_flags) != 0) {
        errno = EINVAL;
        return -1;
    }
    fstatat_type real_fstatat = RESOLVE(fstatat_type, "fstatat");
    if (real_fstatat == NULL) {
        errno = ENOSYS;
        return -1;
    }
    struct stat metadata;
    int fstatat_flags = flags
            & (AT_SYMLINK_NOFOLLOW | AT_NO_AUTOMOUNT | AT_EMPTY_PATH);
    if (real_fstatat(directory, target, &metadata, fstatat_flags) != 0) {
        return -1;
    }
    memset(value, 0, sizeof(*value));
    value->stx_mask = STATX_BASIC_STATS;
    value->stx_blksize = (uint32_t)metadata.st_blksize;
    value->stx_nlink = (uint32_t)metadata.st_nlink;
    value->stx_uid = fake_chroot_active
            ? (root_identity_active ? 0 : ARCHPHENE_USER_ID)
            : metadata.st_uid;
    value->stx_gid = fake_chroot_active
            ? (root_identity_active ? 0 : ARCHPHENE_USER_ID)
            : metadata.st_gid;
    value->stx_mode = (uint16_t)metadata.st_mode;
    value->stx_ino = metadata.st_ino;
    value->stx_size = metadata.st_size;
    value->stx_blocks = metadata.st_blocks;
    value->stx_atime.tv_sec = metadata.st_atim.tv_sec;
    value->stx_atime.tv_nsec = (uint32_t)metadata.st_atim.tv_nsec;
    value->stx_ctime.tv_sec = metadata.st_ctim.tv_sec;
    value->stx_ctime.tv_nsec = (uint32_t)metadata.st_ctim.tv_nsec;
    value->stx_mtime.tv_sec = metadata.st_mtim.tv_sec;
    value->stx_mtime.tv_nsec = (uint32_t)metadata.st_mtim.tv_nsec;
    value->stx_rdev_major = major(metadata.st_rdev);
    value->stx_rdev_minor = minor(metadata.st_rdev);
    value->stx_dev_major = major(metadata.st_dev);
    value->stx_dev_minor = minor(metadata.st_dev);
    return 0;
}

ssize_t readlink(const char *path, char *buffer, size_t size) {
    typedef ssize_t (*function_type)(const char *, char *, size_t);
    const char *program = trusted_linux_program_path();
    char managed_program[PATH_MAX];
    if (managed_process_executable(path, managed_program)) {
        size_t length = strlen(managed_program);
        if (length > size) length = size;
        if (length > 0) memcpy(buffer, managed_program, length);
        return (ssize_t)length;
    }
    if (strcmp(path, "/proc/self/exe") == 0 && program != NULL) {
        size_t length = strlen(program);
        if (length > size) length = size;
        if (length > 0) memcpy(buffer, program, length);
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
    ssize_t length = real(target, buffer, size);
    size_t root_length = strlen(trusted_root);
    if (length >= 0 && root_length > 0 && (size_t)length >= root_length
            && memcmp(buffer, trusted_root, root_length) == 0
            && ((size_t)length == root_length || buffer[root_length] == '/')) {
        size_t logical_length = (size_t)length - root_length;
        if (logical_length == 0) {
            if (size > 0) buffer[0] = '/';
            return size > 0 ? 1 : 0;
        }
        memmove(buffer, buffer + root_length, logical_length);
        return (ssize_t)logical_length;
    }
    return length;
}

ssize_t __readlink_chk(
        const char *path, char *buffer, size_t size, size_t buffer_size) {
    typedef ssize_t (*function_type)(
            const char *, char *, size_t, size_t);
    function_type real = RESOLVE(function_type, "__readlink_chk");
    const char *program = trusted_linux_program_path();
    char managed_program[PATH_MAX];
    if (size <= buffer_size
            && managed_process_executable(path, managed_program)) {
        size_t length = strlen(managed_program);
        if (length > size) length = size;
        if (length > 0) memcpy(buffer, managed_program, length);
        return (ssize_t)length;
    }
    if (size <= buffer_size
            && strcmp(path, "/proc/self/exe") == 0
            && program != NULL) {
        size_t length = strlen(program);
        if (length > size) length = size;
        if (length > 0) memcpy(buffer, program, length);
        return (ssize_t)length;
    }
    bool translated;
    char translated_path[PATH_MAX];
    const char *target = translate_path(path, translated_path, &translated);
    if (target == NULL) return -1;
    REQUIRE_REAL(real);
    return real(target, buffer, size, buffer_size);
}

ssize_t readlinkat(int directory, const char *path, char *buffer, size_t size) {
    typedef ssize_t (*function_type)(int, const char *, char *, size_t);
    const char *program = trusted_linux_program_path();
    char managed_program[PATH_MAX];
    if (managed_process_executable(path, managed_program)) {
        size_t length = strlen(managed_program);
        if (length > size) length = size;
        if (length > 0) memcpy(buffer, managed_program, length);
        return (ssize_t)length;
    }
    if (strcmp(path, "/proc/self/exe") == 0 && program != NULL) {
        size_t length = strlen(program);
        if (length > size) length = size;
        if (length > 0) memcpy(buffer, program, length);
        return (ssize_t)length;
    }
    function_type real = RESOLVE(function_type, "readlinkat");
    bool translated;
    char translated_path[PATH_MAX];
    const char *target =
            translate_at_path(directory, path, translated_path, &translated, false);
    if (target == NULL) return -1;
    REQUIRE_REAL(real);
    ssize_t length = real(directory, target, buffer, size);
    size_t root_length = strlen(trusted_root);
    if (length >= 0 && root_length > 0 && (size_t)length >= root_length
            && memcmp(buffer, trusted_root, root_length) == 0
            && ((size_t)length == root_length || buffer[root_length] == '/')) {
        size_t logical_length = (size_t)length - root_length;
        if (logical_length == 0) {
            if (size > 0) buffer[0] = '/';
            return size > 0 ? 1 : 0;
        }
        memmove(buffer, buffer + root_length, logical_length);
        return (ssize_t)logical_length;
    }
    return length;
}

ssize_t __readlinkat_chk(int directory, const char *path, char *buffer,
        size_t size, size_t buffer_size) {
    typedef ssize_t (*function_type)(
            int, const char *, char *, size_t, size_t);
    function_type real = RESOLVE(function_type, "__readlinkat_chk");
    const char *program = trusted_linux_program_path();
    char managed_program[PATH_MAX];
    if (size <= buffer_size
            && managed_process_executable(path, managed_program)) {
        size_t length = strlen(managed_program);
        if (length > size) length = size;
        if (length > 0) memcpy(buffer, managed_program, length);
        return (ssize_t)length;
    }
    if (size <= buffer_size
            && strcmp(path, "/proc/self/exe") == 0
            && program != NULL) {
        size_t length = strlen(program);
        if (length > size) length = size;
        if (length > 0) memcpy(buffer, program, length);
        return (ssize_t)length;
    }
    bool translated;
    char translated_path[PATH_MAX];
    const char *target =
            translate_at_path(directory, path, translated_path, &translated, false);
    if (target == NULL) return -1;
    REQUIRE_REAL(real);
    return real(directory, target, buffer, size, buffer_size);
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
    const char *target =
            translate_at_path(directory, path, buffer, &translated, false);
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
    const char *target = translate_follow_path(path, buffer, &translated);
    if (target == NULL) return -1;
    REQUIRE_REAL(real);
    if (translated && !fake_chroot_active) {
        errno = EROFS;
        return -1;
    }
    return real(AT_FDCWD, target, mode, 0);
}

int utimensat(int directory, const char *path,
        const struct timespec times[2], int flags) {
    typedef int (*function_type)(
            int, const char *, const struct timespec[2], int);
    function_type real = RESOLVE(function_type, "utimensat");
    bool translated;
    char buffer[PATH_MAX];
    const char *target = translate_at_path(directory, path, buffer, &translated,
            (flags & AT_SYMLINK_NOFOLLOW) == 0);
    if (target == NULL) return -1;
    REQUIRE_REAL(real);
    if (translated && !fake_chroot_active) {
        errno = EROFS;
        return -1;
    }
    return real(directory, target, times, flags);
}

int unlinkat(int directory, const char *path, int flags) {
    typedef int (*function_type)(int, const char *, int);
    function_type real = RESOLVE(function_type, "unlinkat");
    bool translated;
    char buffer[PATH_MAX];
    const char *target =
            translate_at_path(directory, path, buffer, &translated, false);
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
    const char *old_target = translate_at_path(
            old_directory, old_path, old_buffer, &old_translated, false);
    const char *new_target = translate_at_path(
            new_directory, new_path, new_buffer, &new_translated, false);
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
    const char *destination =
            translate_at_path(directory, link_path, buffer, &translated, false);
    if (destination == NULL) return -1;
    bool target_translated;
    char target_buffer[PATH_MAX];
    const char *stored_target;
    if (target[0] != '/') {
        if (strchr(target, '\n') != NULL
                || (fake_chroot_active
                    && !inside_trusted_root(destination))) {
            errno = EACCES;
            return -1;
        }
        if (fake_chroot_active) {
            char parent[PATH_MAX];
            size_t destination_length = strlen(destination);
            if (destination_length >= sizeof(parent)) {
                errno = ENAMETOOLONG;
                return -1;
            }
            memcpy(parent, destination, destination_length + 1);
            char *separator = strrchr(parent, '/');
            if (separator == NULL) {
                errno = EACCES;
                return -1;
            }
            *separator = '\0';
            char combined[PATH_MAX];
            int combined_length =
                    snprintf(combined, sizeof(combined), "%s/%s", parent, target);
            char logical[PATH_MAX];
            if (combined_length <= 0
                    || (size_t)combined_length >= sizeof(combined)
                    || !normalize_logical_path(combined, logical)) {
                errno = EACCES;
                return -1;
            }
        }
        stored_target = target;
        target_translated = false;
    } else {
        if (fake_chroot_active || root_identity_active) {
            /*
             * The symlink payload belongs to the emulated Linux namespace
             * both while building a package and while a root-identity package
             * tool installs it. Translating it leaks an Android-private root
             * into either the package archive or the installed shared root.
             */
            if (!normalize_logical_path(target, target_buffer)) {
                errno = EACCES;
                return -1;
            }
            stored_target = target_buffer;
            target_translated = false;
        } else {
            stored_target =
                    translate_path(target, target_buffer, &target_translated);
            if (stored_target == NULL) return -1;
        }
    }
    REQUIRE_REAL(real);
    if (translated && !fake_chroot_active) {
        errno = EROFS;
        return -1;
    }
    return real(stored_target, directory, destination);
}

int symlink(const char *target, const char *link_path) {
    return symlinkat(target, AT_FDCWD, link_path);
}
