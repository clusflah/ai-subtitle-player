#include <dlfcn.h>
#include <jni.h>
#include <math.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#ifdef __ANDROID__
#include <android/log.h>
#include <sys/system_properties.h>
#define LOG_TAG "Voxtitle-VlcBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#include <stdio.h>
#define LOGI(...) do { fprintf(stdout, "[INFO] " __VA_ARGS__); fprintf(stdout, "\n"); } while (0)
#define LOGW(...) do { fprintf(stderr, "[WARN] " __VA_ARGS__); fprintf(stderr, "\n"); } while (0)
#define LOGE(...) do { fprintf(stderr, "[ERR]  " __VA_ARGS__); fprintf(stderr, "\n"); } while (0)
#endif

#ifndef RTLD_NOLOAD
#define RTLD_NOLOAD 0x00004
#endif

#if defined(__has_include) && __has_include(<aaudio/AAudio.h>)
#include <aaudio/AAudio.h>
#else
typedef struct AAudioStreamStruct AAudioStream;
typedef struct AAudioStreamBuilderStruct AAudioStreamBuilder;
typedef int32_t aaudio_result_t;
typedef int32_t aaudio_direction_t;
typedef int32_t aaudio_format_t;
typedef int32_t aaudio_sharing_mode_t;
typedef int32_t aaudio_performance_mode_t;
typedef int32_t aaudio_stream_state_t;
typedef int32_t aaudio_usage_t;
typedef int32_t aaudio_content_type_t;
enum {
    AAUDIO_OK = 0,
    AAUDIO_DIRECTION_OUTPUT = 0,
    AAUDIO_FORMAT_PCM_I16 = 1,
    AAUDIO_FORMAT_PCM_FLOAT = 2,
    AAUDIO_SHARING_MODE_EXCLUSIVE = 0,
    AAUDIO_SHARING_MODE_SHARED = 1,
    AAUDIO_PERFORMANCE_MODE_NONE = 10,
    AAUDIO_PERFORMANCE_MODE_POWER_SAVING = 11,
    AAUDIO_PERFORMANCE_MODE_LOW_LATENCY = 12,
    AAUDIO_USAGE_MEDIA = 1,
    AAUDIO_CONTENT_TYPE_MOVIE = 3,
};
#endif

/**
 * VLC 原生非阻塞音频分流桥（兼容 -DANDROID_STL=none，零 C++ 标准库符号依赖）：
 * 1. 适配 LibVLC 3.0.25 (amem "S16N")：onVlcAudioPlay 100% 非阻塞，仅做微秒级单点爆音净化并写入 4 秒容量 PCM 环形缓冲区，
 *    彻底避免阻塞 VLC DecoderThread 触发 dec.c (advance < -60ms) 锯齿状丢包电流声；
 * 2. 独立 C++ 音频渲染线程 (renderThreadMain) 基于与 VLC mdate() 同源的 CLOCK_MONOTONIC 对齐首包 PTS 起播，
 *    在环形缓冲内维持 ~1.0–1.5s 抗抖动水位，吸收 4K 50fps HDR 解码与 HLS/TS 直播分片网络波动；
 *    同时内置 >2.8s 高水位 1.002x 线性插值微调，消除长时间直播晶振时钟漂移积压；
 * 3. 输出后端支持 Android 8.1+ C++ AAudio (AAUDIO_PERFORMANCE_MODE_NONE) 与老电视 Java AudioTrack 自动回退；
 * 4. 开启 AI 字幕识别时，在 C++ 层将净化后的原始音频混音并抗混叠重采样为 16kHz 单声道 float PCM 分批送入识别引擎。
 */

// ---------------- libVLC C API 动态符号定义 ----------------

typedef struct libvlc_media_player_t libvlc_media_player_t;

typedef void (*libvlc_audio_play_cb)(void* data, const void* samples, unsigned count, int64_t pts);
typedef void (*libvlc_audio_pause_cb)(void* data, int64_t pts);
typedef void (*libvlc_audio_resume_cb)(void* data, int64_t pts);
typedef void (*libvlc_audio_flush_cb)(void* data, int64_t pts);
typedef void (*libvlc_audio_drain_cb)(void* data);
typedef int (*libvlc_audio_set_volume_cb)(void* data, float volume, bool mute);
typedef int (*libvlc_audio_setup_cb)(void** data, char* format, unsigned* rate, unsigned* channels);
typedef void (*libvlc_audio_cleanup_cb)(void* data);

typedef void (*pfn_libvlc_audio_set_callbacks)(
    libvlc_media_player_t* mp,
    libvlc_audio_play_cb play,
    libvlc_audio_pause_cb pause,
    libvlc_audio_resume_cb resume,
    libvlc_audio_flush_cb flush,
    libvlc_audio_drain_cb drain,
    void* opaque
);
typedef void (*pfn_libvlc_audio_set_format_callbacks)(
    libvlc_media_player_t* mp,
    libvlc_audio_setup_cb setup,
    libvlc_audio_cleanup_cb cleanup
);
typedef void (*pfn_libvlc_audio_set_volume_callback)(
    libvlc_media_player_t* mp,
    libvlc_audio_set_volume_cb set_volume
);
typedef int (*pfn_libvlc_audio_set_delay)(libvlc_media_player_t* mp, int64_t i_delay);
typedef int (*pfn_libvlc_audio_output_set)(libvlc_media_player_t* mp, const char* psz_name);

struct LibVlcApi {
    bool initialized;
    bool available;
    void* handle;
    pfn_libvlc_audio_set_callbacks set_callbacks;
    pfn_libvlc_audio_set_format_callbacks set_format_callbacks;
    pfn_libvlc_audio_set_volume_callback set_volume_callback;
    pfn_libvlc_audio_set_delay set_delay;
    pfn_libvlc_audio_output_set output_set;
};

// ---------------- AAudio C API 动态符号定义 ----------------

typedef aaudio_result_t (*pfn_AAudio_createStreamBuilder)(AAudioStreamBuilder** builder);
typedef aaudio_result_t (*pfn_AAudioStreamBuilder_delete)(AAudioStreamBuilder* builder);
typedef void (*pfn_AAudioStreamBuilder_setDirection)(AAudioStreamBuilder* builder, aaudio_direction_t direction);
typedef void (*pfn_AAudioStreamBuilder_setSharingMode)(AAudioStreamBuilder* builder, aaudio_sharing_mode_t sharingMode);
typedef void (*pfn_AAudioStreamBuilder_setSampleRate)(AAudioStreamBuilder* builder, int32_t sampleRate);
typedef void (*pfn_AAudioStreamBuilder_setChannelCount)(AAudioStreamBuilder* builder, int32_t channelCount);
typedef void (*pfn_AAudioStreamBuilder_setFormat)(AAudioStreamBuilder* builder, aaudio_format_t format);
typedef void (*pfn_AAudioStreamBuilder_setPerformanceMode)(AAudioStreamBuilder* builder, aaudio_performance_mode_t mode);
typedef void (*pfn_AAudioStreamBuilder_setBufferCapacityInFrames)(AAudioStreamBuilder* builder, int32_t numFrames);
typedef void (*pfn_AAudioStreamBuilder_setUsage)(AAudioStreamBuilder* builder, aaudio_usage_t usage);
typedef void (*pfn_AAudioStreamBuilder_setContentType)(AAudioStreamBuilder* builder, aaudio_content_type_t contentType);
typedef aaudio_result_t (*pfn_AAudioStreamBuilder_openStream)(AAudioStreamBuilder* builder, AAudioStream** stream);
typedef aaudio_result_t (*pfn_AAudioStream_requestStart)(AAudioStream* stream);
typedef aaudio_result_t (*pfn_AAudioStream_requestPause)(AAudioStream* stream);
typedef aaudio_result_t (*pfn_AAudioStream_requestFlush)(AAudioStream* stream);
typedef aaudio_result_t (*pfn_AAudioStream_requestStop)(AAudioStream* stream);
typedef aaudio_result_t (*pfn_AAudioStream_write)(
    AAudioStream* stream,
    const void* buffer,
    int32_t numFrames,
    int64_t timeoutNanoseconds
);
typedef aaudio_result_t (*pfn_AAudioStream_close)(AAudioStream* stream);
typedef int32_t (*pfn_AAudioStream_getSampleRate)(AAudioStream* stream);
typedef int32_t (*pfn_AAudioStream_getChannelCount)(AAudioStream* stream);
typedef aaudio_format_t (*pfn_AAudioStream_getFormat)(AAudioStream* stream);
typedef int32_t (*pfn_AAudioStream_getBufferCapacityInFrames)(AAudioStream* stream);
typedef aaudio_result_t (*pfn_AAudioStream_setBufferSizeInFrames)(AAudioStream* stream, int32_t numFrames);

struct AAudioApi {
    bool initialized;
    bool available;
    void* handle;
    pfn_AAudio_createStreamBuilder createStreamBuilder;
    pfn_AAudioStreamBuilder_delete builderDelete;
    pfn_AAudioStreamBuilder_setDirection builderSetDirection;
    pfn_AAudioStreamBuilder_setSharingMode builderSetSharingMode;
    pfn_AAudioStreamBuilder_setSampleRate builderSetSampleRate;
    pfn_AAudioStreamBuilder_setChannelCount builderSetChannelCount;
    pfn_AAudioStreamBuilder_setFormat builderSetFormat;
    pfn_AAudioStreamBuilder_setPerformanceMode builderSetPerformanceMode;
    pfn_AAudioStreamBuilder_setBufferCapacityInFrames builderSetBufferCapacityInFrames;
    pfn_AAudioStreamBuilder_setUsage builderSetUsage;
    pfn_AAudioStreamBuilder_setContentType builderSetContentType;
    pfn_AAudioStreamBuilder_openStream builderOpenStream;
    pfn_AAudioStream_requestStart streamRequestStart;
    pfn_AAudioStream_requestPause streamRequestPause;
    pfn_AAudioStream_requestFlush streamRequestFlush;
    pfn_AAudioStream_requestStop streamRequestStop;
    pfn_AAudioStream_write streamWrite;
    pfn_AAudioStream_close streamClose;
    pfn_AAudioStream_getSampleRate streamGetSampleRate;
    pfn_AAudioStream_getChannelCount streamGetChannelCount;
    pfn_AAudioStream_getFormat streamGetFormat;
    pfn_AAudioStream_getBufferCapacityInFrames streamGetBufferCapacityInFrames;
    pfn_AAudioStream_setBufferSizeInFrames streamSetBufferSizeInFrames;
};

