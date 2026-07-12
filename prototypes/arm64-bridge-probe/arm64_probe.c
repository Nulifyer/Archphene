#include <dlfcn.h>
#include <errno.h>
#include <jni.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/utsname.h>
#include <unistd.h>

JNIEXPORT jstring JNICALL
Java_org_archphene_bridgeprobe_MainActivity_runNativeChecks(
        JNIEnv *env, jclass clazz, jstring files_dir) {
    (void)clazz;
    (void)files_dir;
    char report[1024];
    struct utsname uts;
    int uname_ok = uname(&uts) == 0;

    int sockets[2] = {-1, -1};
    int socket_ok = socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, sockets) == 0;
    int transfer_ok = 0;
    if (socket_ok) {
        const char sent[] = "wayland-socket-ok";
        char received[sizeof(sent)] = {0};
        transfer_ok = write(sockets[0], sent, sizeof(sent)) == sizeof(sent)
                && read(sockets[1], received, sizeof(received)) == sizeof(received)
                && memcmp(sent, received, sizeof(sent)) == 0;
        close(sockets[0]);
        close(sockets[1]);
    }

    size_t page_size = (size_t)sysconf(_SC_PAGESIZE);
    unsigned char *shared = mmap(NULL, page_size, PROT_READ | PROT_WRITE,
            MAP_SHARED | MAP_ANONYMOUS, -1, 0);
    int mmap_ok = shared != MAP_FAILED;
    if (mmap_ok) {
        shared[0] = 0x41;
        mmap_ok = shared[0] == 0x41;
        munmap(shared, page_size);
    }

    void *shim = dlopen("libarchphene_wayland_client_android.so", RTLD_NOW | RTLD_LOCAL);
    int shim_ok = shim != NULL
            && dlsym(shim, "wl_display_connect") != NULL
            && dlsym(shim, "wl_proxy_marshal_flags") != NULL;
    const char *shim_error = shim_ok ? "none" : dlerror();
    if (shim != NULL) dlclose(shim);

    int all_ok = uname_ok && socket_ok && transfer_ok && mmap_ok && shim_ok;
    snprintf(report, sizeof(report),
            "%s\nuname.machine=%s\nAF_UNIX socketpair=%s\nsocket transfer=%s\nshared mmap=%s\nWayland shim dlopen/exports=%s\nshim error=%s",
            all_ok ? "PASS" : "FAIL",
            uname_ok ? uts.machine : "unknown",
            socket_ok ? "PASS" : strerror(errno),
            transfer_ok ? "PASS" : "FAIL",
            mmap_ok ? "PASS" : "FAIL",
            shim_ok ? "PASS" : "FAIL",
            shim_error == NULL ? "none" : shim_error);
    return (*env)->NewStringUTF(env, report);
}
