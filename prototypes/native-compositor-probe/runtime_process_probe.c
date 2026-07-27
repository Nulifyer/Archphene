#include <unistd.h>

int main(void) {
    static const char message[] = "runtime-process-probe\n";
    if (write(STDOUT_FILENO, message, sizeof(message) - 1) < 0) {
        return 24;
    }
    return 23;
}
