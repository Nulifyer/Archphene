#include <errno.h>
#include <jni.h>
#include <stdio.h>
#include <stddef.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

static void throw_io_exception(JNIEnv *env, const char *prefix, int err) {
    jclass cls = (*env)->FindClass(env, "java/io/IOException");
    if (cls == NULL) {
        return;
    }
    char message[256];
    snprintf(message, sizeof(message), "%s: %s", prefix, strerror(err));
    (*env)->ThrowNew(env, cls, message);
}

JNIEXPORT jobject JNICALL
Java_org_archphene_linux_kcalc_MainActivity_createFilesystemWaylandServer(JNIEnv *env, jclass clazz, jstring socket_path) {
    (void)clazz;
    const char *path = (*env)->GetStringUTFChars(env, socket_path, NULL);
    if (path == NULL) {
        return NULL;
    }

    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) {
        int err = errno;
        (*env)->ReleaseStringUTFChars(env, socket_path, path);
        throw_io_exception(env, "socket(AF_UNIX) failed", err);
        return NULL;
    }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    size_t path_len = strlen(path);
    if (path_len + 1 > sizeof(addr.sun_path)) {
        close(fd);
        (*env)->ReleaseStringUTFChars(env, socket_path, path);
        throw_io_exception(env, "socket path is too long", ENAMETOOLONG);
        return NULL;
    }
    memcpy(addr.sun_path, path, path_len + 1);
    unlink(path);

    socklen_t len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + path_len + 1);
    if (bind(fd, (struct sockaddr *)&addr, len) != 0) {
        int err = errno;
        close(fd);
        (*env)->ReleaseStringUTFChars(env, socket_path, path);
        throw_io_exception(env, "bind filesystem UNIX socket failed", err);
        return NULL;
    }
    if (listen(fd, 1) != 0) {
        int err = errno;
        close(fd);
        unlink(path);
        (*env)->ReleaseStringUTFChars(env, socket_path, path);
        throw_io_exception(env, "listen filesystem UNIX socket failed", err);
        return NULL;
    }
    (*env)->ReleaseStringUTFChars(env, socket_path, path);

    jclass fd_class = (*env)->FindClass(env, "java/io/FileDescriptor");
    if (fd_class == NULL) {
        close(fd);
        return NULL;
    }
    jmethodID ctor = (*env)->GetMethodID(env, fd_class, "<init>", "()V");
    jfieldID fd_field = (*env)->GetFieldID(env, fd_class, "descriptor", "I");
    if (fd_field == NULL) {
        (*env)->ExceptionClear(env);
        fd_field = (*env)->GetFieldID(env, fd_class, "fd", "I");
    }
    if (ctor == NULL || fd_field == NULL) {
        close(fd);
        return NULL;
    }
    jobject result = (*env)->NewObject(env, fd_class, ctor);
    if (result == NULL) {
        close(fd);
        return NULL;
    }
    (*env)->SetIntField(env, result, fd_field, fd);
    return result;
}