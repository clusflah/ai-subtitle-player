#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#ifndef RTLD_NOLOAD
#define RTLD_NOLOAD 0x00004
#endif

#define LOG_TAG "Voxtitle-MpvBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

typedef struct mpv_handle mpv_handle;

enum {
    MPV_FORMAT_NONE       = 0,
    MPV_FORMAT_STRING     = 1,
    MPV_FORMAT_OSD_STRING = 2,
    MPV_FORMAT_FLAG       = 3,
    MPV_FORMAT_INT64      = 4,
    MPV_FORMAT_DOUBLE     = 5,
};

enum {
    MPV_EVENT_NONE               = 0,
    MPV_EVENT_SHUTDOWN           = 1,
    MPV_EVENT_LOG_MESSAGE        = 2,
    MPV_EVENT_START_FILE         = 6,
    MPV_EVENT_END_FILE           = 7,
    MPV_EVENT_FILE_LOADED        = 8,
    MPV_EVENT_VIDEO_RECONFIG     = 17,
    MPV_EVENT_AUDIO_RECONFIG     = 18,
    MPV_EVENT_SEEK               = 20,
    MPV_EVENT_PLAYBACK_RESTART   = 21,
    MPV_EVENT_PROPERTY_CHANGE    = 22,
};

typedef struct mpv_event_property {
    const char* name;
    int format;
    void* data;
} mpv_event_property;

typedef struct mpv_event_end_file {
    int reason;
    int error;
    int64_t playlist_entry_id;
    int64_t playlist_insert_id;
    int playlist_insert_num_entries;
} mpv_event_end_file;

typedef struct mpv_event_log_message {
    const char* prefix;
    const char* level;
    const char* text;
    int log_level;
} mpv_event_log_message;

typedef struct mpv_event {
    int event_id;
    int error;
    uint64_t reply_userdata;
    void* data;
} mpv_event;

typedef mpv_handle* (*fn_mpv_create)(void);
typedef int (*fn_mpv_initialize)(mpv_handle*);
typedef void (*fn_mpv_terminate_destroy)(mpv_handle*);
typedef void (*fn_mpv_wakeup)(mpv_handle*);
typedef int (*fn_mpv_command)(mpv_handle*, const char**);
typedef int (*fn_mpv_command_async)(mpv_handle*, uint64_t, const char**);
typedef int (*fn_mpv_set_option)(mpv_handle*, const char*, int, void*);
typedef int (*fn_mpv_set_option_string)(mpv_handle*, const char*, const char*);
typedef int (*fn_mpv_set_property)(mpv_handle*, const char*, int, void*);
typedef int (*fn_mpv_set_property_string)(mpv_handle*, const char*, const char*);
typedef int (*fn_mpv_get_property)(mpv_handle*, const char*, int, void*);
typedef char* (*fn_mpv_get_property_string)(mpv_handle*, const char*);
typedef void (*fn_mpv_free)(void*);
typedef int (*fn_mpv_observe_property)(mpv_handle*, uint64_t, const char*, int);
typedef int (*fn_mpv_request_log_messages)(mpv_handle*, const char*);
typedef mpv_event* (*fn_mpv_wait_event)(mpv_handle*, double);
typedef int (*fn_av_jni_set_java_vm)(void*, void*);

struct MpvApi {
    void* lib;
    fn_mpv_create create;
    fn_mpv_initialize initialize;
    fn_mpv_terminate_destroy terminate_destroy;
    fn_mpv_wakeup wakeup;
    fn_mpv_command command;
    fn_mpv_command_async command_async;
    fn_mpv_set_option set_option;
    fn_mpv_set_option_string set_option_string;
    fn_mpv_set_property set_property;
    fn_mpv_set_property_string set_property_string;
    fn_mpv_get_property get_property;
    fn_mpv_get_property_string get_property_string;
    fn_mpv_free free_mem;
    fn_mpv_observe_property observe_property;
    fn_mpv_request_log_messages request_log_messages;
    fn_mpv_wait_event wait_event;
    bool ready;
};

static JavaVM* g_vm = nullptr;
static pthread_mutex_t g_api_lock = PTHREAD_MUTEX_INITIALIZER;
static MpvApi g_api = {};
static bool g_av_vm_initialized = false;