static pthread_mutex_t g_api_mutex = PTHREAD_MUTEX_INITIALIZER;
static LibVlcApi g_vlc = {};
static AAudioApi g_aaudio = {};
static JavaVM* g_jvm = nullptr;
static pthread_key_t g_jni_thread_key;
static pthread_once_t g_jni_key_once = PTHREAD_ONCE_INIT;

static const int TARGET_ASR_RATE = 16000;
static const int ASR_BATCH_SAMPLES = 1600; // 100ms @ 16kHz
static const int RING_BUFFER_SECONDS = 4;  // 4.0s 环形缓冲容量，彻底容纳 VLC live-caching 瞬时解码突发
static const int64_t AAUDIO_WRITE_SLICE_NS = 40LL * 1000000LL; // 单次写入分片超时 40ms，切台/Flush 秒退
static const int64_t AAUDIO_HW_LATENCY_US = 120000LL;          // AAudio 标准共享模式声学延迟约 120ms
static const int64_t AUDIOTRACK_HW_LATENCY_US = 160000LL;      // AudioTrack 流模式声学延迟约 160ms

static int64_t monotonicUs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000LL + static_cast<int64_t>(ts.tv_nsec / 1000L);
}

static void condTimedWaitMs(pthread_cond_t* cond, pthread_mutex_t* mutex, int waitMs) {
    if (waitMs <= 0) return;
    if (waitMs > 15) waitMs = 15;
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    int64_t addNs = static_cast<int64_t>(waitMs) * 1000000LL + static_cast<int64_t>(ts.tv_nsec);
    ts.tv_sec += static_cast<time_t>(addNs / 1000000000LL);
    ts.tv_nsec = static_cast<long>(addNs % 1000000000LL);
    pthread_cond_timedwait(cond, mutex, &ts);
}

static void detachCurrentThreadDestructor(void* value) {
    if (value != nullptr && g_jvm != nullptr) {
        g_jvm->DetachCurrentThread();
    }
}

static void initJniThreadKey() {
    pthread_key_create(&g_jni_thread_key, detachCurrentThreadDestructor);
}

static JNIEnv* getThreadEnv() {
    if (g_jvm == nullptr) return nullptr;
    JNIEnv* env = nullptr;
    jint status = g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (status == JNI_OK) return env;
    if (status == JNI_EDETACHED) {
        pthread_once(&g_jni_key_once, initJniThreadKey);
#ifdef __ANDROID__
        const jint attachRes = g_jvm->AttachCurrentThread(&env, nullptr);
#else
        const jint attachRes = g_jvm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr);
#endif
        if (attachRes == JNI_OK) {
            pthread_setspecific(g_jni_thread_key, reinterpret_cast<void*>(1));
            return env;
        }
    }
    return nullptr;
}

static void detachThreadEnvIfOwned() {
    pthread_once(&g_jni_key_once, initJniThreadKey);
    if (pthread_getspecific(g_jni_thread_key) != nullptr) {
        pthread_setspecific(g_jni_thread_key, nullptr);
        if (g_jvm != nullptr) {
            g_jvm->DetachCurrentThread();
        }
    }
}

static int detectAndroidSdkInt() {
#ifdef __ANDROID__
    char sdkStr[PROP_VALUE_MAX] = {0};
    if (__system_property_get("ro.build.version.sdk", sdkStr) > 0) {
        return atoi(sdkStr);
    }
    return 23;
#else
    const char* mockSdk = getenv("MOCK_ANDROID_SDK_INT");
    return mockSdk ? atoi(mockSdk) : 29;
#endif
}

static bool ensureAAudioApiLocked() {
    if (g_aaudio.initialized) return g_aaudio.available;
    g_aaudio.initialized = true;

    int sdk = detectAndroidSdkInt();
    // Android 8.0 (API 26) 初版 AAudio 存在已知稳定性缺陷；仅在 Android 8.1+ (API >= 27) 探测启用 AAudio
    if (sdk < 27) {
        LOGI("当前系统 API %d (< 27)，跳过 AAudio，启用 Java AudioTrack 异步环形缓冲路径", sdk);
        return false;
    }

    const char* libName = "libaaudio.so";
#ifndef __ANDROID__
    const char* overrideLib = getenv("MOCK_AAUDIO_LIB");
    if (overrideLib != nullptr) libName = overrideLib;
#endif

    void* h = dlopen(libName, RTLD_LAZY);
    if (!h) {
        LOGI("dlopen(%s) 返回空，回退到 Java AudioTrack 路径", libName);
        return false;
    }

    g_aaudio.handle = h;
    g_aaudio.createStreamBuilder = reinterpret_cast<pfn_AAudio_createStreamBuilder>(dlsym(h, "AAudio_createStreamBuilder"));
    g_aaudio.builderDelete = reinterpret_cast<pfn_AAudioStreamBuilder_delete>(dlsym(h, "AAudioStreamBuilder_delete"));
    g_aaudio.builderSetDirection = reinterpret_cast<pfn_AAudioStreamBuilder_setDirection>(dlsym(h, "AAudioStreamBuilder_setDirection"));
    g_aaudio.builderSetSharingMode = reinterpret_cast<pfn_AAudioStreamBuilder_setSharingMode>(dlsym(h, "AAudioStreamBuilder_setSharingMode"));
    g_aaudio.builderSetSampleRate = reinterpret_cast<pfn_AAudioStreamBuilder_setSampleRate>(dlsym(h, "AAudioStreamBuilder_setSampleRate"));
    g_aaudio.builderSetChannelCount = reinterpret_cast<pfn_AAudioStreamBuilder_setChannelCount>(dlsym(h, "AAudioStreamBuilder_setChannelCount"));
    g_aaudio.builderSetFormat = reinterpret_cast<pfn_AAudioStreamBuilder_setFormat>(dlsym(h, "AAudioStreamBuilder_setFormat"));
    g_aaudio.builderSetPerformanceMode = reinterpret_cast<pfn_AAudioStreamBuilder_setPerformanceMode>(dlsym(h, "AAudioStreamBuilder_setPerformanceMode"));
    g_aaudio.builderSetBufferCapacityInFrames = reinterpret_cast<pfn_AAudioStreamBuilder_setBufferCapacityInFrames>(dlsym(h, "AAudioStreamBuilder_setBufferCapacityInFrames"));
    g_aaudio.builderSetUsage = reinterpret_cast<pfn_AAudioStreamBuilder_setUsage>(dlsym(h, "AAudioStreamBuilder_setUsage"));
    g_aaudio.builderSetContentType = reinterpret_cast<pfn_AAudioStreamBuilder_setContentType>(dlsym(h, "AAudioStreamBuilder_setContentType"));
    g_aaudio.builderOpenStream = reinterpret_cast<pfn_AAudioStreamBuilder_openStream>(dlsym(h, "AAudioStreamBuilder_openStream"));
    g_aaudio.streamRequestStart = reinterpret_cast<pfn_AAudioStream_requestStart>(dlsym(h, "AAudioStream_requestStart"));
    g_aaudio.streamRequestPause = reinterpret_cast<pfn_AAudioStream_requestPause>(dlsym(h, "AAudioStream_requestPause"));
    g_aaudio.streamRequestFlush = reinterpret_cast<pfn_AAudioStream_requestFlush>(dlsym(h, "AAudioStream_requestFlush"));
    g_aaudio.streamRequestStop = reinterpret_cast<pfn_AAudioStream_requestStop>(dlsym(h, "AAudioStream_requestStop"));
    g_aaudio.streamWrite = reinterpret_cast<pfn_AAudioStream_write>(dlsym(h, "AAudioStream_write"));
    g_aaudio.streamClose = reinterpret_cast<pfn_AAudioStream_close>(dlsym(h, "AAudioStream_close"));
    g_aaudio.streamGetSampleRate = reinterpret_cast<pfn_AAudioStream_getSampleRate>(dlsym(h, "AAudioStream_getSampleRate"));
    g_aaudio.streamGetChannelCount = reinterpret_cast<pfn_AAudioStream_getChannelCount>(dlsym(h, "AAudioStream_getChannelCount"));
    g_aaudio.streamGetFormat = reinterpret_cast<pfn_AAudioStream_getFormat>(dlsym(h, "AAudioStream_getFormat"));
    g_aaudio.streamGetBufferCapacityInFrames = reinterpret_cast<pfn_AAudioStream_getBufferCapacityInFrames>(dlsym(h, "AAudioStream_getBufferCapacityInFrames"));
    g_aaudio.streamSetBufferSizeInFrames = reinterpret_cast<pfn_AAudioStream_setBufferSizeInFrames>(dlsym(h, "AAudioStream_setBufferSizeInFrames"));

    if (!g_aaudio.createStreamBuilder || !g_aaudio.builderDelete ||
        !g_aaudio.builderSetDirection || !g_aaudio.builderSetSharingMode ||
        !g_aaudio.builderSetSampleRate || !g_aaudio.builderSetChannelCount ||
        !g_aaudio.builderSetFormat || !g_aaudio.builderSetPerformanceMode ||
        !g_aaudio.builderOpenStream || !g_aaudio.streamRequestStart ||
        !g_aaudio.streamRequestPause || !g_aaudio.streamRequestFlush ||
        !g_aaudio.streamRequestStop || !g_aaudio.streamWrite ||
        !g_aaudio.streamClose || !g_aaudio.streamGetSampleRate ||
        !g_aaudio.streamGetChannelCount) {
        LOGW("libaaudio.so 关键符号不完整，回退到 Java AudioTrack 路径");
        dlclose(h);
        memset(&g_aaudio, 0, sizeof(g_aaudio));
        g_aaudio.initialized = true;
        return false;
    }

    g_aaudio.available = true;
    LOGI("AAudio 动态探测成功（API %d），启用 C++ AAudio 异步环形缓冲路径", sdk);
    return true;
}

static bool isAAudioAvailable() {
    pthread_mutex_lock(&g_api_mutex);
    bool ok = ensureAAudioApiLocked();
    pthread_mutex_unlock(&g_api_mutex);
    return ok;
}

