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
    if (argc > 2 && strcmp(argv[1], "--access") == 0) {
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
    } else {
        execlp(command, command, "bridge-arg", NULL);
    }
    int error = errno;
    perror("execlp");
    return error == ENOENT ? 2 : 1;
}
