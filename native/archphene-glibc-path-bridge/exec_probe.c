#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

extern char **environ;

int main(int argc, char **argv) {
    const char *command = argc > 1 ? argv[1] : "cat";
    if (argc > 2 && strcmp(argv[1], "--direct") == 0) {
        command = argv[2];
        char *arguments[] = {(char *)command, "bridge-arg", NULL};
        execve(command, arguments, environ);
    } else {
        execlp(command, command, "bridge-arg", NULL);
    }
    int error = errno;
    perror("execlp");
    return error == ENOENT ? 2 : 1;
}