static bool ensureLibVlcApi() {
    pthread_mutex_lock(&g_api_mutex);
    if (g_vlc.initialized) {
        bool ok = g_vlc.available;
        pthread_mutex_unlock(&g_api_mutex);
        return ok;
    }
    g_vlc.initialized = true;

    const char* vlcLibName = "libvlc.so";
#ifndef __ANDROID__
    const char* overrideLib = getenv("MOCK_VLC_LIB");
    if (overrideLib != nullptr) vlcLibName = overrideLib;
#endif

    void* h = dlopen(vlcLibName, RTLD_NOW | RTLD_NOLOAD);
    if (!h) h = dlopen(vlcLibName, RTLD_LAZY);
    void* lookup = h ? h : RTLD_DEFAULT;

    g_vlc.handle = h;
    g_vlc.set_callbacks = reinterpret_cast<pfn_libvlc_audio_set_callbacks>(dlsym(lookup, "libvlc_audio_set_callbacks"));
    g_vlc.set_format_callbacks = reinterpret_cast<pfn_libvlc_audio_set_format_callbacks>(dlsym(lookup, "libvlc_audio_set_format_callbacks"));
    g_vlc.set_volume_callback = reinterpret_cast<pfn_libvlc_audio_set_volume_callback>(dlsym(lookup, "libvlc_audio_set_volume_callback"));
    g_vlc.set_delay = reinterpret_cast<pfn_libvlc_audio_set_delay>(dlsym(lookup, "libvlc_audio_set_delay"));
    g_vlc.output_set = reinterpret_cast<pfn_libvlc_audio_output_set>(dlsym(lookup, "libvlc_audio_output_set"));

    g_vlc.available = (g_vlc.set_callbacks != nullptr && g_vlc.set_format_callbacks != nullptr);
    if (!g_vlc.available) {
        LOGE("未能解析 libvlc_audio_set_callbacks / libvlc_audio_set_format_callbacks");
    }
    bool ok = g_vlc.available;
    pthread_mutex_unlock(&g_api_mutex);
    return ok;
}

// ---------------- 流式抗混叠重采样器（任意采样率 -> 16kHz 单声道 float） ----------------

struct StreamingResampler16k {
    int inRate;
    double step;
    double pos;
    float prevSample;
    bool hasPrev;

    void reset(int sampleRate) {
        inRate = sampleRate > 0 ? sampleRate : TARGET_ASR_RATE;
        step = static_cast<double>(inRate) / static_cast<double>(TARGET_ASR_RATE);
        pos = 0.0;
        prevSample = 0.0f;
        hasPrev = false;
    }

    int maxOutputFor(int frames) const {
        if (frames <= 0) return 0;
        return static_cast<int>(static_cast<double>(frames) / step) + 8;
    }

    int process(const float* monoIn, int frames, float* outBuf, int outCapacity) {
        if (frames <= 0 || outCapacity <= 0) return 0;
        if (inRate == TARGET_ASR_RATE) {
            int toCopy = frames < outCapacity ? frames : outCapacity;
            memcpy(outBuf, monoIn, static_cast<size_t>(toCopy) * sizeof(float));
            return toCopy;
        }

        int produced = 0;
        if (!hasPrev) {
            prevSample = monoIn[0];
            hasPrev = true;
        }

        while (pos < static_cast<double>(frames - 1) && produced < outCapacity) {
            int i = static_cast<int>(floor(pos));
            float frac = static_cast<float>(pos - static_cast<double>(i));
            float a = (i < 0) ? prevSample : monoIn[i];
            float b = monoIn[i + 1];
            float interp = a + (b - a) * frac;

            if (step >= 1.8 && i >= 0 && (i + 2) < frames) {
                float c = monoIn[i + 2];
                outBuf[produced++] = 0.5f * interp + 0.25f * a + 0.25f * c;
            } else {
                outBuf[produced++] = interp;
            }
            pos += step;
        }

        pos -= static_cast<double>(frames);
        if (pos < -1.0) pos = -1.0;
        prevSample = monoIn[frames - 1];
        return produced;
    }
};

// ---------------- 桥接实例上下文 ----------------

enum OutputBackend {
    BACKEND_NONE = 0,
    BACKEND_AAUDIO = 1,
    BACKEND_JAVA_AUDIOTRACK = 2,
};

struct BridgeContext {
    // 锁顺序严格分层：ringMutex 为纯内存叶子锁（绝不在持有 ringMutex 时加其它锁或调用 JNI/AAudio）
    pthread_mutex_t hwMutex;
    pthread_mutex_t asrMutex;
    pthread_mutex_t ringMutex;
    pthread_cond_t ringCond;

    pthread_t renderThread;
    bool renderThreadRunning;

    libvlc_media_player_t* mp;
    jobject callbackGlobal;

    jmethodID midOnAudioSetup;
    jmethodID midOnAudioWrite;
    jmethodID midOnAudioPause;
    jmethodID midOnAudioResume;
    jmethodID midOnAudioFlush;
    jmethodID midOnAudioCleanup;
    jmethodID midOnAsrPcm;
    jmethodID midOnDiscontinuity;

    // 硬件输出与渲染线程专用缓冲区（受 hwMutex / renderThread 保护）
    jshortArray javaPcmArrayGlobal;
    int javaPcmCapacity;

    int16_t* renderChunkBuf;
    int renderChunkCapacity;

    float* renderFloatBuf;
    int renderFloatCapacity;

    // 非阻塞环形缓冲区（受 ringMutex 保护）
    int16_t* ringBuf;
    int ringCapacityFrames;
    int ringReadFrame;
    int ringWriteFrame;
    int ringAvailableFrames;
    unsigned ringSampleRate;
    unsigned ringChannels;
    int64_t targetStartUs;
    int64_t pauseStartedUs;
    bool preRollDone;
    bool renderPaused;
    bool draining;
    bool renderStop;
    bool driftActive;
    double driftFrac;
    int fadeInRemaining;
    int fadeInTotal;

    // AI 字幕重采样分流工作区（受 asrMutex 保护）
    float* floatWorkBuf;
    int floatWorkCapacity;
    float* monoScratchBuf;
    int monoScratchCapacity;
    float* resampledScratchBuf;
    int resampledScratchCapacity;
    float asrBatchBuf[ASR_BATCH_SAMPLES];
    int asrBatchCount;
    StreamingResampler16k resampler;

    // 硬件流状态（受 hwMutex 保护）
    AAudioStream* aaudioStream;
    bool aaudioStarted;
    bool aaudioUsesFloat;
    OutputBackend backend;
    unsigned sampleRate;
    unsigned channels;

    volatile int asrEnabled;
    volatile int detached;
    volatile int flushSerial;
    volatile int volumeMilli; // 0..1500 代表 0.0f..1.5f
    volatile int muted;
};

static bool ensureShortCapacity(int16_t** buf, int* cap, int needed) {
    if (needed <= *cap) return true;
    int nextCap = needed < 4096 ? 4096 : needed * 2;
    int16_t* next = static_cast<int16_t*>(realloc(*buf, static_cast<size_t>(nextCap) * sizeof(int16_t)));
    if (!next) return false;
    *buf = next;
    *cap = nextCap;
    return true;
}

static bool ensureFloatCapacity(float** buf, int* cap, int needed) {
    if (needed <= *cap) return true;
    int nextCap = needed < 4096 ? 4096 : needed * 2;
    float* next = static_cast<float*>(realloc(*buf, static_cast<size_t>(nextCap) * sizeof(float)));
    if (!next) return false;
    *buf = next;
    *cap = nextCap;
    return true;
}

static inline float softLimitGainSample(float s) {
    float a = s < 0.0f ? -s : s;
    if (a <= 0.88f) return s;
    float over = a - 0.88f;
    float compressed = 0.88f + (0.12f * over) / (0.12f + over);
    return s < 0.0f ? -compressed : compressed;
}

static inline int16_t floatToClampedI16(float s) {
    float scaled = s * 32767.0f;
    if (scaled >= 32767.0f) return 32767;
    if (scaled <= -32768.0f) return -32768;
    return static_cast<int16_t>(scaled >= 0.0f ? (scaled + 0.5f) : (scaled - 0.5f));
}

static void armFadeInRingLocked(BridgeContext* ctx, unsigned rate, int durationMs) {
    int frames = static_cast<int>((rate * static_cast<unsigned>(durationMs)) / 1000u);
    if (frames < 32) frames = 32;
    ctx->fadeInTotal = frames;
    ctx->fadeInRemaining = frames;
}

static jshortArray ensureJavaShortArray(JNIEnv* env, BridgeContext* ctx, int neededShorts) {
    if (ctx->javaPcmArrayGlobal != nullptr && ctx->javaPcmCapacity >= neededShorts) {
        return ctx->javaPcmArrayGlobal;
    }
    int nextCap = neededShorts < 8192 ? 8192 : neededShorts * 2;
    jshortArray localArr = env->NewShortArray(nextCap);
    if (!localArr || env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    if (ctx->javaPcmArrayGlobal != nullptr) {
        env->DeleteGlobalRef(ctx->javaPcmArrayGlobal);
    }
    ctx->javaPcmArrayGlobal = static_cast<jshortArray>(env->NewGlobalRef(localArr));
    env->DeleteLocalRef(localArr);
    ctx->javaPcmCapacity = ctx->javaPcmArrayGlobal ? nextCap : 0;
    return ctx->javaPcmArrayGlobal;
}

static void flushAsrBatchToJava(JNIEnv* env, BridgeContext* ctx) {
    if (ctx->asrBatchCount <= 0) return;
    if (!env || !ctx->callbackGlobal || !ctx->midOnAsrPcm) {
        ctx->asrBatchCount = 0;
        return;
    }
    int count = ctx->asrBatchCount;
    ctx->asrBatchCount = 0;
    jfloatArray arr = env->NewFloatArray(count);
    if (!arr || env->ExceptionCheck()) {
        env->ExceptionClear();
        return;
    }
    env->SetFloatArrayRegion(arr, 0, count, ctx->asrBatchBuf);
    env->CallVoidMethod(ctx->callbackGlobal, ctx->midOnAsrPcm, arr);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    env->DeleteLocalRef(arr);
}

// ---------------- AAudio / Java AudioTrack 硬件输出操作（调用方需持有 hwMutex） ----------------

static void closeAAudioStreamHwLocked(BridgeContext* ctx) {
    if (ctx->aaudioStream != nullptr && g_aaudio.available) {
        g_aaudio.streamRequestStop(ctx->aaudioStream);
        g_aaudio.streamClose(ctx->aaudioStream);
        ctx->aaudioStream = nullptr;
        ctx->aaudioStarted = false;
    }
}

static AAudioStream* tryOpenSingleAAudioStream(
    unsigned rate,
    unsigned channels,
    aaudio_format_t reqFormat,
    int32_t capacityFrames
) {
    AAudioStreamBuilder* builder = nullptr;
    if (g_aaudio.createStreamBuilder(&builder) != AAUDIO_OK || builder == nullptr) {
        return nullptr;
    }

    g_aaudio.builderSetDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    g_aaudio.builderSetSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    g_aaudio.builderSetSampleRate(builder, static_cast<int32_t>(rate));
    g_aaudio.builderSetChannelCount(builder, static_cast<int32_t>(channels));
    g_aaudio.builderSetFormat(builder, reqFormat);
    // 媒体流使用标准共享性能模式（严禁无回调 LOW_LATENCY FastMixer）
    g_aaudio.builderSetPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_NONE);
    if (g_aaudio.builderSetBufferCapacityInFrames && capacityFrames > 0) {
        g_aaudio.builderSetBufferCapacityInFrames(builder, capacityFrames);
    }
    if (g_aaudio.builderSetUsage) {
        g_aaudio.builderSetUsage(builder, AAUDIO_USAGE_MEDIA);
    }
    if (g_aaudio.builderSetContentType) {
        g_aaudio.builderSetContentType(builder, AAUDIO_CONTENT_TYPE_MOVIE);
    }

    AAudioStream* stream = nullptr;
    aaudio_result_t res = g_aaudio.builderOpenStream(builder, &stream);
    g_aaudio.builderDelete(builder);
    if (res != AAUDIO_OK || stream == nullptr) {
        return nullptr;
    }
    return stream;
}

