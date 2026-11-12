import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20"
    id("org.jetbrains.compose") version "1.7.3"
}

group = "com.aisub.player"
version = "2.9.84"

// 复用或准备 TDLib Java 源码与 sherpa-onnx Java 接口
val tdlibTag = "tdlib-1.8.69-4c6377aa73"
val tdlibSha256 = "147377ef259c3615e578c894c8c3f74f9519f2ff473c030c41dba602dea1238c"

fun prepareTdlib(dir: File): File {
    val done = File(dir, ".ok")
    if (done.exists()) return dir
    val parentCache = file("../.gradle/tdlib/$tdlibTag")
    if (File(parentCache, ".ok").exists()) return parentCache
    dir.mkdirs()
    val zip = File(dir, "$tdlibTag.zip")
    if (!zip.exists()) {
        val url = "https://github.com/clusflah/ai-subtitle-player/releases/download/$tdlibTag/$tdlibTag.zip"
        logger.lifecycle("下载 TDLib：$url")
        URI(url).toURL().openStream().use { input -> zip.outputStream().use { input.copyTo(it) } }
    }
    val digest = MessageDigest.getInstance("SHA-256")
    zip.inputStream().use { input ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
    }
    val sha = digest.digest().joinToString("") { "%02x".format(it) }
    if (sha != tdlibSha256) {
        zip.delete()
        throw GradleException("TDLib 压缩包校验不对：$sha")
    }
    ZipFile(zip).use { z ->
        for (e in z.entries()) {
            val out = File(dir, e.name)
            if (!out.canonicalPath.startsWith(dir.canonicalPath)) throw GradleException("TDLib 压缩包里有异常路径：${e.name}")
            if (e.name.contains("/javadoc/")) continue
            if (e.isDirectory) out.mkdirs() else {
                out.parentFile.mkdirs()
                z.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it) } }
            }
        }
    }
    zip.delete()
    done.writeText(tdlibSha256)
    return dir
}

fun extractSherpaJar(aarFile: File, outJar: File): File {
    if (outJar.exists() && outJar.length() > 0L) return outJar
    outJar.parentFile.mkdirs()
    val tmpRaw = File(outJar.parentFile, "sherpa-raw.jar")
    ZipFile(aarFile).use { zip ->
        val entry = zip.getEntry("classes.jar") ?: throw GradleException("sherpa-onnx AAR 中缺少 classes.jar")
        zip.getInputStream(entry).use { input ->
            tmpRaw.outputStream().use { output -> input.copyTo(output) }
        }
    }
    val targetBytes = byteArrayOf(0x00, 0x10) + "java/lang/System".toByteArray(Charsets.US_ASCII)
    val replaceBytes = byteArrayOf(0x00, 0x10) + "android/os/LibLd".toByteArray(Charsets.US_ASCII)
    ZipFile(tmpRaw).use { rawZip ->
        ZipOutputStream(outJar.outputStream()).use { zos ->
            for (e in rawZip.entries()) {
                zos.putNextEntry(ZipEntry(e.name))
                if (!e.isDirectory) {
                    val bytes = rawZip.getInputStream(e).use { it.readBytes() }
                    if (e.name.endsWith(".class")) {
                        var i = 0
                        while (i <= bytes.size - targetBytes.size) {
                            var match = true
                            for (j in targetBytes.indices) {
                                if (bytes[i + j] != targetBytes[j]) {
                                    match = false
                                    break
                                }
                            }
                            if (match) {
                                System.arraycopy(replaceBytes, 0, bytes, i, replaceBytes.size)
                                i += targetBytes.size
                            } else {
                                i++
                            }
                        }
                    }
                    zos.write(bytes)
                }
                zos.closeEntry()
            }
        }
    }
    tmpRaw.delete()
    return outJar
}

