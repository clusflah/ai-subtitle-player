#include <dlfcn.h>
#include <jni.h>
#include <math.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

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
 * VLC 原生音频分流桥（兼容 -DANDROID_STL=none，零 C++ 标准库符号依赖）：
 * 1. 通过 libvlc_audio_set_callbacks + libvlc_audio_set_format_callbacks 接管解码后的 32-bit 浮点 ("FL32") PCM，
 *    内置 IEEE-754 NaN/Inf 坏包静默过滤与 5.1 下混软膝防削波限幅器，彻底消除高码率 4K 直播与环绕声破音/电流声；
 * 2. 运行时通过 dlopen("libaaudio.so", RTLD_LAZY) 动态探测 AAudio：
 *    - Android 8.1+（API >= 27）且 dlopen 成功：走 C++ AAudio 标准媒体模式（400ms 容量 / 240ms 活跃缓冲 + 完整分片写入循环），
 *      杜绝 4K 50fps HDR 解码与 HLS 分片抖动导致的 FastMixer XRun 欠载与尾部丢帧电流声；
 *    - Android 6.0 老电视等不支持设备：自动回退到 Java AudioTrack 播放；
 * 3. 同时在开启 AI 字幕识别时，在 C++ 层将多声道音频混音并抗混叠重采样为 16kHz 单声道 float PCM，
 *    按 100ms（1600 样本）无损分批回调给识别引擎，单连接直播源无需另开第二路连接即可实时出字幕。
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
static const int64_t AAUDIO_WRITE_SLICE_NS = 80LL * 1000000LL; // 单次写入分片超时 80ms，循环写满整包，零丢帧且切台秒退
static const int64_t AUDIOTRACK_DESYNC_US = -180000LL; // -180ms

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
        LOGI("当前系统 API %d (< 27)，跳过 AAudio，使用 Java AudioTrack + --audio-desync=-160 同步补偿路径", sdk);
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
    LOGI("AAudio 动态探测成功（API %d），启用 C++ AAudio 32-bit 浮点防欠载路径", sdk);
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

        // 降采样（如 48kHz/44.1kHz -> 16kHz）结合区间邻域均值与线性插值，抑制高频混叠噪声
        while (pos < static_cast<double>(frames - 1) && produced < outCapacity) {
            int i = static_cast<int>(floor(pos));
            float frac = static_cast<float>(pos - static_cast<double>(i));
            float a = (i < 0) ? prevSample : monoIn[i];
            float b = monoIn[i + 1];
            float interp = a + (b - a) * frac;

            if (step >= 1.8 && i >= 0 && (i + 2) < frames) {
                // 3 点轻量低通平滑（0.25, 0.5, 0.25），滤除音乐高频混叠
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
    pthread_mutex_t audioMutex;
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

    jshortArray javaPcmArrayGlobal;
    int javaPcmCapacity;

    int16_t* scaledPcmBuf;
    int scaledPcmCapacity;

    float* floatWorkBuf;
    int floatWorkCapacity;

    float* monoScratchBuf;
    int monoScratchCapacity;

    float* resampledScratchBuf;
    int resampledScratchCapacity;

    float asrBatchBuf[ASR_BATCH_SAMPLES];
    int asrBatchCount;
    StreamingResampler16k resampler;

    AAudioStream* aaudioStream;
    bool aaudioStarted;
    bool aaudioUsesFloat;
    bool appliedRuntimeDelay;
    OutputBackend backend;
    unsigned sampleRate;
    unsigned channels;
    int fadeInRemaining;
    int fadeInTotal;

    volatile int asrEnabled;
    volatile int detached;
    volatile int flushSerial;
    volatile int volumeMilli; // 0..1000 代表 0.0f..1.0f
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

static inline float sanitizeAndSoftLimitSample(float s, bool* sawNonFinite) {
    union {
        float f;
        uint32_t u;
    } bits;
    bits.f = s;
    // 拦截直播 TS 丢包/切台首包可能出现的 NaN / Inf，避免转为满幅爆音脉冲
    if ((bits.u & 0x7F800000u) == 0x7F800000u) {
        *sawNonFinite = true;
        return 0.0f;
    }
    float a = s < 0.0f ? -s : s;
    if (a <= 0.88f) return s;
    // 5.1 / 7.1 下混立体声时常见瞬态超 1.0f 峰值：采用有理软膝压缩至 [-1, 1]，杜绝硬削波谐波电流声
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

static void armFadeInLocked(BridgeContext* ctx, unsigned rate, int durationMs) {
    int frames = static_cast<int>((rate * static_cast<unsigned>(durationMs)) / 1000u);
    if (frames < 32) frames = 32;
    ctx->fadeInTotal = frames;
    ctx->fadeInRemaining = frames;
}

static void closeAAudioStreamLocked(BridgeContext* ctx) {
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
    // 阻塞式媒体流严禁启用无回调 LOW_LATENCY（FastMixer 默认仅 4~8ms 缓冲，4K HDR 解码抖动必现 XRun 电流声）
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

static bool openAAudioStreamLocked(BridgeContext* ctx, unsigned* rate, unsigned* channels) {
    if (!isAAudioAvailable()) return false;

    const int32_t wantCapacityFrames = static_cast<int32_t>((*rate * 400u) / 1000u); // 400ms 容量
    bool useFloat = false;
    // 直接使用与 LibVLC 3.0 amem ("S16N") 一致的 PCM_I16，兼顾全机型 HAL 硬件兼容性与零格式错位风险
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

    // 显式将活跃缓冲水位扩至 ~240ms（不超过实际分配容量），彻底吸收 4K 50fps HDR 解码与直播分片抖动
    if (g_aaudio.streamSetBufferSizeInFrames) {
        int32_t targetActive = static_cast<int32_t>((*rate * 240u) / 1000u);
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

    if (ctx->appliedRuntimeDelay && g_vlc.set_delay && ctx->mp) {
        g_vlc.set_delay(ctx->mp, 0);
        ctx->appliedRuntimeDelay = false;
    }

    ctx->aaudioStream = stream;
    ctx->aaudioStarted = true;
    ctx->aaudioUsesFloat = useFloat;
    ctx->backend = BACKEND_AAUDIO;
    return true;
}

static bool openJavaAudioTrackLocked(BridgeContext* ctx, JNIEnv* env, unsigned rate, unsigned channels) {
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
    ctx->backend = BACKEND_JAVA_AUDIOTRACK;
    // 若启动时探测到 AAudio（已设 --audio-desync=-140），运行时回退到 AudioTrack 仅需补齐差值 -40ms（合计 -180ms）
    if (isAAudioAvailable() && g_vlc.set_delay && ctx->mp) {
        g_vlc.set_delay(ctx->mp, -40000LL);
        ctx->appliedRuntimeDelay = true;
    } else if (!isAAudioAvailable()) {
        (void)AUDIOTRACK_DESYNC_US;
    }
    return true;
}

static bool writeFullAAudioFramesLocked(BridgeContext* ctx, const float* floatPcm, int frames, int ch) {
    if (!ctx->aaudioStream || frames <= 0) return false;
    if (!ctx->aaudioStarted) {
        g_aaudio.streamRequestStart(ctx->aaudioStream);
        ctx->aaudioStarted = true;
    }

    const int16_t* i16Pcm = nullptr;
    if (!ctx->aaudioUsesFloat) {
        const int totalSamples = frames * ch;
        if (!ensureShortCapacity(&ctx->scaledPcmBuf, &ctx->scaledPcmCapacity, totalSamples)) {
            return false;
        }
        for (int i = 0; i < totalSamples; ++i) {
            ctx->scaledPcmBuf[i] = floatToClampedI16(floatPcm[i]);
        }
        i16Pcm = ctx->scaledPcmBuf;
    }

    const int startSerial = __atomic_load_n(&ctx->flushSerial, __ATOMIC_ACQUIRE);
    int writtenTotal = 0;
    int zeroRetries = 0;

    while (writtenTotal < frames) {
        if (__atomic_load_n(&ctx->detached, __ATOMIC_ACQUIRE) != 0 ||
            __atomic_load_n(&ctx->flushSerial, __ATOMIC_ACQUIRE) != startSerial) {
            return true;
        }
        const void* ptr = ctx->aaudioUsesFloat
            ? static_cast<const void*>(floatPcm + writtenTotal * ch)
            : static_cast<const void*>(i16Pcm + writtenTotal * ch);
        const int32_t remain = frames - writtenTotal;
        aaudio_result_t w = g_aaudio.streamWrite(ctx->aaudioStream, ptr, remain, AAUDIO_WRITE_SLICE_NS);
        if (w > 0) {
            writtenTotal += static_cast<int>(w);
            zeroRetries = 0;
        } else if (w == 0) {
            if (++zeroRetries > 20) {
                LOGW("AAudio 连续写入超时（已写 %d/%d 帧）", writtenTotal, frames);
                return true;
            }
        } else {
            LOGW("AAudio write 返回错误 %d，准备重建输出流", static_cast<int>(w));
            return false;
        }
    }
    return true;
}

static void writeJavaAudioTrackFromFloatLocked(BridgeContext* ctx, JNIEnv* env, const float* floatPcm, int totalSamples) {
    if (!env || !ctx->callbackGlobal || !ctx->midOnAudioWrite || totalSamples <= 0) return;
    if (!ensureShortCapacity(&ctx->scaledPcmBuf, &ctx->scaledPcmCapacity, totalSamples)) return;
    for (int i = 0; i < totalSamples; ++i) {
        ctx->scaledPcmBuf[i] = floatToClampedI16(floatPcm[i]);
    }
    jshortArray arr = ensureJavaShortArray(env, ctx, totalSamples);
    if (!arr) return;
    env->SetShortArrayRegion(arr, 0, totalSamples, ctx->scaledPcmBuf);
    env->CallVoidMethod(ctx->callbackGlobal, ctx->midOnAudioWrite, arr, static_cast<jint>(totalSamples));
    if (env->ExceptionCheck()) env->ExceptionClear();
}

// ---------------- libVLC 原生回调函数 ----------------

static int onVlcAudioSetup(void** data, char* format, unsigned* rate, unsigned* channels) {
    if (!data || !*data) return -1;
    BridgeContext* ctx = static_cast<BridgeContext*>(*data);
    if (__atomic_load_n(&ctx->detached, __ATOMIC_ACQUIRE) != 0) return -1;

    // 注意：libvlc-all:3.7.7 内核为 LibVLC 3.0.25，其 amem.c 仅接受 "S16N"（传其它格式会触发 VLC_EGENERIC 无声）！
    memcpy(format, "S16N", 4);
    unsigned targetRate = (*rate >= 8000u && *rate <= 96000u) ? *rate : 48000u;
    unsigned targetChannels = (*channels >= 2u) ? 2u : 1u;

    pthread_mutex_lock(&ctx->audioMutex);

    // 清理上一次音轨遗留的输出流
    closeAAudioStreamLocked(ctx);
    JNIEnv* env = getThreadEnv();
    if (ctx->backend == BACKEND_JAVA_AUDIOTRACK && env && ctx->callbackGlobal && ctx->midOnAudioCleanup) {
        env->CallVoidMethod(ctx->callbackGlobal, ctx->midOnAudioCleanup);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    ctx->backend = BACKEND_NONE;

    bool opened = openAAudioStreamLocked(ctx, &targetRate, &targetChannels);
    if (!opened) {
        opened = openJavaAudioTrackLocked(ctx, env, targetRate, targetChannels);
    }

    if (!opened) {
        pthread_mutex_unlock(&ctx->audioMutex);
        LOGE("音频输出初始化失败（rate=%u, ch=%u）", targetRate, targetChannels);
        return -1;
    }

    *rate = targetRate;
    *channels = targetChannels;
    ctx->sampleRate = targetRate;
    ctx->channels = targetChannels;
    ctx->resampler.reset(static_cast<int>(targetRate));
    ctx->asrBatchCount = 0;
    armFadeInLocked(ctx, targetRate, 6);

    LOGI("VLC 音频桥已就绪：backend=%s(%s), rate=%uHz, channels=%u",
         ctx->backend == BACKEND_AAUDIO ? "AAudio" : "AudioTrack",
         (ctx->backend == BACKEND_AAUDIO && ctx->aaudioUsesFloat) ? "FL32" : "S16",
         targetRate, targetChannels);

    pthread_mutex_unlock(&ctx->audioMutex);
    return 0;
}

static void onVlcAudioCleanup(void* data) {
    BridgeContext* ctx = static_cast<BridgeContext*>(data);
    if (!ctx) return;

    __atomic_fetch_add(&ctx->flushSerial, 1, __ATOMIC_RELEASE);
    pthread_mutex_lock(&ctx->audioMutex);
    JNIEnv* env = getThreadEnv();
    if (__atomic_load_n(&ctx->asrEnabled, __ATOMIC_ACQUIRE) != 0 && env) {
        flushAsrBatchToJava(env, ctx);
    } else {
        ctx->asrBatchCount = 0;
    }

    closeAAudioStreamLocked(ctx);
    if (ctx->backend == BACKEND_JAVA_AUDIOTRACK && env && ctx->callbackGlobal && ctx->midOnAudioCleanup) {
        env->CallVoidMethod(ctx->callbackGlobal, ctx->midOnAudioCleanup);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    ctx->backend = BACKEND_NONE;
    pthread_mutex_unlock(&ctx->audioMutex);
}

static void onVlcAudioPlay(void* data, const void* samples, unsigned count, int64_t pts) {
    (void)pts;
    BridgeContext* ctx = static_cast<BridgeContext*>(data);
    if (!ctx || !samples || count == 0) return;
    if (__atomic_load_n(&ctx->detached, __ATOMIC_ACQUIRE) != 0) return;

    pthread_mutex_lock(&ctx->audioMutex);
    if (ctx->backend == BACKEND_NONE || ctx->channels == 0) {
        pthread_mutex_unlock(&ctx->audioMutex);
        return;
    }

    const int16_t* rawPcm = static_cast<const int16_t*>(samples);
    const int frames = static_cast<int>(count);
    const int ch = static_cast<int>(ctx->channels);
    const int totalSamples = frames * ch;

    if (!ensureFloatCapacity(&ctx->floatWorkBuf, &ctx->floatWorkCapacity, totalSamples)) {
        pthread_mutex_unlock(&ctx->audioMutex);
        return;
    }

    // 0. 将 S16N 转为浮点工作区，同时自动插值消除直播 TS 坏包 NaN 引发的单点满幅脉冲咔哒声
    const float inv32768 = 1.0f / 32768.0f;
    for (int i = 0; i < totalSamples; ++i) {
        int16_t cur = rawPcm[i];
        if ((cur == 32767 || cur == -32768) && i >= ch && (i + ch) < totalSamples) {
            int16_t prev = rawPcm[i - ch];
            int16_t next = rawPcm[i + ch];
            if (prev > -4096 && prev < 4096 && next > -4096 && next < 4096) {
                cur = static_cast<int16_t>((static_cast<int>(prev) + static_cast<int>(next)) / 2);
            }
        }
        ctx->floatWorkBuf[i] = static_cast<float>(cur) * inv32768;
    }

    // 1. 分流给识别引擎：使用净化后且未经扬声器音量衰减的 PCM 混音并重采样为 16kHz 单声道 float
    if (__atomic_load_n(&ctx->asrEnabled, __ATOMIC_ACQUIRE) != 0) {
        int maxOut = ctx->resampler.maxOutputFor(frames);
        if (ensureFloatCapacity(&ctx->monoScratchBuf, &ctx->monoScratchCapacity, frames) &&
            ensureFloatCapacity(&ctx->resampledScratchBuf, &ctx->resampledScratchCapacity, maxOut)) {
            if (ch == 1) {
                memcpy(ctx->monoScratchBuf, ctx->floatWorkBuf, static_cast<size_t>(frames) * sizeof(float));
            } else {
                const float invCh = 1.0f / static_cast<float>(ch);
                for (int i = 0; i < frames; ++i) {
                    float sum = 0.0f;
                    const int base = i * ch;
                    for (int c = 0; c < ch; ++c) {
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
    } else {
        ctx->asrBatchCount = 0;
    }

    // 2. 扬声器播放路径：应用音量 / 静音以及起播或断流恢复时的毫秒级平滑淡入（消除直流阶跃咔哒声）
    const int muted = __atomic_load_n(&ctx->muted, __ATOMIC_RELAXED);
    const int volMilli = muted ? 0 : __atomic_load_n(&ctx->volumeMilli, __ATOMIC_RELAXED);
    const float gain = volMilli <= 0 ? 0.0f : (static_cast<float>(volMilli) * 0.001f);

    if (gain <= 0.0f) {
        memset(ctx->floatWorkBuf, 0, static_cast<size_t>(totalSamples) * sizeof(float));
        ctx->fadeInRemaining = 0;
    } else {
        if (volMilli != 1000) {
            bool dummy = false;
            for (int i = 0; i < totalSamples; ++i) {
                ctx->floatWorkBuf[i] = sanitizeAndSoftLimitSample(ctx->floatWorkBuf[i] * gain, &dummy);
            }
        }
        if (ctx->fadeInRemaining > 0 && ctx->fadeInTotal > 0) {
            const float invTotal = 1.0f / static_cast<float>(ctx->fadeInTotal);
            for (int f = 0; f < frames && ctx->fadeInRemaining > 0; ++f) {
                const int done = ctx->fadeInTotal - ctx->fadeInRemaining;
                const float ramp = static_cast<float>(done) * invTotal;
                const int base = f * ch;
                for (int c = 0; c < ch; ++c) {
                    ctx->floatWorkBuf[base + c] *= ramp;
                }
                ctx->fadeInRemaining--;
            }
        }
    }

    if (ctx->backend == BACKEND_AAUDIO && ctx->aaudioStream != nullptr) {
        if (!writeFullAAudioFramesLocked(ctx, ctx->floatWorkBuf, frames, ch)) {
            // 蓝牙耳机插拔等路由变更导致流断开：自动重开 AAudio 流，重开失败则无缝切到 Java AudioTrack
            closeAAudioStreamLocked(ctx);
            unsigned r = ctx->sampleRate;
            unsigned c = ctx->channels;
            if (openAAudioStreamLocked(ctx, &r, &c) && r == ctx->sampleRate && c == ctx->channels) {
                armFadeInLocked(ctx, r, 6);
                writeFullAAudioFramesLocked(ctx, ctx->floatWorkBuf, frames, ch);
            } else {
                closeAAudioStreamLocked(ctx);
                JNIEnv* env = getThreadEnv();
                if (openJavaAudioTrackLocked(ctx, env, ctx->sampleRate, ctx->channels)) {
                    writeJavaAudioTrackFromFloatLocked(ctx, env, ctx->floatWorkBuf, totalSamples);
                }
            }
        }
    } else if (ctx->backend == BACKEND_JAVA_AUDIOTRACK) {
        JNIEnv* env = getThreadEnv();
        writeJavaAudioTrackFromFloatLocked(ctx, env, ctx->floatWorkBuf, totalSamples);
    }

    pthread_mutex_unlock(&ctx->audioMutex);
}

static void onVlcAudioPause(void* data, int64_t pts) {
    (void)pts;
    BridgeContext* ctx = static_cast<BridgeContext*>(data);
    if (!ctx || __atomic_load_n(&ctx->detached, __ATOMIC_ACQUIRE) != 0) return;

    __atomic_fetch_add(&ctx->flushSerial, 1, __ATOMIC_RELEASE);
    pthread_mutex_lock(&ctx->audioMutex);
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
    pthread_mutex_unlock(&ctx->audioMutex);
}

static void onVlcAudioResume(void* data, int64_t pts) {
    (void)pts;
    BridgeContext* ctx = static_cast<BridgeContext*>(data);
    if (!ctx || __atomic_load_n(&ctx->detached, __ATOMIC_ACQUIRE) != 0) return;

    pthread_mutex_lock(&ctx->audioMutex);
    armFadeInLocked(ctx, ctx->sampleRate > 0 ? ctx->sampleRate : 48000u, 4);
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
    pthread_mutex_unlock(&ctx->audioMutex);
}

static void onVlcAudioFlush(void* data, int64_t pts) {
    (void)pts;
    BridgeContext* ctx = static_cast<BridgeContext*>(data);
    if (!ctx || __atomic_load_n(&ctx->detached, __ATOMIC_ACQUIRE) != 0) return;

    __atomic_fetch_add(&ctx->flushSerial, 1, __ATOMIC_RELEASE);
    pthread_mutex_lock(&ctx->audioMutex);
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
    armFadeInLocked(ctx, curRate, 5);

    if (ctx->backend == BACKEND_AAUDIO && ctx->aaudioStream != nullptr) {
        g_aaudio.streamRequestPause(ctx->aaudioStream);
        g_aaudio.streamRequestFlush(ctx->aaudioStream);
        ctx->aaudioStarted = false;
    } else if (ctx->backend == BACKEND_JAVA_AUDIOTRACK && env && ctx->callbackGlobal && ctx->midOnAudioFlush) {
        env->CallVoidMethod(ctx->callbackGlobal, ctx->midOnAudioFlush);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    pthread_mutex_unlock(&ctx->audioMutex);
}

static void onVlcAudioDrain(void* data) {
    BridgeContext* ctx = static_cast<BridgeContext*>(data);
    if (!ctx || __atomic_load_n(&ctx->detached, __ATOMIC_ACQUIRE) != 0) return;

    pthread_mutex_lock(&ctx->audioMutex);
    if (__atomic_load_n(&ctx->asrEnabled, __ATOMIC_ACQUIRE) != 0) {
        JNIEnv* env = getThreadEnv();
        flushAsrBatchToJava(env, ctx);
    }
    pthread_mutex_unlock(&ctx->audioMutex);
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

    // 路径 1：优先调用 VLCObject.getInstance()J（libvlcjni 3.2+ 官方提供的原生指针读取接口）
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

    // 路径 2：回退读取 VLCObject.mInstance（指向 vlcjni_object 结构体，首字段 u.p_mp 为 libvlc_media_player_t*）
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

    // 通过对比 LibVLC 实例指针，兼容任意 32/64 位架构下的 vlcjni_object 字段排列
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

    pthread_mutex_init(&ctx->audioMutex, nullptr);
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
        pthread_mutex_destroy(&ctx->audioMutex);
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
    pthread_mutex_lock(&ctx->audioMutex);
    int b = static_cast<int>(ctx->backend);
    pthread_mutex_unlock(&ctx->audioMutex);
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
    __atomic_fetch_add(&ctx->flushSerial, 1, __ATOMIC_RELEASE);

    if (ctx->mp && g_vlc.available) {
        g_vlc.set_callbacks(ctx->mp, nullptr, nullptr, nullptr, nullptr, nullptr, nullptr);
        g_vlc.set_format_callbacks(ctx->mp, nullptr, nullptr);
        if (g_vlc.set_volume_callback) {
            g_vlc.set_volume_callback(ctx->mp, nullptr);
        }
    }

    pthread_mutex_lock(&ctx->audioMutex);
    closeAAudioStreamLocked(ctx);
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
    free(ctx->scaledPcmBuf);
    ctx->scaledPcmBuf = nullptr;
    free(ctx->floatWorkBuf);
    ctx->floatWorkBuf = nullptr;
    free(ctx->monoScratchBuf);
    ctx->monoScratchBuf = nullptr;
    free(ctx->resampledScratchBuf);
    ctx->resampledScratchBuf = nullptr;
    pthread_mutex_unlock(&ctx->audioMutex);

    pthread_mutex_destroy(&ctx->audioMutex);
    free(ctx);
}
