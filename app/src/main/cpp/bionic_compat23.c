#undef _FORTIFY_SOURCE
#define _FORTIFY_SOURCE 0
#define _GNU_SOURCE
#define _LARGEFILE64_SOURCE

#include <dlfcn.h>
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/types.h>
#include <unistd.h>

#ifndef RTLD_NOLOAD
#define RTLD_NOLOAD 0x00004
#endif

/**
 * Android 6.0 (API 23) Bionic libc 缺少 Android 7.0 (API 24) 才引入的部分 *_chk
 * Fortify 符号。通过 -Wl,-z,global + RTLD_GLOBAL 在应用启动初期加载，供后续载入的
 * sherpa-onnx (libonnxruntime.so) 与 tdlib (libtdjni.so) 解析全局符号。
 */

ssize_t __write_chk(int fd, const void* buf, size_t count, size_t bos) {
    if (bos != (size_t)-1 && count > bos) {
        abort();
    }
    return write(fd, buf, count);
}

ssize_t __pwrite64_chk(int fd, const void* buf, size_t count, off64_t offset, size_t bos) {
    if (bos != (size_t)-1 && count > bos) {
        abort();
    }
    return pwrite64(fd, buf, count, offset);
}

ssize_t __pwrite_chk(int fd, const void* buf, size_t count, off_t offset, size_t bos) {
    if (bos != (size_t)-1 && count > bos) {
        abort();
    }
    return pwrite(fd, buf, count, offset);
}

char* __getcwd_chk(char* buf, size_t len, size_t bos) {
    if (buf != NULL && bos != (size_t)-1 && len > bos) {
        abort();
    }
    return getcwd(buf, len);
}

size_t __fread_chk(void* buf, size_t size, size_t count, FILE* stream, size_t bos) {
    if (bos != (size_t)-1 && size != 0 && count > bos / size) {
        abort();
    }
    return fread(buf, size, count, stream);
}

size_t __fwrite_chk(const void* buf, size_t size, size_t count, FILE* stream, size_t bos) {
    if (bos != (size_t)-1 && size != 0 && count > bos / size) {
        abort();
    }
    return fwrite(buf, size, count, stream);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)vm;
    (void)reserved;
    void* h = dlopen("libbionic_compat23.so", RTLD_NOW | RTLD_GLOBAL | RTLD_NOLOAD);
    if (!h) {
        dlopen("libbionic_compat23.so", RTLD_NOW | RTLD_GLOBAL);
    }
    return JNI_VERSION_1_6;
}