static bool ensure_mpv_api_locked() {
    if (g_api.ready) return true;

    void* lib = dlopen("libmpv.so", RTLD_NOW | RTLD_NOLOAD);
    if (!lib) {
        lib = dlopen("libmpv.so", RTLD_NOW | RTLD_LOCAL);
    }
    if (!lib) {
        LOGE("dlopen libmpv.so 失败: %s", dlerror());
        return false;
    }

    if (!g_av_vm_initialized && g_vm != nullptr) {
        fn_av_jni_set_java_vm set_vm = nullptr;
        void* avcodec = dlopen("libavcodec.so", RTLD_NOW | RTLD_NOLOAD);
        if (!avcodec) avcodec = dlopen("libavcodec.so", RTLD_NOW | RTLD_LOCAL);
        if (avcodec) {
            set_vm = reinterpret_cast<fn_av_jni_set_java_vm>(dlsym(avcodec, "av_jni_set_java_vm"));
        }
        if (!set_vm) {
            set_vm = reinterpret_cast<fn_av_jni_set_java_vm>(dlsym(lib, "av_jni_set_java_vm"));
        }
        if (set_vm) {
            int rc = set_vm(g_vm, nullptr);
            LOGI("av_jni_set_java_vm 初始化完成: rc=%d", rc);
        }
        g_av_vm_initialized = true;
    }

    g_api.lib = lib;
    g_api.create = reinterpret_cast<fn_mpv_create>(dlsym(lib, "mpv_create"));
    g_api.initialize = reinterpret_cast<fn_mpv_initialize>(dlsym(lib, "mpv_initialize"));
    g_api.terminate_destroy = reinterpret_cast<fn_mpv_terminate_destroy>(dlsym(lib, "mpv_terminate_destroy"));
    g_api.wakeup = reinterpret_cast<fn_mpv_wakeup>(dlsym(lib, "mpv_wakeup"));
    g_api.command = reinterpret_cast<fn_mpv_command>(dlsym(lib, "mpv_command"));
    g_api.command_async = reinterpret_cast<fn_mpv_command_async>(dlsym(lib, "mpv_command_async"));
    g_api.set_option = reinterpret_cast<fn_mpv_set_option>(dlsym(lib, "mpv_set_option"));
    g_api.set_option_string = reinterpret_cast<fn_mpv_set_option_string>(dlsym(lib, "mpv_set_option_string"));
    g_api.set_property = reinterpret_cast<fn_mpv_set_property>(dlsym(lib, "mpv_set_property"));
    g_api.set_property_string = reinterpret_cast<fn_mpv_set_property_string>(dlsym(lib, "mpv_set_property_string"));
    g_api.get_property = reinterpret_cast<fn_mpv_get_property>(dlsym(lib, "mpv_get_property"));
    g_api.get_property_string = reinterpret_cast<fn_mpv_get_property_string>(dlsym(lib, "mpv_get_property_string"));
    g_api.free_mem = reinterpret_cast<fn_mpv_free>(dlsym(lib, "mpv_free"));
    g_api.observe_property = reinterpret_cast<fn_mpv_observe_property>(dlsym(lib, "mpv_observe_property"));
    g_api.request_log_messages = reinterpret_cast<fn_mpv_request_log_messages>(dlsym(lib, "mpv_request_log_messages"));
    g_api.wait_event = reinterpret_cast<fn_mpv_wait_event>(dlsym(lib, "mpv_wait_event"));

    if (!g_api.create || !g_api.initialize || !g_api.terminate_destroy || !g_api.wakeup ||
        !g_api.command || !g_api.set_option || !g_api.set_option_string ||
        !g_api.set_property || !g_api.set_property_string || !g_api.get_property ||
        !g_api.get_property_string || !g_api.free_mem || !g_api.observe_property || !g_api.wait_event) {
        LOGE("libmpv.so 缺少核心符号");
        return false;
    }

    g_api.ready = true;
    return true;
}

static bool ensure_mpv_api() {
    pthread_mutex_lock(&g_api_lock);
    bool ok = ensure_mpv_api_locked();
    pthread_mutex_unlock(&g_api_lock);
    return ok;
}

