#define _GNU_SOURCE

#include <errno.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stddef.h>
#include <stdio.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <unistd.h>

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
                __NR_landlock_create_ruleset, 2, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K,
                __NR_io_uring_setup, 1, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K,
                __NR_get_mempolicy, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
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
    puts("optional-sandbox-denied");
    return 0;
#endif
}
