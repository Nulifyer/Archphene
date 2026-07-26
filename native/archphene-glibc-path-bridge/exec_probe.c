#include <errno.h>
#include <spawn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>

extern char **environ;

int main(int argc, char **argv) {
    const char *command = argc > 1 ? argv[1] : "cat";
    if (argc > 1 && strcmp(argv[1], "--fakeroot-child") == 0) {
        const char *key = getenv("FAKEROOTKEY");
        printf("fakeroot-key:%s\n", key == NULL ? "missing" : key);
        return 0;
    } else if (argc > 1 && strcmp(argv[1], "--self-child") == 0) {
        printf("self-exec:%s\n", argv[0]);
        return 0;
    } else if (argc > 1 && strcmp(argv[1], "--self-child-clean") == 0) {
        const char *root = getenv("ARCHPHENE_RUNTIME_ROOT");
        const char *preload = getenv("LD_PRELOAD");
        FILE *value = fopen("/usr/share/archphene-test/value", "r");
        char contents[16] = {0};
        if (root == NULL || preload == NULL || value == NULL
                || fgets(contents, sizeof(contents), value) == NULL
                || strcmp(contents, "expected") != 0) {
            fprintf(stderr, "root=%s preload=%s value=%s contents=%s errno=%d\n",
                    root == NULL ? "<missing>" : root,
                    preload == NULL ? "<missing>" : preload,
                    value == NULL ? "<missing>" : "<open>",
                    contents, errno);
            if (value != NULL) fclose(value);
            fputs("managed environment was not restored\n", stderr);
            return 3;
        }
        fclose(value);
        printf("self-clean:%s\n", argv[0]);
        return 0;
    } else if (argc > 1 && strcmp(argv[1], "--self-exec") == 0) {
        char *arguments[] = {"/proc/self/exe", "--self-child", NULL};
        execvp("/proc/self/exe", arguments);
    } else if (argc > 1 && strcmp(argv[1], "--self-exec-clean") == 0) {
        char *arguments[] = {"/proc/self/exe", "--self-child-clean", NULL};
        char *environment[] = {"PATH=/usr/bin", NULL};
        execve("/proc/self/exe", arguments, environment);
    } else if (argc > 2 && strcmp(argv[1], "--access") == 0) {
        if (access(argv[2], R_OK | X_OK) != 0) {
            perror("access");
            return 1;
        }
        puts("runtime-command-accessible");
        return 0;
    } else if (argc > 1 && strcmp(argv[1], "--loader") == 0) {
        const char *loader = getenv("ARCHPHENE_RUNTIME_LOADER");
        char *arguments[] = {(char *)loader, "trusted-loader-exec", NULL};
        execve(loader, arguments, environ);
    } else if (argc > 2 && strcmp(argv[1], "--direct") == 0) {
        command = argv[2];
        char *arguments[] = {(char *)command, "bridge-arg", NULL};
        execve(command, arguments, environ);
    } else if (argc > 3 && strcmp(argv[1], "--direct-path-argument") == 0) {
        command = argv[2];
        char *arguments[] = {(char *)command, argv[3], NULL};
        execve(command, arguments, environ);
    } else if (argc > 2 && strcmp(argv[1], "--spawn-direct") == 0) {
        command = argv[2];
        char *arguments[] = {(char *)command, "bridge-arg", NULL};
        pid_t process;
        int error = posix_spawn(&process, command, NULL, NULL, arguments, environ);
        if (error != 0) {
            errno = error;
            perror("posix_spawn");
            return 1;
        }
        int status;
        return waitpid(process, &status, 0) == process && WIFEXITED(status)
                ? WEXITSTATUS(status) : 1;
    } else if (argc > 2 && strcmp(argv[1], "--spawn-path") == 0) {
        command = argv[2];
        char *arguments[] = {(char *)command, "bridge-arg", NULL};
        pid_t process;
        int error = posix_spawnp(&process, command, NULL, NULL, arguments, environ);
        if (error != 0) {
            errno = error;
            perror("posix_spawnp");
            return 1;
        }
        int status;
        return waitpid(process, &status, 0) == process && WIFEXITED(status)
                ? WEXITSTATUS(status) : 1;
    } else if (argc > 2 && strcmp(argv[1], "--fakeroot") == 0) {
        char *arguments[] = {
            "fakeroot", "--", argv[2], "fakeroot-compat", NULL
        };
        execvp("fakeroot", arguments);
    } else if (argc > 2 && strcmp(argv[1], "--fakeroot-environment") == 0) {
        char *arguments[] = {
            "fakeroot", "--", argv[2], "--fakeroot-child", NULL
        };
        execvp("fakeroot", arguments);
    } else {
        execlp(command, command, "bridge-arg", NULL);
    }
    int error = errno;
    perror("execlp");
    return error == ENOENT ? 2 : 1;
}