struct MpvInstance {
    mpv_handle* mpv;
    jobject callback_global;
    jobject surface_global;
    jmethodID mid_on_event;
    jmethodID mid_on_prop_long;
    jmethodID mid_on_prop_double;
    jmethodID mid_on_prop_bool;
    jmethodID mid_on_prop_string;
    pthread_t event_thread;
    pthread_mutex_t surface_lock;
    volatile int stopping;
    bool initialized;
    bool thread_started;
};

static void* mpv_event_loop(void* arg) {
    MpvInstance* inst = reinterpret_cast<MpvInstance*>(arg);
    JNIEnv* env = nullptr;
    if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK || !env) {
        LOGE("mpv 事件线程绑定 JVM 失败");
        return nullptr;
    }

    while (!inst->stopping) {
        mpv_event* ev = g_api.wait_event(inst->mpv, 0.5);
        if (!ev || inst->stopping) continue;

        switch (ev->event_id) {
            case MPV_EVENT_NONE:
                break;
            case MPV_EVENT_SHUTDOWN:
                env->CallVoidMethod(inst->callback_global, inst->mid_on_event, (jint)MPV_EVENT_SHUTDOWN, (jint)0, (jint)0);
                if (env->ExceptionCheck()) env->ExceptionClear();
                goto exit_loop;
            case MPV_EVENT_START_FILE:
            case MPV_EVENT_FILE_LOADED:
            case MPV_EVENT_VIDEO_RECONFIG:
            case MPV_EVENT_AUDIO_RECONFIG:
            case MPV_EVENT_SEEK:
            case MPV_EVENT_PLAYBACK_RESTART:
                env->CallVoidMethod(inst->callback_global, inst->mid_on_event, (jint)ev->event_id, (jint)0, (jint)ev->error);
                if (env->ExceptionCheck()) env->ExceptionClear();
                break;
            case MPV_EVENT_END_FILE: {
                mpv_event_end_file* ef = reinterpret_cast<mpv_event_end_file*>(ev->data);
                int reason = ef ? ef->reason : 0;
                int err = ef ? ef->error : ev->error;
                env->CallVoidMethod(inst->callback_global, inst->mid_on_event, (jint)MPV_EVENT_END_FILE, (jint)reason, (jint)err);
                if (env->ExceptionCheck()) env->ExceptionClear();
                break;
            }
            case MPV_EVENT_LOG_MESSAGE: {
                mpv_event_log_message* msg = reinterpret_cast<mpv_event_log_message*>(ev->data);
                if (msg && msg->text) {
                    LOGW("[%s] %s", msg->prefix ? msg->prefix : "mpv", msg->text);
                }
                break;
            }
            case MPV_EVENT_PROPERTY_CHANGE: {
                mpv_event_property* prop = reinterpret_cast<mpv_event_property*>(ev->data);
                if (!prop || !prop->name) break;
                if (prop->format == MPV_FORMAT_NONE) {
                    jstring jname = env->NewStringUTF(prop->name);
                    if (jname) {
                        env->CallVoidMethod(inst->callback_global, inst->mid_on_prop_string, jname, nullptr);
                        env->DeleteLocalRef(jname);
                    }
                    break;
                }
                if (!prop->data) break;
                jstring jname = env->NewStringUTF(prop->name);
                if (!jname) break;
                switch (prop->format) {
                    case MPV_FORMAT_INT64: {
                        int64_t val = *reinterpret_cast<int64_t*>(prop->data);
                        env->CallVoidMethod(inst->callback_global, inst->mid_on_prop_long, jname, (jlong)val);
                        break;
                    }
                    case MPV_FORMAT_DOUBLE: {
                        double val = *reinterpret_cast<double*>(prop->data);
                        env->CallVoidMethod(inst->callback_global, inst->mid_on_prop_double, jname, (jdouble)val);
                        break;
                    }
                    case MPV_FORMAT_FLAG: {
                        int val = *reinterpret_cast<int*>(prop->data);
                        env->CallVoidMethod(inst->callback_global, inst->mid_on_prop_bool, jname, val ? JNI_TRUE : JNI_FALSE);
                        break;
                    }
                    case MPV_FORMAT_STRING: {
                        const char* str = *reinterpret_cast<const char**>(prop->data);
                        jstring jval = str ? env->NewStringUTF(str) : nullptr;
                        env->CallVoidMethod(inst->callback_global, inst->mid_on_prop_string, jname, jval);
                        if (jval) env->DeleteLocalRef(jval);
                        break;
                    }
                    default:
                        break;
                }
                if (env->ExceptionCheck()) env->ExceptionClear();
                env->DeleteLocalRef(jname);
                break;
            }
            default:
                break;
        }
    }

