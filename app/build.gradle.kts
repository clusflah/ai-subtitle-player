import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.aisub.player"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aisub.player"
        minSdk = 26
        targetSdk = 36
        versionCode = 74
        versionName = "2.9.13"

        ndk {
            // 真机基本都是 arm64；x86_64 给模拟器用
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        // CI 通过环境变量提供固定的发布证书，保证每个版本签名一致、可以覆盖升级
        val keystore = System.getenv("RELEASE_KEYSTORE_FILE")
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 本地没有发布证书时退回 debug 证书，方便直接安装
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // 两个版本，同一个包名（可以互相覆盖安装、保留设置）：
    // standard 标准版；ytdlp 下载版，内置 yt-dlp（Python + FFmpeg，每个架构多约 50 MB），
    // 可以从其他 App 分享视频链接过来，自动下载、准备字幕、播放
    flavorDimensions += "edition"
    productFlavors {
        create("standard") {
            dimension = "edition"
            isDefault = true
        }
        create("ytdlp") {
            dimension = "edition"
            versionNameSuffix = "-dl"
        }
    }

    // 每个 CPU 架构单独出一个 APK（onnxruntime 原生库很大），另外保留一个通用包
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
                "META-INF/versions/**",
            )
        }
        jniLibs {
            pickFirsts += "lib/*/libc++_shared.so"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 语音识别 + VAD（k2-fsa/sherpa-onnx 官方发布的 AAR）
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.media3:media3-exoplayer:1.9.0")
    implementation("androidx.media3:media3-ui:1.9.0")
    // 后台播放：媒体会话、通知栏与锁屏控制、耳机按键
    implementation("androidx.media3:media3-session:1.9.0")
    // 直播与串流：HLS（m3u8）、DASH、RTSP
    implementation("androidx.media3:media3-exoplayer-hls:1.9.0")
    implementation("androidx.media3:media3-exoplayer-dash:1.9.0")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.9.0")
    // AC3、E-AC3、DTS、TrueHD 等手机系统通常不支持的音频格式，用 FFmpeg 软解（Jellyfin 预编译，版本需与 Media3 一致）
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1")
    // ISO（DVD / 蓝光镜像）以及 ExoPlayer 解不了的格式改用 VLC 内核
    implementation("org.videolan.android:libvlc-all:3.7.7")
    // 视频自带的 ASS / SSA 字幕用 libass 渲染（位置、多行避让、特效和 Plex、VLC 一致；ExoPlayer 自带的只会把所有行堆在底部）
    // 放在 VLC 后面：两者都带 libc++_shared.so，保留 VLC 那份（NDK 更新，向下兼容）
    implementation("io.github.peerless2012:ass-media:0.5.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // 电视上登录网盘：把授权地址、手机输入页显示成二维码
    implementation("com.google.zxing:core:3.5.3")

    // 翻译：Claude 官方 Java SDK；OpenAI 兼容接口用 OkHttp 直连
    implementation("com.anthropic:anthropic-java:2.34.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 解压模型包（tar.bz2）
    implementation("org.apache.commons:commons-compress:1.27.1")

    // 下载版：yt-dlp（含 Python 运行环境）和合并音视频用的 FFmpeg
    "ytdlpImplementation"("io.github.junkfood02.youtubedl-android:library:0.18.1")
    "ytdlpImplementation"("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // android.jar 里的 org.json 只是空壳
    // 需要真实 Android 类（Html、Bundle 等）的测试，如读取视频内嵌字幕
    testImplementation("org.robolectric:robolectric:4.14.1")
    // 流式识别：用本地 WebSocket 服务器模拟各家协议
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

androidComponents {
    // yt-dlp 要直接运行 so 里的 Python，原生库必须解压到磁盘
    onVariants(selector().withFlavor("edition" to "ytdlp")) { variant ->
        variant.packaging.jniLibs.useLegacyPackaging.set(true)
    }
}
