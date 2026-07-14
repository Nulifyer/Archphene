#include <stdio.h>

extern const char *archphene_runtime_message(void);

int main(void) {
    puts(archphene_runtime_message());
    return 0;
}
