#include <arpa/inet.h>
#include <netdb.h>
#include <resolv.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int main(int argument_count, char **arguments) {
    if (argument_count != 2) {
        fputs("usage: resolver_probe HOST|--configuration-only\n", stderr);
        return 64;
    }
    if (res_init() != 0) {
        perror("res_init");
        return 1;
    }
    printf("nameservers=%d\n", _res.nscount);
    for (int index = 0; index < _res.nscount; index++) {
        char address[INET_ADDRSTRLEN];
        if (inet_ntop(AF_INET, &_res.nsaddr_list[index].sin_addr,
                    address, sizeof(address)) != NULL) {
            printf("nameserver[%d]=%s\n", index, address);
        }
    }
    if (strcmp(arguments[1], "--configuration-only") == 0) {
        return 0;
    }
    struct addrinfo hints = {
        .ai_family = AF_UNSPEC,
        .ai_socktype = SOCK_STREAM,
    };
    struct addrinfo *result = NULL;
    int status = getaddrinfo(arguments[1], NULL, &hints, &result);
    printf("getaddrinfo=%d:%s\n", status, gai_strerror(status));
    freeaddrinfo(result);
    return status == 0 ? 0 : 2;
}