val sherpaWinNativeSha256 = "33fbdbd5410e9ba9bdda94aa164ec8f7825bb49246420d8ce9bdd88219d97039"
fun prepareSherpaWinNative(outFile: File): File {
    if (outFile.exists() && outFile.length() > 1_000_000L) return outFile
    val parentCache = file("../.gradle/sherpa-win/sherpa-onnx-native-lib-win-x64-1.13.8.jar")
    if (parentCache.exists() && parentCache.length() > 1_000_000L) {
        outFile.parentFile.mkdirs()
        parentCache.copyTo(outFile, overwrite = true)
        return outFile
    }
    outFile.parentFile.mkdirs()
    val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-native-lib-win-x64-1.13.8.jar"
    logger.lifecycle("下载 sherpa-onnx Windows x64 原生库：$url")
    URI(url).toURL().openStream().use { input -> outFile.outputStream().use { input.copyTo(it) } }
    val digest = MessageDigest.getInstance("SHA-256")
    outFile.inputStream().use { input ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
    }
    val sha = digest.digest().joinToString("") { "%02x".format(it) }
    if (sha != sherpaWinNativeSha256) {
        outFile.delete()
        throw GradleException("sherpa-onnx Windows 原生库校验不对：$sha")
    }
    return outFile
}

val tdlibDir: File = prepareTdlib(layout.buildDirectory.dir("tdlib/$tdlibTag").get().asFile)
val sherpaJar: File = extractSherpaJar(
    file("../app/libs/sherpa-onnx-1.13.8.aar"),
    layout.buildDirectory.file("extracted-libs/sherpa-onnx-classes-patched.jar").get().asFile,
)
val sherpaWinNativeJar: File = prepareSherpaWinNative(
    layout.buildDirectory.file("sherpa-win/sherpa-onnx-native-lib-win-x64-1.13.8.jar").get().asFile,
)

sourceSets {
    main {
        java.srcDirs("src/main/java", "src/main/kotlin", File(tdlibDir, "tdlib/java"))
        resources.srcDirs("src/main/resources", "../app/src/main/assets")
    }
    test {
        java.srcDirs("src/test/java", "src/test/kotlin")
        resources.srcDirs("src/test/resources")
    }
}

tasks.withType<Copy> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-Xjvm-default=all",
        )
    }
}

dependencies {
    implementation(files(sherpaJar))
    implementation(files(sherpaWinNativeJar))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("androidx.annotation:annotation:1.9.1")

    // Jetpack Lifecycle & ViewModel (Compose Multiplatform)
    implementation("org.jetbrains.androidx.lifecycle:lifecycle-common:2.8.4")
    implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime:2.8.4")
    implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel:2.8.4")
    implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")

    // 网络与 AI 翻译 / 识别
    implementation("com.anthropic:anthropic-java:2.34.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")

    // 压缩包与模型解压（tar.bz2 / zip / 7z）
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.9")

    // 二维码生成（局域网传输、网盘扫码登录）
    implementation("com.google.zxing:core:3.5.3")

    // ICU4J（汉字转带声调拼音 Han-Latin、简繁体精确识别）
    implementation("com.ibm.icu:icu4j:75.1")

    // PDF 漫画与电子书渲染
    implementation("org.apache.pdfbox:pdfbox:2.0.32")

    // Windows 视频/音频/直播播放器内核（LibVLC 桌面绑定 + Java 原生音频）
    implementation("uk.co.caprica:vlcj:4.8.3")

    // Windows 原生 API（WASAPI 系统声音捕获、置顶穿透字幕窗、DWM 窗口特效）
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

compose.desktop {
    application {
        mainClass = "com.aisub.player.desktop.DesktopMainKt"
        jvmArgs += listOf(
            "-Dfile.encoding=UTF-8",
            "-Djna.encoding=UTF-8",
            "-Dsun.java2d.uiScale.enabled=true",
        )
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Voxtitle"
            packageVersion = version.toString()
            description = "Voxtitle - AI Subtitle Player and Media Center"
            vendor = "Voxtitle"
            includeAllModules = true
            windows {
                menuGroup = "Voxtitle"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "7ed04c25-d8a7-4e8e-b525-a64d815f786a"
            }
        }
    }
}
