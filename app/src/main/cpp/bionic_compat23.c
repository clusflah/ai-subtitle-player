#undef _FORTIFY_SOURCE
#define _FORTIFY_SOURCE 0
#define _GNU_SOURCE
#define _LARGEFILE64_SOURCE

#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>
#include <net/if.h>

#ifndef RTLD_NOLOAD
#define RTLD_NOLOAD 0x00004
#endif

/**
 * Android 6.0 (API 23) Bionic libc 缺少 Android 7.0 (API 24) 才引入的部分 *_chk
 * Fortify 符号及大文件/网络接口函数。通过 -Wl,-z,global + RTLD_GLOBAL 在应用启动初期加载，
 * 供后续载入的 sherpa-onnx、tdlib 与 libmpv / FFmpeg 解析全局符号。
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

#if defined(__ANDROID_API__) && __ANDROID_API__ < 24

int fseeko(FILE* fp, off_t offset, int whence) {
    return fseek(fp, (long)offset, whence);
}

off_t ftello(FILE* fp) {
    return (off_t)ftell(fp);
}

int fseeko64(FILE* fp, off64_t offset, int whence) {
    return fseek(fp, (long)offset, whence);
}

off64_t ftello64(FILE* fp) {
    return (off64_t)ftell(fp);
}

struct ifaddrs;

int getifaddrs(struct ifaddrs** ifap) {
    if (ifap != NULL) *ifap = NULL;
    errno = ENOSYS;
    return -1;
}

void freeifaddrs(struct ifaddrs* ifa) {
    (void)ifa;
}

unsigned int if_nametoindex(const char* ifname) {
    if (ifname == NULL) return 0;
    int fd = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return 0;
    struct ifreq ifr;
    memset(&ifr, 0, sizeof(ifr));
    strncpy(ifr.ifr_name, ifname, IFNAMSIZ - 1);
    int rc = ioctl(fd, SIOCGIFINDEX, &ifr);
    close(fd);
    return rc == 0 ? (unsigned int)ifr.ifr_ifindex : 0;
}

char* if_indextoname(unsigned int ifindex, char* ifname) {
    if (ifname == NULL) return NULL;
    int fd = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return NULL;
    struct ifreq ifr;
    memset(&ifr, 0, sizeof(ifr));
    ifr.ifr_ifindex = (int)ifindex;
    int rc = ioctl(fd, SIOCGIFNAME, &ifr);
    close(fd);
    if (rc != 0) return NULL;
    strncpy(ifname, ifr.ifr_name, IFNAMSIZ - 1);
    ifname[IFNAMSIZ - 1] = '\0';
    return ifname;
}

int lockf(int fd, int cmd, off_t len) {
    struct flock fl;
    memset(&fl, 0, sizeof(fl));
    fl.l_whence = SEEK_CUR;
    fl.l_start = 0;
    fl.l_len = len;
    int fcntl_cmd = F_SETLK;
    switch (cmd) {
        case 0:
            fl.l_type = F_UNLCK;
            break;
        case 1:
            fl.l_type = F_WRLCK;
            fcntl_cmd = F_SETLKW;
            break;
        case 2:
            fl.l_type = F_WRLCK;
            break;
        case 3:
            fl.l_type = F_WRLCK;
            if (fcntl(fd, F_GETLK, &fl) == -1) return -1;
            if (fl.l_type == F_UNLCK || fl.l_pid == getpid()) return 0;
            errno = EACCES;
            return -1;
        default:
            errno = EINVAL;
            return -1;
    }
    return fcntl(fd, fcntl_cmd, &fl);
}

int lockf64(int fd, int cmd, off64_t len) {
    return lockf(fd, cmd, (off_t)len);
}

#endif /* __ANDROID_API__ < 24 */

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)vm;
    (void)reserved;
    void* h = dlopen("libbionic_compat23.so", RTLD_NOW | RTLD_GLOBAL | RTLD_NOLOAD);
    if (!h) {
        dlopen("libbionic_compat23.so", RTLD_NOW | RTLD_GLOBAL);
    }
    return JNI_VERSION_1_6;
}
