#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/ipc.h>
#include <sys/msg.h>
#include <sys/sem.h>
#include <sys/wait.h>
#include <unistd.h>

struct probe_message {
    long type;
    char text[32];
};

int main(void) {
    int queue = msgget(IPC_PRIVATE, IPC_CREAT | 0600);
    if (queue < 0) {
        perror("msgget");
        return 1;
    }
    int semaphore = semget(IPC_PRIVATE, 1, IPC_CREAT | 0600);
    if (semaphore < 0 || semctl(semaphore, 0, SETVAL, 0) != 0) {
        perror("semget");
        msgctl(queue, IPC_RMID, NULL);
        return 1;
    }
    pid_t child = fork();
    if (child < 0) {
        perror("fork");
        msgctl(queue, IPC_RMID, NULL);
        return 1;
    }
    if (child == 0) {
        struct probe_message request = {.type = 7, .text = "request"};
        if (msgsnd(queue, &request, strlen(request.text) + 1, 0) != 0) {
            perror("child msgsnd");
            _exit(2);
        }
        struct probe_message response;
        ssize_t received =
                msgrcv(queue, &response, sizeof(response.text), getpid(), 0);
        if (received != 9 || strcmp(response.text, "response") != 0) {
            perror("child msgrcv");
            _exit(3);
        }
        struct sembuf release = {.sem_num = 0, .sem_op = 1, .sem_flg = 0};
        if (semop(semaphore, &release, 1) != 0) {
            perror("child semop");
            _exit(4);
        }
        _exit(0);
    }
    struct probe_message request;
    ssize_t received = msgrcv(queue, &request, sizeof(request.text), 7, 0);
    if (received != 8 || strcmp(request.text, "request") != 0) {
        perror("parent msgrcv");
        kill(child, SIGKILL);
        waitpid(child, NULL, 0);
        msgctl(queue, IPC_RMID, NULL);
        return 1;
    }
    struct probe_message response = {.type = child, .text = "response"};
    if (msgsnd(queue, &response, strlen(response.text) + 1, 0) != 0) {
        perror("parent msgsnd");
        kill(child, SIGKILL);
        waitpid(child, NULL, 0);
        msgctl(queue, IPC_RMID, NULL);
        return 1;
    }
    struct sembuf acquire = {.sem_num = 0, .sem_op = -1, .sem_flg = 0};
    if (semop(semaphore, &acquire, 1) != 0
            || semctl(semaphore, 0, GETVAL) != 0) {
        perror("parent semop");
        kill(child, SIGKILL);
        waitpid(child, NULL, 0);
        semctl(semaphore, 0, IPC_RMID);
        msgctl(queue, IPC_RMID, NULL);
        return 1;
    }
    int status = 0;
    if (waitpid(child, &status, 0) != child || !WIFEXITED(status)
            || WEXITSTATUS(status) != 0) {
        fprintf(stderr, "message queue child failed\n");
        msgctl(queue, IPC_RMID, NULL);
        return 1;
    }
    if (msgctl(queue, IPC_RMID, NULL) != 0) {
        perror("msgctl");
        return 1;
    }
    if (semctl(semaphore, 0, IPC_RMID) != 0) {
        perror("semctl");
        return 1;
    }
    puts("ipc-ok");
    return 0;
}