static bool openAAudioStreamHwLocked(BridgeContext* ctx, unsigned* rate, unsigned* channels) {
    if (!isAAudioAvailable()) return false;

    const int32_t wantCapacityFrames = static_cast<int32_t>((*rate * 320u) / 1000u); // 320ms 硬件容量
    bool useFloat = false;
    AAudioStream* stream = tryOpenSingleAAudioStream(*rate, *channels, AAUDIO_FORMAT_PCM_I16, wantCapacityFrames);
    if (stream == nullptr) {
        useFloat = true;
        stream = tryOpenSingleAAudioStream(*rate, *channels, AAUDIO_FORMAT_PCM_FLOAT, wantCapacityFrames);
    }
    if (stream == nullptr) {
        LOGW("AAudio openStream 失败，回退到 Java AudioTrack");
        return false;
    }

    if (g_aaudio.streamGetFormat) {
        aaudio_format_t actualFmt = g_aaudio.streamGetFormat(stream);
        if (actualFmt == AAUDIO_FORMAT_PCM_I16) {
            useFloat = false;
        } else if (actualFmt == AAUDIO_FORMAT_PCM_FLOAT) {
            useFloat = true;
        }
    }

    int32_t actualRate = g_aaudio.streamGetSampleRate(stream);
    int32_t actualChannels = g_aaudio.streamGetChannelCount(stream);
    if (actualRate >= 8000 && actualRate <= 192000) {
        *rate = static_cast<unsigned>(actualRate);
    }
    if (actualChannels == 1 || actualChannels == 2) {
        *channels = static_cast<unsigned>(actualChannels);
    }

    if (g_aaudio.streamSetBufferSizeInFrames) {
        int32_t targetActive = static_cast<int32_t>((*rate * 160u) / 1000u); // 160ms 活跃缓冲（8 个 20ms 渲染分片）
        if (g_aaudio.streamGetBufferCapacityInFrames) {
            int32_t cap = g_aaudio.streamGetBufferCapacityInFrames(stream);
            if (cap > 0 && targetActive > cap) {
                targetActive = cap;
            }
        }
        if (targetActive > 0) {
            g_aaudio.streamSetBufferSizeInFrames(stream, targetActive);
        }
    }

    aaudio_result_t startRes = g_aaudio.streamRequestStart(stream);
    if (startRes != AAUDIO_OK) {
        LOGW("AAudio requestStart 失败（code=%d），关闭并回退到 Java AudioTrack", static_cast<int>(startRes));
        g_aaudio.streamClose(stream);
        return false;
    }

    if (g_vlc.set_delay && ctx->mp) {
        g_vlc.set_delay(ctx->mp, 0);
    }

    ctx->aaudioStream = stream;
    ctx->aaudioStarted = true;
    ctx->aaudioUsesFloat = useFloat;
    ctx->backend = BACKEND_AAUDIO;
    return true;
}

static bool openJavaAudioTrackHwLocked(BridgeContext* ctx, JNIEnv* env, unsigned rate, unsigned channels) {
    if (!env || !ctx->callbackGlobal || !ctx->midOnAudioSetup) return false;
    jboolean ok = env->CallBooleanMethod(
        ctx->callbackGlobal,
        ctx->midOnAudioSetup,
        static_cast<jint>(rate),
        static_cast<jint>(channels)
    );
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    if (!ok) return false;
    if (g_vlc.set_delay && ctx->mp) {
        g_vlc.set_delay(ctx->mp, 0);
    }
    ctx->backend = BACKEND_JAVA_AUDIOTRACK;
    return true;
}

static bool writeAAudioI16HwLocked(
    BridgeContext* ctx,
    const int16_t* pcmI16,
    int frames,
    int ch,
    int expectedSerial
) {
    if (!ctx->aaudioStream || frames <= 0) return false;
    if (!ctx->aaudioStarted) {
        g_aaudio.streamRequestStart(ctx->aaudioStream);
        ctx->aaudioStarted = true;
    }

    const float* pcmFloat = nullptr;
    if (ctx->aaudioUsesFloat) {
        const int totalSamples = frames * ch;
        if (!ensureFloatCapacity(&ctx->renderFloatBuf, &ctx->renderFloatCapacity, totalSamples)) {
            return false;
        }
        const float inv32768 = 1.0f / 32768.0f;
        for (int i = 0; i < totalSamples; ++i) {
            ctx->renderFloatBuf[i] = static_cast<float>(pcmI16[i]) * inv32768;
        }
        pcmFloat = ctx->renderFloatBuf;
    }

    int writtenTotal = 0;
    int zeroRetries = 0;
    while (writtenTotal < frames) {
        if (__atomic_load_n(&ctx->detached, __ATOMIC_ACQUIRE) != 0 ||
            __atomic_load_n(&ctx->flushSerial, __ATOMIC_ACQUIRE) != expectedSerial) {
            return true;
        }
        const void* ptr = ctx->aaudioUsesFloat
            ? static_cast<const void*>(pcmFloat + writtenTotal * ch)
            : static_cast<const void*>(pcmI16 + writtenTotal * ch);
        const int32_t remain = frames - writtenTotal;
        aaudio_result_t w = g_aaudio.streamWrite(ctx->aaudioStream, ptr, remain, AAUDIO_WRITE_SLICE_NS);
        if (w > 0) {
            writtenTotal += static_cast<int>(w);
            zeroRetries = 0;
        } else if (w == 0) {
            if (++zeroRetries > 25) {
                return true;
            }
        } else {
            LOGW("AAudio write 返回错误 %d，准备重建输出流", static_cast<int>(w));
            return false;
        }
    }
    return true;
}

static void writeJavaAudioTrackI16HwLocked(BridgeContext* ctx, JNIEnv* env, const int16_t* pcmI16, int totalSamples) {
    if (!env || !ctx->callbackGlobal || !ctx->midOnAudioWrite || totalSamples <= 0) return;
    jshortArray arr = ensureJavaShortArray(env, ctx, totalSamples);
    if (!arr) return;
    env->SetShortArrayRegion(arr, 0, totalSamples, pcmI16);
    env->CallVoidMethod(ctx->callbackGlobal, ctx->midOnAudioWrite, arr, static_cast<jint>(totalSamples));
    if (env->ExceptionCheck()) env->ExceptionClear();
}

// ---------------- 独立 C++ 异步环形缓冲消费线程 ----------------