exit_loop:
    g_vm->DetachCurrentThread();
    return nullptr;
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aisub_player_media_MpvNativeBridge_nativeIsAvailable(JNIEnv* env, jclass clazz) {
    (void)env;
    (void)clazz;
    return ensure_mpv_api() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_aisub_player_media_MpvNativeBridge_nativeCreate(JNIEnv* env, jobject thiz) {
    if (!ensure_mpv_api()) return 0;

    mpv_handle* mpv = g_api.create();
    if (!mpv) {
        LOGE("mpv_create() 返回空句柄");
        return 0;
    }

    jclass cls = env->GetObjectClass(thiz);
    jmethodID mid_event = env->GetMethodID(cls, "onNativeEvent", "(III)V");
    jmethodID mid_long = env->GetMethodID(cls, "onNativePropertyLong", "(Ljava/lang/String;J)V");
    jmethodID mid_double = env->GetMethodID(cls, "onNativePropertyDouble", "(Ljava/lang/String;D)V");
    jmethodID mid_bool = env->GetMethodID(cls, "onNativePropertyBoolean", "(Ljava/lang/String;Z)V");
    jmethodID mid_str = env->GetMethodID(cls, "onNativePropertyString", "(Ljava/lang/String;Ljava/lang/String;)V");
    if (!mid_event || !mid_long || !mid_double || !mid_bool || !mid_str) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        g_api.terminate_destroy(mpv);
        return 0;
    }

    MpvInstance* inst = static_cast<MpvInstance*>(malloc(sizeof(MpvInstance)));
    if (!inst) {
        g_api.terminate_destroy(mpv);
        return 0;
    }
    memset(inst, 0, sizeof(MpvInstance));
    inst->mpv = mpv;
    inst->callback_global = env->NewGlobalRef(thiz);
    inst->mid_on_event = mid_event;
    inst->mid_on_prop_long = mid_long;
    inst->mid_on_prop_double = mid_double;
    inst->mid_on_prop_bool = mid_bool;
    inst->mid_on_prop_string = mid_str;
    pthread_mutex_init(&inst->surface_lock, nullptr);

    return reinterpret_cast<jlong>(inst);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aisub_player_media_MpvNativeBridge_nativeInitialize(JNIEnv* env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    MpvInstance* inst = reinterpret_cast<MpvInstance*>(handle);
    if (!inst || !inst->mpv) return JNI_FALSE;

    if (g_api.request_log_messages) {
        g_api.request_log_messages(inst->mpv, "warn");
    }

    int rc = g_api.initialize(inst->mpv);
    if (rc < 0) {
        LOGE("mpv_initialize 失败: rc=%d", rc);
        return JNI_FALSE;
    }
    inst->initialized = true;

    g_api.observe_property(inst->mpv, 1, "time-pos", MPV_FORMAT_DOUBLE);
    g_api.observe_property(inst->mpv, 2, "duration", MPV_FORMAT_DOUBLE);
    g_api.observe_property(inst->mpv, 3, "pause", MPV_FORMAT_FLAG);
    g_api.observe_property(inst->mpv, 4, "paused-for-cache", MPV_FORMAT_FLAG);
    g_api.observe_property(inst->mpv, 5, "eof-reached", MPV_FORMAT_FLAG);
    g_api.observe_property(inst->mpv, 6, "video-params/colormatrix", MPV_FORMAT_STRING);
    g_api.observe_property(inst->mpv, 7, "hwdec-current", MPV_FORMAT_STRING);
    g_api.observe_property(inst->mpv, 8, "current-vo", MPV_FORMAT_STRING);
    g_api.observe_property(inst->mpv, 9, "sub-text", MPV_FORMAT_STRING);
    g_api.observe_property(inst->mpv, 10, "sub-start", MPV_FORMAT_DOUBLE);
    g_api.observe_property(inst->mpv, 11, "sub-end", MPV_FORMAT_DOUBLE);

    inst->stopping = 0;
    if (pthread_create(&inst->event_thread, nullptr, mpv_event_loop, inst) == 0) {
        inst->thread_started = true;
    } else {
        LOGE("启动 mpv 事件线程失败");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aisub_player_media_MpvNativeBridge_nativeSetOptionString(
    JNIEnv* env, jobject thiz, jlong handle, jstring jname, jstring jvalue
) {
    (void)thiz;
    MpvInstance* inst = reinterpret_cast<MpvInstance*>(handle);
    if (!inst || !inst->mpv || !jname || !jvalue) return -1;
    const char* name = env->GetStringUTFChars(jname, nullptr);
    const char* value = env->GetStringUTFChars(jvalue, nullptr);
    int rc = g_api.set_option_string(inst->mpv, name, value);
    env->ReleaseStringUTFChars(jname, name);
    env->ReleaseStringUTFChars(jvalue, value);
    return rc;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aisub_player_media_MpvNativeBridge_nativeSetPropertyString(
    JNIEnv* env, jobject thiz, jlong handle, jstring jname, jstring jvalue
) {
    (void)thiz;
    MpvInstance* inst = reinterpret_cast<MpvInstance*>(handle);
    if (!inst || !inst->mpv || !jname || !jvalue) return -1;
    const char* name = env->GetStringUTFChars(jname, nullptr);
    const char* value = env->GetStringUTFChars(jvalue, nullptr);
    int rc = g_api.set_property_string(inst->mpv, name, value);
    env->ReleaseStringUTFChars(jname, name);
    env->ReleaseStringUTFChars(jvalue, value);
    return rc;
}

extern "C" JNIEXPORT void JNICALL
Java_com_aisub_player_media_MpvNativeBridge_nativeSetPropertyDouble(
    JNIEnv* env, jobject thiz, jlong handle, jstring jname, jdouble value
) {
    (void)thiz;
    MpvInstance* inst = reinterpret_cast<MpvInstance*>(handle);
    if (!inst || !inst->mpv || !jname) return;
    const char* name = env->GetStringUTFChars(jname, nullptr);
    double v = (double)value;
    g_api.set_property(inst->mpv, name, MPV_FORMAT_DOUBLE, &v);
    env->ReleaseStringUTFChars(jname, name);
}

extern "C" JNIEXPORT void JNICALL
Java_com_aisub_player_media_MpvNativeBridge_nativeSetPropertyLong(
    JNIEnv* env, jobject thiz, jlong handle, jstring jname, jlong value
) {
    (void)thiz;
    MpvInstance* inst = reinterpret_cast<MpvInstance*>(handle);
    if (!inst || !inst->mpv || !jname) return;
    const char* name = env->GetStringUTFChars(jname, nullptr);
    int64_t v = (int64_t)value;
    g_api.set_property(inst->mpv, name, MPV_FORMAT_INT64, &v);
    env->ReleaseStringUTFChars(jname, name);
}

extern "C" JNIEXPORT void JNICALL
Java_com_aisub_player_media_MpvNativeBridge_nativeSetPropertyBoolean(
    JNIEnv* env, jobject thiz, jlong handle, jstring jname, jboolean value
) {
    (void)thiz;
    MpvInstance* inst = reinterpret_cast<MpvInstance*>(handle);
    if (!inst || !inst->mpv || !jname) return;
    const char* name = env->GetStringUTFChars(jname, nullptr);
    int flag = value ? 1 : 0;
    g_api.set_property(inst->mpv, name, MPV_FORMAT_FLAG, &flag);
    env->ReleaseStringUTFChars(jname, name);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_aisub_player_media_MpvNativeBridge_nativeGetPropertyString(
    JNIEnv* env, jobject thiz, jlong handle, jstring jname
) {
    (void)thiz;
    MpvInstance* inst = reinterpret_cast<MpvInstance*>(handle);
    if (!inst || !inst->mpv || !jname) return nullptr;
    const char* name = env->GetStringUTFChars(jname, nullptr);
    char* res = g_api.get_property_string(inst->mpv, name);
    env->ReleaseStringUTFChars(jname, name);
    if (!res) return nullptr;
    jstring jres = env->NewStringUTF(res);
    g_api.free_mem(res);
    return jres;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_aisub_player_media_MpvNativeBridge_nativeGetPropertyLong(
    JNIEnv* env, jobject thiz, jlong handle, jstring jname, jlong fallback
) {
    (void)thiz;
    MpvInstance* inst = reinterpret_cast<MpvInstance*>(handle);
    if (!inst || !inst->mpv || !jname) return fallback;
    const char* name = env->GetStringUTFChars(jname, nullptr);
    int64_t val = 0;
    int rc = g_api.get_property(inst->mpv, name, MPV_FORMAT_INT64, &val);
    env->ReleaseStringUTFChars(jname, name);
    return rc >= 0 ? (jlong)val : fallback;
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_aisub_player_media_MpvNativeBridge_nativeGetPropertyDouble(
    JNIEnv* env, jobject thiz, jlong handle, jstring jname, jdouble fallback
) {
    (void)thiz;
    MpvInstance* inst = reinterpret_cast<MpvInstance*>(handle);
    if (!inst || !inst->mpv || !jname) return fallback;
    const char* name = env->GetStringUTFChars(jname, nullptr);
    double val = 0.0;
    int rc = g_api.get_property(inst->mpv, name, MPV_FORMAT_DOUBLE, &val);
    env->ReleaseStringUTFChars(jname, name);
    return rc >= 0 ? (jdouble)val : fallback;
}

static void run_mpv_command_internal(JNIEnv* env, MpvInstance* inst, jobjectArray jargs, bool sync_mode) {
    if (!inst || !inst->mpv || !jargs) return;
    jsize len = env->GetArrayLength(jargs);
    if (len <= 0 || len > 32) return;

    const char* cargs[33];
    jstring jstrs[32];
    for (jsize i = 0; i < len; ++i) {
        jstrs[i] = static_cast<jstring>(env->GetObjectArrayElement(jargs, i));
        cargs[i] = jstrs[i] ? env->GetStringUTFChars(jstrs[i], nullptr) : "";
    }
    cargs[len] = nullptr;

    if (!sync_mode && g_api.command_async) {
        g_api.command_async(inst->mpv, 0, cargs);
    } else {
        g_api.command(inst->mpv, cargs);
    }

    for (jsize i = 0; i < len; ++i) {
        if (jstrs[i]) {
            env->ReleaseStringUTFChars(jstrs[i], cargs[i]);
            env->DeleteLocalRef(jstrs[i]);
        }
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_aisub_player_media_MpvNativeBridge_nativeCommand(
    JNIEnv* env, jobject thiz, jlong handle, jobjectArray jargs
) {
    (void)thiz;
    run_mpv_command_internal(env, reinterpret_cast<MpvInstance*>(handle), jargs, false);
}

extern "C" JNIEXPORT void JNICALL
Java_com_aisub_player_media_MpvNativeBridge_nativeCommandSync(
    JNIEnv* env, jobject thiz, jlong handle, jobjectArray jargs
) {
    (void)thiz;
    run_mpv_command_internal(env, reinterpret_cast<MpvInstance*>(handle), jargs, true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_aisub_player_media_MpvNativeBridge_nativeAttachSurface(
    JNIEnv* env, jobject thiz, jlong handle, jobject surface
) {
    (void)thiz;
    MpvInstance* inst = reinterpret_cast<MpvInstance*>(handle);
    if (!inst || !inst->mpv || !surface) return;

    pthread_mutex_lock(&inst->surface_lock);
    jobject old_surface = inst->surface_global;
    jobject new_surface = env->NewGlobalRef(surface);
    inst->surface_global = new_surface;
    int64_t wid = static_cast<int64_t>(reinterpret_cast<intptr_t>(new_surface));
    if (inst->initialized) {
        // 先切回 vo=null 确保旧 VO 完全解绑旧 Surface，再绑定新 wid 并开启 gpu-next/gpu 渲染管线
        if (old_surface) {
            g_api.set_property_string(inst->mpv, "vo", "null");
        }
        g_api.set_property(inst->mpv, "wid", MPV_FORMAT_INT64, &wid);
        g_api.set_property_string(inst->mpv, "force-window", "yes");
        g_api.set_property_string(inst->mpv, "vo", "gpu-next,gpu");
    } else {
        g_api.set_option(inst->mpv, "wid", MPV_FORMAT_INT64, &wid);
        g_api.set_option_string(inst->mpv, "force-window", "yes");
        g_api.set_option_string(inst->mpv, "vo", "gpu-next,gpu");
    }
    if (old_surface) {
        env->DeleteGlobalRef(old_surface);
    }
    pthread_mutex_unlock(&inst->surface_lock);
}

extern "C" JNIEXPORT void JNICALL
Java_com_aisub_player_media_MpvNativeBridge_nativeDetachSurface(
    JNIEnv* env, jobject thiz, jlong handle
) {
    (void)thiz;
    MpvInstance* inst = reinterpret_cast<MpvInstance*>(handle);
    if (!inst || !inst->mpv) return;

    pthread_mutex_lock(&inst->surface_lock);
    int64_t wid = 0;
    if (inst->initialized) {
        // 必须先关闭 force-window 并切换 vo=null，触发 libmpv 同步销毁 VO 渲染线程与 EGLSurface / ANativeWindow，
        // 随后再将 wid 置 0 并释放 JNI GlobalRef，彻底避免 surfaceDestroyed / 切核时访问已删除引用导致闪退
        g_api.set_property_string(inst->mpv, "force-window", "no");
        g_api.set_property_string(inst->mpv, "vid", "no");
        g_api.set_property_string(inst->mpv, "vo", "null");
        g_api.set_property(inst->mpv, "wid", MPV_FORMAT_INT64, &wid);
    } else {
        g_api.set_option_string(inst->mpv, "force-window", "no");
        g_api.set_option_string(inst->mpv, "vo", "null");
        g_api.set_option(inst->mpv, "wid", MPV_FORMAT_INT64, &wid);
    }
    if (inst->surface_global) {
        env->DeleteGlobalRef(inst->surface_global);
        inst->surface_global = nullptr;
    }
    pthread_mutex_unlock(&inst->surface_lock);
}

extern "C" JNIEXPORT void JNICALL
Java_com_aisub_player_media_MpvNativeBridge_nativeDestroy(
    JNIEnv* env, jobject thiz, jlong handle
) {
    (void)thiz;
    MpvInstance* inst = reinterpret_cast<MpvInstance*>(handle);
    if (!inst) return;

    inst->stopping = 1;
    if (inst->mpv && g_api.wakeup) {
        g_api.wakeup(inst->mpv);
    }
    if (inst->thread_started) {
        pthread_join(inst->event_thread, nullptr);
        inst->thread_started = false;
    }

    pthread_mutex_lock(&inst->surface_lock);
    if (inst->mpv) {
        int64_t wid = 0;
        if (inst->initialized && g_api.set_property_string && g_api.set_property) {
            g_api.set_property_string(inst->mpv, "force-window", "no");
            g_api.set_property_string(inst->mpv, "vid", "no");
            g_api.set_property_string(inst->mpv, "vo", "null");
            g_api.set_property(inst->mpv, "wid", MPV_FORMAT_INT64, &wid);
        } else if (g_api.set_option && g_api.set_option_string) {
            g_api.set_option_string(inst->mpv, "force-window", "no");
            g_api.set_option_string(inst->mpv, "vo", "null");
            g_api.set_option(inst->mpv, "wid", MPV_FORMAT_INT64, &wid);
        }
    }
    if (inst->surface_global) {
        env->DeleteGlobalRef(inst->surface_global);
        inst->surface_global = nullptr;
    }
    pthread_mutex_unlock(&inst->surface_lock);

    if (inst->mpv && g_api.terminate_destroy) {
        g_api.terminate_destroy(inst->mpv);
        inst->mpv = nullptr;
    }
    if (inst->callback_global) {
        env->DeleteGlobalRef(inst->callback_global);
        inst->callback_global = nullptr;
    }
    pthread_mutex_destroy(&inst->surface_lock);
    free(inst);
}
