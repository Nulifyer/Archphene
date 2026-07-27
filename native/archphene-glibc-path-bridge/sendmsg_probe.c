#define _GNU_SOURCE

#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>

static int send_payload(int socket_fd, const struct ucred *credentials) {
    char payload = 'P';
    struct iovec vector = {
        .iov_base = &payload,
        .iov_len = sizeof(payload),
    };
    union {
        struct cmsghdr header;
        unsigned char bytes[CMSG_SPACE(sizeof(struct ucred))];
    } control = {0};
    struct msghdr message = {
        .msg_iov = &vector,
        .msg_iovlen = 1,
    };
    if (credentials != NULL) {
        message.msg_control = control.bytes;
        message.msg_controllen = sizeof(control.bytes);
        struct cmsghdr *header = CMSG_FIRSTHDR(&message);
        header->cmsg_level = SOL_SOCKET;
        header->cmsg_type = SCM_CREDENTIALS;
        header->cmsg_len = CMSG_LEN(sizeof(*credentials));
        memcpy(CMSG_DATA(header), credentials, sizeof(*credentials));
    }
    return (int)sendmsg(socket_fd, &message, MSG_NOSIGNAL);
}

static int receive_payload(int socket_fd) {
    char payload = '\0';
    struct iovec vector = {
        .iov_base = &payload,
        .iov_len = sizeof(payload),
    };
    struct msghdr message = {
        .msg_iov = &vector,
        .msg_iovlen = 1,
    };
    return recvmsg(socket_fd, &message, 0) == 1 && payload == 'P' ? 0 : -1;
}

int main(void) {
    int sockets[2];
    if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, sockets) != 0) {
        perror("socketpair");
        return 1;
    }

    struct ucred own = {
        .pid = getpid(),
        .uid = getuid(),
        .gid = getgid(),
    };
    if (send_payload(sockets[0], &own) != 1 || receive_payload(sockets[1]) != 0) {
        perror("send own credentials");
        return 1;
    }

    struct ucred forged = own;
    forged.pid++;
    errno = 0;
    if (send_payload(sockets[0], &forged) != -1 || errno != EPERM) {
        fprintf(stderr, "forged credentials were not rejected: errno=%d\n", errno);
        return 1;
    }

    if (send_payload(sockets[0], NULL) != 1 || receive_payload(sockets[1]) != 0) {
        perror("send without credentials");
        return 1;
    }

    close(sockets[0]);
    close(sockets[1]);
    puts("sendmsg-credentials-ok");
    return 0;
}