static void* renderThreadMain(void* arg) {
    BridgeContext* ctx = static_cast<BridgeContext*>(arg);
    if (!ctx) return nullptr;

    while (true) {
        int outFrames = 0;
        int ch = 0;
        int chunkSerial = 0;
        int fadeStartRemaining = 0;
        int fadeTotal = 0;

        pthread_mutex_lock(&ctx->ringMutex);
        while (!ctx->renderStop) {
            if (ctx->renderPaused) {
                condTimedWaitMs(&ctx->ringCond, &ctx->ringMutex, 15);
                continue;
            }

            const unsigned rate = ctx->ringSampleRate > 0 ? ctx->ringSampleRate : 48000u;
            const int chunkTarget = static_cast<int>((rate * 20u) / 1000u) > 128
                ? static_cast<int>((rate * 20u) / 1000u)
                : 128;
            const int minStartFrames = static_cast<int>((rate * 60u) / 1000u);
            const int maxPreRollFrames = static_cast<int>((rate * 2200u) / 1000u);
            const int64_t nowUs = monotonicUs();

            if (!ctx->preRollDone) {
                bool ready = false;
                if (ctx->ringAvailableFrames > 0) {
                    if (ctx->draining || ctx->ringAvailableFrames >= maxPreRollFrames) {
                        ready = true;
                    } else if (ctx->targetStartUs > 0 &&
                               nowUs >= ctx->targetStartUs &&
                               ctx->ringAvailableFrames >= minStartFrames) {
                        ready = true;
                    }
                }
                if (ready) {
                    ctx->preRollDone = true;
                    break;
                }

                if (ctx->ringAvailableFrames == 0 && ctx->draining) {
                    pthread_cond_broadcast(&ctx->ringCond);
                }

                int waitMs = 12;
                if (ctx->ringAvailableFrames >= minStartFrames && ctx->targetStartUs > nowUs) {
                    int64_t diffMs = (ctx->targetStartUs - nowUs) / 1000LL;
                    waitMs = diffMs < 1LL ? 1 : (diffMs > 15LL ? 15 : static_cast<int>(diffMs));
                }
                condTimedWaitMs(&ctx->ringCond, &ctx->ringMutex, waitMs);
                continue;
            }

            if (ctx->ringAvailableFrames >= chunkTarget || (ctx->draining && ctx->ringAvailableFrames > 0)) {
                break;
            }

            if (ctx->ringAvailableFrames == 0) {
                if (ctx->draining) {
                    pthread_cond_broadcast(&ctx->ringCond);
                } else {
                    // 网络极端中断导致环形缓冲耗尽：恢复供包时自动加 5ms 淡入并等待 80ms 防抖预缓冲
                    ctx->preRollDone = false;
                    ctx->targetStartUs = 0;
                    armFadeInRingLocked(ctx, rate, 5);
                }
            }
            condTimedWaitMs(&ctx->ringCond, &ctx->ringMutex, 10);
        }

        if (ctx->renderStop) {
            pthread_mutex_unlock(&ctx->ringMutex);
            break;
        }

        const unsigned rate = ctx->ringSampleRate > 0 ? ctx->ringSampleRate : 48000u;
        ch = static_cast<int>(ctx->ringChannels > 0 ? ctx->ringChannels : 2u);
        const int capFrames = ctx->ringCapacityFrames;
        int wantFrames = static_cast<int>((rate * 20u) / 1000u);
        if (wantFrames < 128) wantFrames = 128;
        if (wantFrames > ctx->ringAvailableFrames) {
            wantFrames = ctx->ringAvailableFrames;
        }

        const int highWaterFrames = static_cast<int>((rate * 2800u) / 1000u);
        const int normalWaterFrames = static_cast<int>((rate * 2100u) / 1000u);
        if (!ctx->draining && ctx->ringAvailableFrames > highWaterFrames) {
            ctx->driftActive = true;
        } else if (ctx->ringAvailableFrames <= normalWaterFrames || ctx->draining) {
            ctx->driftActive = false;
            ctx->driftFrac = 0.0;
        }

        if (wantFrames > 0 && capFrames > 0 && ctx->ringBuf != nullptr &&
            ensureShortCapacity(&ctx->renderChunkBuf, &ctx->renderChunkCapacity, wantFrames * ch)) {
            chunkSerial = __atomic_load_n(&ctx->flushSerial, __ATOMIC_ACQUIRE);

            if (ctx->driftActive && ctx->ringAvailableFrames > wantFrames + 8) {
                // 长时间直播 PCR 时钟略快于本机声卡晶振时，以 1.002x 线性插值平滑泄放积压，无音调跳变与丢帧爆音
                const double step = 1.002;
                double pos = ctx->driftFrac;
                int produced = 0;
                while (produced < wantFrames) {
                    int idx0 = static_cast<int>(pos);
                    if (idx0 + 1 >= ctx->ringAvailableFrames) break;
                    float frac = static_cast<float>(pos - static_cast<double>(idx0));
                    int r0 = (ctx->ringReadFrame + idx0) % capFrames;
                    int r1 = (r0 + 1) % capFrames;
                    for (int c = 0; c < ch; ++c) {
                        float s0 = static_cast<float>(ctx->ringBuf[r0 * ch + c]);
                        float s1 = static_cast<float>(ctx->ringBuf[r1 * ch + c]);
                        ctx->renderChunkBuf[produced * ch + c] = static_cast<int16_t>(s0 + (s1 - s0) * frac);
                    }
                    produced++;
                    pos += step;
                }
                int consumed = static_cast<int>(pos);
                if (consumed > ctx->ringAvailableFrames) consumed = ctx->ringAvailableFrames;
                ctx->ringReadFrame = (ctx->ringReadFrame + consumed) % capFrames;
                ctx->ringAvailableFrames -= consumed;
                ctx->driftFrac = pos - static_cast<double>(consumed);
                outFrames = produced;
            } else {
                int firstPart = capFrames - ctx->ringReadFrame;
                if (firstPart > wantFrames) firstPart = wantFrames;
                memcpy(
                    ctx->renderChunkBuf,
                    ctx->ringBuf + ctx->ringReadFrame * ch,
                    static_cast<size_t>(firstPart * ch) * sizeof(int16_t)
                );
                int secondPart = wantFrames - firstPart;
                if (secondPart > 0) {
                    memcpy(
                        ctx->renderChunkBuf + firstPart * ch,
                        ctx->ringBuf,
                        static_cast<size_t>(secondPart * ch) * sizeof(int16_t)
                    );
                }
                ctx->ringReadFrame = (ctx->ringReadFrame + wantFrames) % capFrames;
                ctx->ringAvailableFrames -= wantFrames;
                outFrames = wantFrames;
            }

            if (ctx->fadeInRemaining > 0 && ctx->fadeInTotal > 0 && outFrames > 0) {
                fadeStartRemaining = ctx->fadeInRemaining;
                fadeTotal = ctx->fadeInTotal;
                int consumedFade = outFrames < ctx->fadeInRemaining ? outFrames : ctx->fadeInRemaining;
                ctx->fadeInRemaining -= consumedFade;
            }

            if (ctx->ringAvailableFrames == 0 && ctx->draining) {
                pthread_cond_broadcast(&ctx->ringCond);
            }
        }
        pthread_mutex_unlock(&ctx->ringMutex);

        if (outFrames <= 0) continue;

        // 在锁外完成实时音量缩放与起播平滑淡入，确保静音/TTS 压低原声在 ~120ms 内即时生效
        const int totalSamples = outFrames * ch;
        const int muted = __atomic_load_n(&ctx->muted, __ATOMIC_RELAXED);
        const int volMilli = muted ? 0 : __atomic_load_n(&ctx->volumeMilli, __ATOMIC_RELAXED);

        if (volMilli <= 0) {
            memset(ctx->renderChunkBuf, 0, static_cast<size_t>(totalSamples) * sizeof(int16_t));
        } else {
            if (volMilli != 1000) {
                const float gain = static_cast<float>(volMilli) * 0.001f;
                if (volMilli <= 1000) {
                    for (int i = 0; i < totalSamples; ++i) {
                        ctx->renderChunkBuf[i] = static_cast<int16_t>(
                            static_cast<float>(ctx->renderChunkBuf[i]) * gain
                        );
                    }
                } else {
                    const float inv32768 = 1.0f / 32768.0f;
                    for (int i = 0; i < totalSamples; ++i) {
                        float s = static_cast<float>(ctx->renderChunkBuf[i]) * inv32768 * gain;
                        ctx->renderChunkBuf[i] = floatToClampedI16(softLimitGainSample(s));
                    }
                }
            }
            if (fadeStartRemaining > 0 && fadeTotal > 0) {
                const float invTotal = 1.0f / static_cast<float>(fadeTotal);
                int rem = fadeStartRemaining;
                for (int f = 0; f < outFrames && rem > 0; ++f) {
                    const float ramp = static_cast<float>(fadeTotal - rem) * invTotal;
                    const int base = f * ch;
                    for (int c = 0; c < ch; ++c) {
                        ctx->renderChunkBuf[base + c] = static_cast<int16_t>(
                            static_cast<float>(ctx->renderChunkBuf[base + c]) * ramp
                        );
                    }
                    rem--;
                }
            }
        }

        pthread_mutex_lock(&ctx->hwMutex);
        if (__atomic_load_n(&ctx->detached, __ATOMIC_ACQUIRE) == 0 &&
            __atomic_load_n(&ctx->flushSerial, __ATOMIC_ACQUIRE) == chunkSerial) {
            if (ctx->backend == BACKEND_AAUDIO && ctx->aaudioStream != nullptr) {
                if (!writeAAudioI16HwLocked(ctx, ctx->renderChunkBuf, outFrames, ch, chunkSerial)) {
                    // 耳机/HDMI 音频路由热插拔：自动重建 AAudio，失败则无缝回退到 Java AudioTrack
                    closeAAudioStreamHwLocked(ctx);
                    unsigned r = ctx->sampleRate;
                    unsigned c = ctx->channels;
                    if (openAAudioStreamHwLocked(ctx, &r, &c) && r == ctx->sampleRate && c == ctx->channels) {
                        writeAAudioI16HwLocked(ctx, ctx->renderChunkBuf, outFrames, ch, chunkSerial);
                    } else {
                        closeAAudioStreamHwLocked(ctx);
                        JNIEnv* env = getThreadEnv();
                        if (openJavaAudioTrackHwLocked(ctx, env, ctx->sampleRate, ctx->channels)) {
                            writeJavaAudioTrackI16HwLocked(ctx, env, ctx->renderChunkBuf, totalSamples);
                        }
                    }
                }
            } else if (ctx->backend == BACKEND_JAVA_AUDIOTRACK) {
                JNIEnv* env = getThreadEnv();
                writeJavaAudioTrackI16HwLocked(ctx, env, ctx->renderChunkBuf, totalSamples);
            }
        }
        pthread_mutex_unlock(&ctx->hwMutex);
    }

    detachThreadEnvIfOwned();
    return nullptr;
}

static void stopRenderThread(BridgeContext* ctx) {
    if (!ctx) return;
    __atomic_fetch_add(&ctx->flushSerial, 1, __ATOMIC_RELEASE);

    pthread_t threadToJoin = 0;
    bool needJoin = false;
    pthread_mutex_lock(&ctx->ringMutex);
    if (ctx->renderThreadRunning) {
        ctx->renderStop = true;
        threadToJoin = ctx->renderThread;
        ctx->renderThreadRunning = false;
        needJoin = true;
        pthread_cond_broadcast(&ctx->ringCond);
    }
    pthread_mutex_unlock(&ctx->ringMutex);

    if (needJoin) {
        pthread_join(threadToJoin, nullptr);
    }
}

// ---------------- libVLC 原生回调函数 ----------------

