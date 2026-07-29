#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <linux/filter.h>
#include <linux/openat2.h>
#include <linux/seccomp.h>
#include <stddef.h>
#include <stdio.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifdef __NR_openat2
extern int openat2(int directory, const char *path,
        const struct open_how *how, size_t size);
#endif

int main(void) {
#if !defined(__NR_landlock_create_ruleset) || !defined(__NR_io_uring_setup) \
        || !defined(__NR_get_mempolicy)
    puts("landlock-unavailable");
    return 0;
#else
    struct sock_filter filter[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS,
                (unsigned int)offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K,
                __NR_landlock_create_ruleset, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K,
                __NR_io_uring_setup, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K,
                __NR_get_mempolicy, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
#ifdef __NR_pkey_alloc
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K,
                __NR_pkey_alloc, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
#endif
#ifdef __NR_openat2
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K,
                __NR_openat2, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
#endif
#ifdef __NR_statx
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K,
                __NR_statx, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
#endif
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog program = {
        .len = (unsigned short)(sizeof(filter) / sizeof(filter[0])),
        .filter = filter,
    };
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0
            || prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program) != 0) {
        perror("seccomp");
        return 1;
    }
    errno = 0;
    long result = syscall(__NR_landlock_create_ruleset, NULL, 0, 0);
    if (result != -1 || errno != ENOSYS) {
        fprintf(stderr, "landlock result=%ld errno=%d\n", result, errno);
        return 1;
    }
    errno = 0;
    result = syscall(__NR_io_uring_setup, 1, NULL);
    if (result != -1 || errno != ENOSYS) {
        fprintf(stderr, "io_uring result=%ld errno=%d\n", result, errno);
        return 1;
    }
    errno = 0;
    result = syscall(__NR_get_mempolicy, NULL, NULL, 0, NULL, 0);
    if (result != -1 || errno != ENOSYS) {
        fprintf(stderr, "get_mempolicy result=%ld errno=%d\n", result, errno);
        return 1;
    }
#ifdef __NR_pkey_alloc
    errno = 0;
    result = syscall(__NR_pkey_alloc, 0, 0);
    if (result != -1 || errno != ENOSYS) {
        fprintf(stderr, "pkey_alloc result=%ld errno=%d\n", result, errno);
        return 1;
    }
#endif
#ifdef __NR_openat2
    errno = 0;
    result = syscall(__NR_openat2, -1, NULL, NULL, 0);
    if (result != -1 || errno != ENOSYS) {
        fprintf(stderr, "openat2 result=%ld errno=%d\n", result, errno);
        return 1;
    }
    errno = 0;
    struct open_how how = {};
    if (openat2(-1, "", &how, sizeof(how)) != -1 || errno != ENOSYS) {
        fprintf(stderr, "openat2 symbol errno=%d\n", errno);
        return 1;
    }
#endif
#if defined(__NR_statx) && defined(STATX_MNT_ID)
    struct statx metadata;
    if (statx(AT_FDCWD, ".", AT_STATX_SYNC_AS_STAT,
                STATX_TYPE | STATX_MNT_ID, &metadata) != 0
            || (metadata.stx_mask & (STATX_TYPE | STATX_MNT_ID))
                != (STATX_TYPE | STATX_MNT_ID)
            || metadata.stx_mnt_id == 0) {
        fprintf(stderr, "statx fallback errno=%d mask=%x mount=%llu\n",
                errno, metadata.stx_mask,
                (unsigned long long)metadata.stx_mnt_id);
        return 1;
    }
#endif
    puts("optional-sandbox-denied");
    return 0;
#endif
}
