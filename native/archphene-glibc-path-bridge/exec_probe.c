#include <stdio.h>
#include <unistd.h>

int main(void) {
    execlp("cat", "cat", "bridge-arg", NULL);
    perror("execlp");
    return 1;
}