static int onVlcAudioSetup(void** data, char* format, unsigned* rate, unsigned* channels) {
    if (!data || !*data) return -1;
    BridgeContext* ctx = static_cast<BridgeContext*>(*data);
    if (__atomic_load_n(&ctx->detached, __ATOMIC_ACQUIRE) != 0) return -1;

    // 先安全停掉上一次音轨遗留的渲染线程（不在持有 hwMutex 时 join，杜绝死锁）
    stopRenderThread(ctx);

    // 注意：libvlc-all:3.7.7 内核为 LibVLC 3.0.25，其 amem.c 仅接受 "S16N"（传其它格式会触发 VLC_EGENERIC 无声）！
    memcpy(format, "S16N", 4);
    unsigned targetRate = (*rate >= 8000u && *rate <= 96000u) ? *rate : 48000u;
    unsigned targetChannels = (*channels >= 2u) ? 2u : 1u;

    pthread_mutex_lock(&ctx->hwMutex);
    closeAAudioStreamHwLocked(ctx);
    JNIEnv* env = getThreadEnv();
    if (ctx->backend == BACKEND_JAVA_AUDIOTRACK && env && ctx->callbackGlobal && ctx->midOnAudioCleanup) {
        env->CallVoidMethod(ctx->callbackGlobal, ctx->midOnAudioCleanup);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    ctx->backend = BACKEND_NONE;

    bool opened = openAAudioStreamHwLocked(ctx, &targetRate, &targetChannels);
    if (!opened) {
        opened = openJavaAudioTrackHwLocked(ctx, env, targetRate, targetChannels);
    }
    if (!opened) {
        pthread_mutex_unlock(&ctx->hwMutex);
        LOGE("音频输出初始化失败（rate=%u, ch=%u）", targetRate, targetChannels);
        return -1;
    }

    *rate = targetRate;
    *channels = targetChannels;
    ctx->sampleRate = targetRate;
    ctx->channels = targetChannels;
    const OutputBackend activeBackend = ctx->backend;
    const bool usesFloat = ctx->aaudioUsesFloat;
    pthread_mutex_unlock(&ctx->hwMutex);

    pthread_mutex_lock(&ctx->asrMutex);
    ctx->resampler.reset(static_cast<int>(targetRate));
    ctx->asrBatchCount = 0;
    pthread_mutex_unlock(&ctx->asrMutex);

    const int ringFrames = static_cast<int>(targetRate * static_cast<unsigned>(RING_BUFFER_SECONDS));
    const int ringShorts = ringFrames * static_cast<int>(targetChannels);

    pthread_mutex_lock(&ctx->ringMutex);
    int16_t* nextRing = static_cast<int16_t*>(
        realloc(ctx->ringBuf, static_cast<size_t>(ringShorts) * sizeof(int16_t))
    );
    if (!nextRing) {
        pthread_mutex_unlock(&ctx->ringMutex);
        LOGE("分配 %d 帧音频环形缓冲失败", ringFrames);
        return -1;
    }
    ctx->ringBuf = nextRing;
    ctx->ringCapacityFrames = ringFrames;
    ctx->ringReadFrame = 0;
    ctx->ringWriteFrame = 0;
    ctx->ringAvailableFrames = 0;
    ctx->ringSampleRate = targetRate;
    ctx->ringChannels = targetChannels;
    ctx->targetStartUs = 0;
    ctx->pauseStartedUs = 0;
    ctx->preRollDone = false;
    ctx->renderPaused = false;
    ctx->draining = false;
    ctx->renderStop = false;
    ctx->driftActive = false;
    ctx->driftFrac = 0.0;
    armFadeInRingLocked(ctx, targetRate, 6);

    int createRes = pthread_create(&ctx->renderThread, nullptr, renderThreadMain, ctx);
    if (createRes == 0) {
        ctx->renderThreadRunning = true;
    }
    pthread_mutex_unlock(&ctx->ringMutex);

    if (createRes != 0) {
        LOGE("创建 C++ 音频渲染线程失败: %d", createRes);
        return -1;
    }

    LOGI("VLC 异步音频桥已就绪：backend=%s(%s), rate=%uHz, channels=%u, ring=%dms",
         activeBackend == BACKEND_AAUDIO ? "AAudio" : "AudioTrack",
         (activeBackend == BACKEND_AAUDIO && usesFloat) ? "FL32" : "S16",
         targetRate, targetChannels, RING_BUFFER_SECONDS * 1000);
    return 0;
}

static void onVlcAudioCleanup(void* data) {
    BridgeContext* ctx = static_cast<BridgeContext*>(data);
    if (!ctx) return;

    stopRenderThread(ctx);

    pthread_mutex_lock(&ctx->asrMutex);
    JNIEnv* env = getThreadEnv();
    if (__atomic_load_n(&ctx->asrEnabled, __ATOMIC_ACQUIRE) != 0 && env) {
        flushAsrBatchToJava(env, ctx);
    } else {
        ctx->asrBatchCount = 0;
    }
    pthread_mutex_unlock(&ctx->asrMutex);

    pthread_mutex_lock(&ctx->hwMutex);
    closeAAudioStreamHwLocked(ctx);
    if (ctx->backend == BACKEND_JAVA_AUDIOTRACK && env && ctx->callbackGlobal && ctx->midOnAudioCleanup) {
        env->CallVoidMethod(ctx->callbackGlobal, ctx->midOnAudioCleanup);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    ctx->backend = BACKEND_NONE;
    pthread_mutex_unlock(&ctx->hwMutex);
}

static void onVlcAudioPlay(void* data, const void* samples, unsigned count, int64_t pts) {
    BridgeContext* ctx = static_cast<BridgeContext*>(data);
    if (!ctx || !samples || count == 0) return;
    if (__atomic_load_n(&ctx->detached, __ATOMIC_ACQUIRE) != 0) return;

    const int16_t* rawPcm = static_cast<const int16_t*>(samples);
    const int frames = static_cast<int>(count);

    // 1. 若开启了实时 AI 字幕识别，在独立 asrMutex 下将净化后的音频重采样为 16kHz 单声道分流
    if (__atomic_load_n(&ctx->asrEnabled, __ATOMIC_ACQUIRE) != 0) {
        pthread_mutex_lock(&ctx->asrMutex);
        const int chAsr = static_cast<int>(ctx->channels > 0 ? ctx->channels : 2u);
        const int totalAsrSamples = frames * chAsr;
        const int maxOut = ctx->resampler.maxOutputFor(frames);
        if (ensureFloatCapacity(&ctx->floatWorkBuf, &ctx->floatWorkCapacity, totalAsrSamples) &&
            ensureFloatCapacity(&ctx->monoScratchBuf, &ctx->monoScratchCapacity, frames) &&
            ensureFloatCapacity(&ctx->resampledScratchBuf, &ctx->resampledScratchCapacity, maxOut)) {
            const float inv32768 = 1.0f / 32768.0f;
            for (int i = 0; i < totalAsrSamples; ++i) {
                int16_t cur = rawPcm[i];
                if ((cur == 32767 || cur == -32768) && i >= chAsr && (i + chAsr) < totalAsrSamples) {
                    int16_t prev = rawPcm[i - chAsr];
                    int16_t next = rawPcm[i + chAsr];
                    if (prev > -4096 && prev < 4096 && next > -4096 && next < 4096) {
                        cur = static_cast<int16_t>((static_cast<int>(prev) + static_cast<int>(next)) / 2);
                    }
                }
                ctx->floatWorkBuf[i] = static_cast<float>(cur) * inv32768;
            }

            if (chAsr == 1) {
                memcpy(ctx->monoScratchBuf, ctx->floatWorkBuf, static_cast<size_t>(frames) * sizeof(float));
            } else {
                const float invCh = 1.0f / static_cast<float>(chAsr);
                for (int i = 0; i < frames; ++i) {
                    float sum = 0.0f;
                    const int base = i * chAsr;
                    for (int c = 0; c < chAsr; ++c) {
                        sum += ctx->floatWorkBuf[base + c];
                    }
                    ctx->monoScratchBuf[i] = sum * invCh;
                }
            }

            int produced = ctx->resampler.process(
                ctx->monoScratchBuf,
                frames,
                ctx->resampledScratchBuf,
                maxOut
            );

            JNIEnv* env = nullptr;
            int idx = 0;
            while (idx < produced) {
                int space = ASR_BATCH_SAMPLES - ctx->asrBatchCount;
                int n = (produced - idx) < space ? (produced - idx) : space;
                memcpy(
                    ctx->asrBatchBuf + ctx->asrBatchCount,
                    ctx->resampledScratchBuf + idx,
                    static_cast<size_t>(n) * sizeof(float)
                );
                ctx->asrBatchCount += n;
                idx += n;
                if (ctx->asrBatchCount >= ASR_BATCH_SAMPLES) {
                    if (!env) env = getThreadEnv();
                    flushAsrBatchToJava(env, ctx);
                }
            }
        }
        pthread_mutex_unlock(&ctx->asrMutex);
    }

    // 2. 100% 非阻塞写入 4 秒环形缓冲区（仅微秒级内存拷贝，绝不阻塞 VLC DecoderThread）
    pthread_mutex_lock(&ctx->ringMutex);
    if (!ctx->ringBuf || ctx->ringCapacityFrames <= 0 || ctx->ringChannels == 0 || ctx->renderStop) {
        pthread_mutex_unlock(&ctx->ringMutex);
        return;
    }

    const int ch = static_cast<int>(ctx->ringChannels);
    const int capFrames = ctx->ringCapacityFrames;
    ctx->draining = false;

    // 首包进入空缓冲时，根据 VLC 单调时钟 PTS 与声学硬件延迟计算精确起播时刻，实现零阻塞唇形同步
    if (!ctx->preRollDone && ctx->ringAvailableFrames == 0) {
        const int64_t nowUs = monotonicUs();
        const int64_t hwLatUs = (ctx->backend == BACKEND_AAUDIO) ? AAUDIO_HW_LATENCY_US : AUDIOTRACK_HW_LATENCY_US;
        int64_t idealStartUs = (pts > hwLatUs) ? (pts - hwLatUs) : (nowUs + 80000LL);
        if (idealStartUs < nowUs + 80000LL) {
            idealStartUs = nowUs + 80000LL;
        } else if (idealStartUs > nowUs + 2200000LL) {
            idealStartUs = nowUs + 2200000LL;
        }
        ctx->targetStartUs = idealStartUs;
    }

    int srcOffsetFrames = 0;
    int framesToWrite = frames;
    if (framesToWrite > capFrames) {
        srcOffsetFrames = framesToWrite - capFrames;
        framesToWrite = capFrames;
    }

    // 若极端情况下环形缓冲已满，平滑丢弃最旧溢出帧并附带 5ms 淡入，绝不阻塞 VLC 解码器
    const int overflow = (ctx->ringAvailableFrames + framesToWrite) - capFrames;
    if (overflow > 0) {
        ctx->ringReadFrame = (ctx->ringReadFrame + overflow) % capFrames;
        ctx->ringAvailableFrames -= overflow;
        armFadeInRingLocked(ctx, ctx->ringSampleRate, 5);
    }

    const int totalSrcSamples = frames * ch;
    for (int f = 0; f < framesToWrite; ++f) {
        const int srcFrame = srcOffsetFrames + f;
        const int dstFrame = (ctx->ringWriteFrame + f) % capFrames;
        const int srcBase = srcFrame * ch;
        const int dstBase = dstFrame * ch;
        for (int c = 0; c < ch; ++c) {
            const int si = srcBase + c;
            int16_t cur = rawPcm[si];
            // 滤除直播 TS 偶发坏包引起的单采样点满幅脉冲咔哒声
            if ((cur == 32767 || cur == -32768) && si >= ch && (si + ch) < totalSrcSamples) {
                int16_t prev = rawPcm[si - ch];
                int16_t next = rawPcm[si + ch];
                if (prev > -4096 && prev < 4096 && next > -4096 && next < 4096) {
                    cur = static_cast<int16_t>((static_cast<int>(prev) + static_cast<int>(next)) / 2);
                }
            }
            ctx->ringBuf[dstBase + c] = cur;
        }
    }

    ctx->ringWriteFrame = (ctx->ringWriteFrame + framesToWrite) % capFrames;
    ctx->ringAvailableFrames += framesToWrite;
    pthread_cond_signal(&ctx->ringCond);
    pthread_mutex_unlock(&ctx->ringMutex);
}

static void onVlcAudioPause(void* data, int64_t pts) {
    (void)pts;
    BridgeContext* ctx = static_cast<BridgeContext*>(data);
    if (!ctx || __atomic_load_n(&ctx->detached, __ATOMIC_ACQUIRE) != 0) return;

    __atomic_fetch_add(&ctx->flushSerial, 1, __ATOMIC_RELEASE);

    pthread_mutex_lock(&ctx->ringMutex);
    if (!ctx->renderPaused) {
        ctx->renderPaused = true;
        ctx->pauseStartedUs = monotonicUs();
        pthread_cond_broadcast(&ctx->ringCond);
    }
    pthread_mutex_unlock(&ctx->ringMutex);

    pthread_mutex_lock(&ctx->hwMutex);
    if (ctx->backend == BACKEND_AAUDIO && ctx->aaudioStream != nullptr) {
        g_aaudio.streamRequestPause(ctx->aaudioStream);
        ctx->aaudioStarted = false;
    } else if (ctx->backend == BACKEND_JAVA_AUDIOTRACK) {
        JNIEnv* env = getThreadEnv();
        if (env && ctx->callbackGlobal && ctx->midOnAudioPause) {
            env->CallVoidMethod(ctx->callbackGlobal, ctx->midOnAudioPause);
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
    }
    pthread_mutex_unlock(&ctx->hwMutex);
}

static void onVlcAudioResume(void* data, int64_t pts) {
    (void)pts;
    BridgeContext* ctx = static_cast<BridgeContext*>(data);
    if (!ctx || __atomic_load_n(&ctx->detached, __ATOMIC_ACQUIRE) != 0) return;

    pthread_mutex_lock(&ctx->hwMutex);
    if (ctx->backend == BACKEND_AAUDIO && ctx->aaudioStream != nullptr) {
        g_aaudio.streamRequestStart(ctx->aaudioStream);
        ctx->aaudioStarted = true;
    } else if (ctx->backend == BACKEND_JAVA_AUDIOTRACK) {
        JNIEnv* env = getThreadEnv();
        if (env && ctx->callbackGlobal && ctx->midOnAudioResume) {
            env->CallVoidMethod(ctx->callbackGlobal, ctx->midOnAudioResume);
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
    }
    pthread_mutex_unlock(&ctx->hwMutex);

    pthread_mutex_lock(&ctx->ringMutex);
    if (ctx->renderPaused) {
        const int64_t nowUs = monotonicUs();
        if (!ctx->preRollDone && ctx->pauseStartedUs > 0 && ctx->targetStartUs > 0 && nowUs > ctx->pauseStartedUs) {
            ctx->targetStartUs += (nowUs - ctx->pauseStartedUs);
        }
        ctx->pauseStartedUs = 0;
        ctx->renderPaused = false;
        armFadeInRingLocked(ctx, ctx->ringSampleRate > 0 ? ctx->ringSampleRate : 48000u, 4);
        pthread_cond_broadcast(&ctx->ringCond);
    }
    pthread_mutex_unlock(&ctx->ringMutex);
}

static void onVlcAudioFlush(void* data, int64_t pts) {
    (void)pts;
    BridgeContext* ctx = static_cast<BridgeContext*>(data);
    if (!ctx || __atomic_load_n(&ctx->detached, __ATOMIC_ACQUIRE) != 0) return;

    __atomic_fetch_add(&ctx->flushSerial, 1, __ATOMIC_RELEASE);

    pthread_mutex_lock(&ctx->ringMutex);
    ctx->ringReadFrame = 0;
    ctx->ringWriteFrame = 0;
    ctx->ringAvailableFrames = 0;
    ctx->targetStartUs = 0;
    ctx->pauseStartedUs = 0;
    ctx->preRollDone = false;
    ctx->draining = false;
    ctx->driftActive = false;
    ctx->driftFrac = 0.0;
    armFadeInRingLocked(ctx, ctx->ringSampleRate > 0 ? ctx->ringSampleRate : 48000u, 5);
    pthread_cond_broadcast(&ctx->ringCond);
    pthread_mutex_unlock(&ctx->ringMutex);

    pthread_mutex_lock(&ctx->asrMutex);
    JNIEnv* env = getThreadEnv();
    if (__atomic_load_n(&ctx->asrEnabled, __ATOMIC_ACQUIRE) != 0 && env) {
        flushAsrBatchToJava(env, ctx);
        if (ctx->callbackGlobal && ctx->midOnDiscontinuity) {
            env->CallVoidMethod(ctx->callbackGlobal, ctx->midOnDiscontinuity);
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
    } else {
        ctx->asrBatchCount = 0;
    }
    unsigned curRate = ctx->sampleRate > 0 ? ctx->sampleRate : static_cast<unsigned>(TARGET_ASR_RATE);
    ctx->resampler.reset(static_cast<int>(curRate));
    pthread_mutex_unlock(&ctx->asrMutex);

    pthread_mutex_lock(&ctx->hwMutex);
    if (ctx->backend == BACKEND_AAUDIO && ctx->aaudioStream != nullptr) {
        g_aaudio.streamRequestPause(ctx->aaudioStream);
        g_aaudio.streamRequestFlush(ctx->aaudioStream);
        if (!ctx->renderPaused) {
            g_aaudio.streamRequestStart(ctx->aaudioStream);
            ctx->aaudioStarted = true;
        } else {
            ctx->aaudioStarted = false;
        }
    } else if (ctx->backend == BACKEND_JAVA_AUDIOTRACK && env && ctx->callbackGlobal && ctx->midOnAudioFlush) {
        env->CallVoidMethod(ctx->callbackGlobal, ctx->midOnAudioFlush);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    pthread_mutex_unlock(&ctx->hwMutex);
}

static void onVlcAudioDrain(void* data) {
    BridgeContext* ctx = static_cast<BridgeContext*>(data);
    if (!ctx || __atomic_load_n(&ctx->detached, __ATOMIC_ACQUIRE) != 0) return;

    pthread_mutex_lock(&ctx->asrMutex);
    if (__atomic_load_n(&ctx->asrEnabled, __ATOMIC_ACQUIRE) != 0) {
        JNIEnv* env = getThreadEnv();
        flushAsrBatchToJava(env, ctx);
    }
    pthread_mutex_unlock(&ctx->asrMutex);

    const int startSerial = __atomic_load_n(&ctx->flushSerial, __ATOMIC_ACQUIRE);
    const int64_t deadlineUs = monotonicUs() + 2000000LL;

    pthread_mutex_lock(&ctx->ringMutex);
    ctx->draining = true;
    pthread_cond_broadcast(&ctx->ringCond);
    while (!ctx->renderStop &&
           !ctx->renderPaused &&
           ctx->ringAvailableFrames > 0 &&
           __atomic_load_n(&ctx->detached, __ATOMIC_ACQUIRE) == 0 &&
           __atomic_load_n(&ctx->flushSerial, __ATOMIC_ACQUIRE) == startSerial &&
           monotonicUs() < deadlineUs) {
        condTimedWaitMs(&ctx->ringCond, &ctx->ringMutex, 15);
    }
    pthread_mutex_unlock(&ctx->ringMutex);
}

static int onVlcAudioSetVolume(void* data, float volume, bool mute) {
    BridgeContext* ctx = static_cast<BridgeContext*>(data);
    if (!ctx) return 0;
    float clamped = volume < 0.0f ? 0.0f : (volume > 1.5f ? 1.5f : volume);
    __atomic_store_n(&ctx->volumeMilli, static_cast<int>(clamped * 1000.0f + 0.5f), __ATOMIC_RELAXED);
    __atomic_store_n(&ctx->muted, mute ? 1 : 0, __ATOMIC_RELAXED);
    return 0;
}

// ---------------- 从 org.videolan.libvlc.MediaPlayer 提取原生 libvlc_media_player_t* ----------------

static libvlc_media_player_t* extractNativeMediaPlayer(JNIEnv* env, jobject jLibVlc, jobject jMediaPlayer) {
    if (!env || !jMediaPlayer) return nullptr;

    jclass mpCls = env->GetObjectClass(jMediaPlayer);
    if (!mpCls) return nullptr;

    jmethodID midGetInstance = env->GetMethodID(mpCls, "getInstance", "()J");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        midGetInstance = nullptr;
    }
    if (midGetInstance != nullptr) {
        jlong ptr = env->CallLongMethod(jMediaPlayer, midGetInstance);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        } else if (ptr != 0) {
            env->DeleteLocalRef(mpCls);
            return reinterpret_cast<libvlc_media_player_t*>(static_cast<uintptr_t>(ptr));
        }
    }

    jclass curCls = mpCls;
    jfieldID fidInstance = nullptr;
    while (curCls != nullptr) {
        fidInstance = env->GetFieldID(curCls, "mInstance", "J");
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            fidInstance = nullptr;
        }
        if (fidInstance != nullptr) break;
        jclass superCls = env->GetSuperclass(curCls);
        if (curCls != mpCls) env->DeleteLocalRef(curCls);
        curCls = superCls;
    }
    if (curCls != nullptr && curCls != mpCls) env->DeleteLocalRef(curCls);
    env->DeleteLocalRef(mpCls);

    if (!fidInstance) {
        LOGE("无法在 VLCObject 继承链中找到 getInstance() 或 mInstance 字段");
        return nullptr;
    }

    jlong mpObjAddr = env->GetLongField(jMediaPlayer, fidInstance);
    if (mpObjAddr == 0) return nullptr;

    void* libVlcRawPtr = nullptr;
    if (jLibVlc != nullptr) {
        jlong lvObjAddr = env->GetLongField(jLibVlc, fidInstance);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        } else if (lvObjAddr != 0) {
            void** lvWords = reinterpret_cast<void**>(static_cast<uintptr_t>(lvObjAddr));
            libVlcRawPtr = (lvWords[0] != nullptr) ? lvWords[0] : lvWords[1];
        }
    }

    void** mpWords = reinterpret_cast<void**>(static_cast<uintptr_t>(mpObjAddr));
    void* candidate = mpWords[0];
    if (libVlcRawPtr != nullptr && candidate == libVlcRawPtr && mpWords[1] != nullptr) {
        candidate = mpWords[1];
    }
    return reinterpret_cast<libvlc_media_player_t*>(candidate);
}

static void bindCallbacksToPlayer(BridgeContext* ctx) {
    if (!ctx || !ctx->mp) return;
    if (g_vlc.output_set) {
        g_vlc.output_set(ctx->mp, "amem");
    }
    g_vlc.set_callbacks(
        ctx->mp,
        onVlcAudioPlay,
        onVlcAudioPause,
        onVlcAudioResume,
        onVlcAudioFlush,
        onVlcAudioDrain,
        ctx
    );
    g_vlc.set_format_callbacks(ctx->mp, onVlcAudioSetup, onVlcAudioCleanup);
    if (g_vlc.set_volume_callback) {
        g_vlc.set_volume_callback(ctx->mp, onVlcAudioSetVolume);
    }
}

// ---------------- JNI 导出接口 ----------------

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    g_jvm = vm;
    pthread_once(&g_jni_key_once, initJniThreadKey);
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aisub_player_media_VlcAudioBridge_nativeIsAAudioSupported(JNIEnv* env, jclass clazz) {
    (void)env;
    (void)clazz;
    return isAAudioAvailable() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_aisub_player_media_VlcAudioBridge_nativeAttach(
    JNIEnv* env,
    jobject thiz,
    jobject jLibVlc,
    jobject jMediaPlayer
) {
    if (!ensureLibVlcApi()) return 0;

    libvlc_media_player_t* mp = extractNativeMediaPlayer(env, jLibVlc, jMediaPlayer);
    if (!mp) {
        LOGE("提取原生 libvlc_media_player_t* 失败");
        return 0;
    }

    BridgeContext* ctx = static_cast<BridgeContext*>(calloc(1, sizeof(BridgeContext)));
    if (!ctx) return 0;

    pthread_mutex_init(&ctx->hwMutex, nullptr);
    pthread_mutex_init(&ctx->asrMutex, nullptr);
    pthread_mutex_init(&ctx->ringMutex, nullptr);
    pthread_cond_init(&ctx->ringCond, nullptr);

    ctx->mp = mp;
    ctx->callbackGlobal = env->NewGlobalRef(thiz);
    ctx->volumeMilli = 1000;
    ctx->muted = 0;
    ctx->asrEnabled = 0;
    ctx->detached = 0;
    ctx->backend = BACKEND_NONE;
    ctx->resampler.reset(48000);

    jclass cbCls = env->GetObjectClass(thiz);
    ctx->midOnAudioSetup = env->GetMethodID(cbCls, "onNativeAudioSetup", "(II)Z");
    ctx->midOnAudioWrite = env->GetMethodID(cbCls, "onNativeAudioWrite", "([SI)V");
    ctx->midOnAudioPause = env->GetMethodID(cbCls, "onNativeAudioPause", "()V");
    ctx->midOnAudioResume = env->GetMethodID(cbCls, "onNativeAudioResume", "()V");
    ctx->midOnAudioFlush = env->GetMethodID(cbCls, "onNativeAudioFlush", "()V");
    ctx->midOnAudioCleanup = env->GetMethodID(cbCls, "onNativeAudioCleanup", "()V");
    ctx->midOnAsrPcm = env->GetMethodID(cbCls, "onNativeAsrPcm", "([F)V");
    ctx->midOnDiscontinuity = env->GetMethodID(cbCls, "onNativeDiscontinuity", "()V");
    env->DeleteLocalRef(cbCls);

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        if (ctx->callbackGlobal) env->DeleteGlobalRef(ctx->callbackGlobal);
        pthread_cond_destroy(&ctx->ringCond);
        pthread_mutex_destroy(&ctx->ringMutex);
        pthread_mutex_destroy(&ctx->asrMutex);
        pthread_mutex_destroy(&ctx->hwMutex);
        free(ctx);
        LOGE("查找 VlcAudioBridge 回调方法签名失败");
        return 0;
    }

    bindCallbacksToPlayer(ctx);
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(ctx));
}

extern "C" JNIEXPORT void JNICALL
Java_com_aisub_player_media_VlcAudioBridge_nativeRebindCallbacks(
    JNIEnv* env,
    jobject thiz,
    jlong handle
) {
    (void)env;
    (void)thiz;
    BridgeContext* ctx = reinterpret_cast<BridgeContext*>(static_cast<uintptr_t>(handle));
    if (!ctx || __atomic_load_n(&ctx->detached, __ATOMIC_ACQUIRE) != 0) return;
    bindCallbacksToPlayer(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_aisub_player_media_VlcAudioBridge_nativeSetAsrEnabled(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jboolean enabled
) {
    (void)env;
    (void)thiz;
    BridgeContext* ctx = reinterpret_cast<BridgeContext*>(static_cast<uintptr_t>(handle));
    if (!ctx) return;
    __atomic_store_n(&ctx->asrEnabled, enabled ? 1 : 0, __ATOMIC_RELEASE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_aisub_player_media_VlcAudioBridge_nativeSetVolume(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jfloat volume
) {
    (void)env;
    (void)thiz;
    BridgeContext* ctx = reinterpret_cast<BridgeContext*>(static_cast<uintptr_t>(handle));
    if (!ctx) return;
    float clamped = volume < 0.0f ? 0.0f : (volume > 1.5f ? 1.5f : volume);
    __atomic_store_n(&ctx->volumeMilli, static_cast<int>(clamped * 1000.0f + 0.5f), __ATOMIC_RELAXED);
    __atomic_store_n(&ctx->muted, clamped <= 0.0001f ? 1 : 0, __ATOMIC_RELAXED);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aisub_player_media_VlcAudioBridge_nativeActiveBackend(
    JNIEnv* env,
    jobject thiz,
    jlong handle
) {
    (void)env;
    (void)thiz;
    BridgeContext* ctx = reinterpret_cast<BridgeContext*>(static_cast<uintptr_t>(handle));
    if (!ctx) return 0;
    pthread_mutex_lock(&ctx->hwMutex);
    int b = static_cast<int>(ctx->backend);
    pthread_mutex_unlock(&ctx->hwMutex);
    return static_cast<jint>(b);
}

extern "C" JNIEXPORT void JNICALL
Java_com_aisub_player_media_VlcAudioBridge_nativeDetach(
    JNIEnv* env,
    jobject thiz,
    jlong handle
) {
    (void)thiz;
    BridgeContext* ctx = reinterpret_cast<BridgeContext*>(static_cast<uintptr_t>(handle));
    if (!ctx) return;

    __atomic_store_n(&ctx->detached, 1, __ATOMIC_RELEASE);
    __atomic_store_n(&ctx->asrEnabled, 0, __ATOMIC_RELEASE);

    if (ctx->mp && g_vlc.available) {
        g_vlc.set_callbacks(ctx->mp, nullptr, nullptr, nullptr, nullptr, nullptr, nullptr);
        g_vlc.set_format_callbacks(ctx->mp, nullptr, nullptr);
        if (g_vlc.set_volume_callback) {
            g_vlc.set_volume_callback(ctx->mp, nullptr);
        }
    }

    stopRenderThread(ctx);

    pthread_mutex_lock(&ctx->hwMutex);
    closeAAudioStreamHwLocked(ctx);
    if (ctx->backend == BACKEND_JAVA_AUDIOTRACK && env && ctx->callbackGlobal && ctx->midOnAudioCleanup) {
        env->CallVoidMethod(ctx->callbackGlobal, ctx->midOnAudioCleanup);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    ctx->backend = BACKEND_NONE;

    if (env) {
        if (ctx->javaPcmArrayGlobal) {
            env->DeleteGlobalRef(ctx->javaPcmArrayGlobal);
            ctx->javaPcmArrayGlobal = nullptr;
        }
        if (ctx->callbackGlobal) {
            env->DeleteGlobalRef(ctx->callbackGlobal);
            ctx->callbackGlobal = nullptr;
        }
    }
    free(ctx->renderChunkBuf);
    ctx->renderChunkBuf = nullptr;
    free(ctx->renderFloatBuf);
    ctx->renderFloatBuf = nullptr;
    pthread_mutex_unlock(&ctx->hwMutex);

    pthread_mutex_lock(&ctx->ringMutex);
    free(ctx->ringBuf);
    ctx->ringBuf = nullptr;
    ctx->ringCapacityFrames = 0;
    pthread_mutex_unlock(&ctx->ringMutex);

    pthread_mutex_lock(&ctx->asrMutex);
    free(ctx->floatWorkBuf);
    ctx->floatWorkBuf = nullptr;
    free(ctx->monoScratchBuf);
    ctx->monoScratchBuf = nullptr;
    free(ctx->resampledScratchBuf);
    ctx->resampledScratchBuf = nullptr;
    pthread_mutex_unlock(&ctx->asrMutex);

    pthread_cond_destroy(&ctx->ringCond);
    pthread_mutex_destroy(&ctx->ringMutex);
    pthread_mutex_destroy(&ctx->asrMutex);
    pthread_mutex_destroy(&ctx->hwMutex);
    free(ctx);
}
