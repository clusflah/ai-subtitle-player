package com.aisub.player

import android.net.Uri
import android.os.Environment
import com.aisub.player.media.MediaEntry
import com.aisub.player.meta.MediaInfo
import com.aisub.player.meta.MetaRepository
import com.aisub.player.meta.MetaSource
import com.aisub.player.net.AppProxy
import com.aisub.player.net.CnNetworkBypass
import com.aisub.player.net.ProxyType
import com.aisub.player.subtitle.Cue
import com.aisub.player.subtitle.SidecarSrt
import com.aisub.player.subtitle.SubtitleStore
import com.aisub.player.translate.FailoverTranslator
import com.aisub.player.translate.Translator
import java.io.File
import java.io.IOException
import java.net.Proxy
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 验证多端默认配置、故障回退（ASR / 翻译 / 影视信息）、PLAY_KERNEL 重构、
 * CN 网络直连规则以及非破坏性字幕备份与旁挂写入。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsDefaultsTest {

    @Test
    fun defaultsAndTvVsPhoneAsrAndKernelMigration() {
        val defaults = AppSettings()
        assertFalse(defaults.liquidGlass)
        assertEquals(PlayerKernel.AUTO, defaults.preferredKernel)
        assertTrue(defaults.autoDolbyMpv)
        assertFalse(defaults.dlAutoAsr)
        assertEquals(AsrEngine.LOCAL, defaults.asrEngine)
        assertEquals("sense-voice", defaults.localModel)
        assertEquals(Provider.INDEX_TRANSLATE, defaults.provider)
        assertEquals("DOUBAN", defaults.metaPrimary)
        assertTrue(defaults.proxyBypassCn)

        // 旧版 "EXO" 迁移到 "AUTO"，其他枚举保留
        assertEquals(PlayerKernel.AUTO, PlayerKernel.fromStored("EXO"))
        assertEquals(PlayerKernel.AUTO, PlayerKernel.fromStored("AUTO"))
        assertEquals(PlayerKernel.AUTO, PlayerKernel.fromStored(null))
        assertEquals(PlayerKernel.MEDIA3, PlayerKernel.fromStored("MEDIA3"))
        assertEquals(PlayerKernel.MPV, PlayerKernel.fromStored("MPV"))
        assertEquals(PlayerKernel.VLC, PlayerKernel.fromStored("VLC"))

        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        val forceTv = File(app.filesDir, "force_tv")
        try {
            prefs.edit().clear().commit()
            forceTv.delete()
            val phoneLoaded = SettingsRepository(app).load()
            assertEquals(AsrEngine.LOCAL, phoneLoaded.asrEngine)
            assertEquals(Provider.INDEX_TRANSLATE, phoneLoaded.provider)
            assertEquals("DOUBAN", phoneLoaded.metaPrimary)
            assertEquals(PlayerKernel.AUTO, phoneLoaded.preferredKernel)

            prefs.edit().clear().commit()
            forceTv.writeText("1")
            val tvLoaded = SettingsRepository(app).load()
            assertEquals(AsrEngine.VOLC, tvLoaded.asrEngine)
            assertEquals(Provider.INDEX_TRANSLATE, tvLoaded.provider)
        } finally {
            forceTv.delete()
            prefs.edit().clear().commit()
        }
    }

    @Test
    fun asrCandidateEnginesFallbackOrderIncludesConfiguredCloudAndLocal() {
        val unconfiguredVolc = AppSettings(asrEngine = AsrEngine.VOLC)
        val c1 = unconfiguredVolc.candidateAsrEngines()
        assertEquals(listOf(AsrEngine.LOCAL), c1)

        val configuredVolcAndAzure = unconfiguredVolc
            .withAsrValue(AsrEngine.AZURE, "key", "az-key-1")
            .withAsrValue(AsrEngine.AZURE, "region", "eastasia")
        val c2 = configuredVolcAndAzure.candidateAsrEngines()
        assertEquals(listOf(AsrEngine.AZURE, AsrEngine.LOCAL), c2)

        val bothConfigured = configuredVolcAndAzure.withAsrValue(AsrEngine.VOLC, "appKey", "volc-key")
        val c3 = bothConfigured.candidateAsrEngines()
        assertEquals(listOf(AsrEngine.VOLC, AsrEngine.AZURE, AsrEngine.LOCAL), c3)
    }

    @Test
    fun failoverTranslatorFallsBackOnErrorAndBlankResultAndPreservesCancellation() = runBlocking {
        var callsPrimary = 0
        var callsBackup = 0
        val notified = mutableListOf<Pair<Provider, Provider>>()
        val primaryFailing = object : Translator {
            override suspend fun translate(lines: List<String>, context: List<String>, targetLanguage: String): List<String?> {
                callsPrimary++
                throw IOException("timeout")
            }

            override suspend fun chat(system: String, user: String): String = throw IOException("timeout")
        }
        val blankReturning = object : Translator {
            override suspend fun translate(lines: List<String>, context: List<String>, targetLanguage: String): List<String?> =
                lines.map { "" }

            override suspend fun chat(system: String, user: String): String = ""
        }
        val backupHealthy = object : Translator {
            override suspend fun translate(lines: List<String>, context: List<String>, targetLanguage: String): List<String?> {
                callsBackup++
                return lines.map { "译:$it" }
            }

            override suspend fun chat(system: String, user: String): String = "ok"
        }

        val failover = FailoverTranslator(
            candidates = listOf(
                Provider.INDEX_TRANSLATE to primaryFailing,
                Provider.VOLC to blankReturning,
                Provider.DEEPSEEK to backupHealthy,
            ),
            onFallback = { from, to, _ -> notified += from to to },
        )

        val first = failover.translate(listOf("hello"), emptyList(), "简体中文")
        assertEquals(listOf("译:hello"), first)
        assertEquals(1, callsPrimary)
        assertEquals(1, callsBackup)
        assertEquals(listOf(Provider.INDEX_TRANSLATE to Provider.DEEPSEEK), notified)

        // Sticky preferredIndex avoids retrying failed primary on next batch
        val second = failover.translate(listOf("world"), emptyList(), "简体中文")
        assertEquals(listOf("译:world"), second)
        assertEquals(1, callsPrimary)
        assertEquals(2, callsBackup)

        val cancelling = object : Translator {
            override suspend fun translate(lines: List<String>, context: List<String>, targetLanguage: String): List<String?> {
                throw CancellationException("job cancelled")
            }

            override suspend fun chat(system: String, user: String): String = throw CancellationException("job cancelled")
        }
        val cancelFailover = FailoverTranslator(
            listOf(Provider.INDEX_TRANSLATE to cancelling, Provider.DEEPSEEK to backupHealthy),
        )
        var cancelled = false
        try {
            cancelFailover.translate(listOf("x"), emptyList(), "简体中文")
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    @Test
    fun cnNetworkBypassAndAppProxyBypassRules() {
        assertTrue(CnNetworkBypass.shouldBypass("example.cn"))
        assertTrue(CnNetworkBypass.shouldBypass("sub.xn--fiqs8s"))
        assertTrue(CnNetworkBypass.shouldBypass("movie.douban.com"))
        assertTrue(CnNetworkBypass.shouldBypass("api.bilibili.com"))
        assertTrue(CnNetworkBypass.shouldBypass("openspeech.bytedance.com"))
        assertTrue(CnNetworkBypass.shouldBypass("ark.cn-beijing.volces.com"))
        assertTrue(CnNetworkBypass.shouldBypass("114.114.114.114"))
        assertTrue(CnNetworkBypass.shouldBypass("223.5.5.5"))
        assertTrue(CnNetworkBypass.shouldBypass("2400:3200::1"))

        assertFalse(CnNetworkBypass.shouldBypass("8.8.8.8"))
        assertFalse(CnNetworkBypass.shouldBypass("1.1.1.1"))
        assertFalse(CnNetworkBypass.shouldBypass("api.openai.com"))
        assertFalse(CnNetworkBypass.shouldBypass("api.themoviedb.org"))

        val app = RuntimeEnvironment.getApplication()
        try {
            AppProxy.apply(app, ProxyType.HTTP, "198.51.100.2", 8080, bypassCn = true)
            assertEquals(listOf(Proxy.NO_PROXY), AppProxy.selector.select(URI("http://192.168.1.50:5005/")))
            assertEquals(listOf(Proxy.NO_PROXY), AppProxy.selector.select(URI("https://movie.douban.com/subject/123/")))
            val foreignWhenOn = AppProxy.selector.select(URI("https://api.themoviedb.org/3/movie/1"))
            assertEquals(Proxy.Type.HTTP, foreignWhenOn.first().type())

            AppProxy.apply(app, ProxyType.HTTP, "198.51.100.2", 8080, bypassCn = false)
            val cnWhenBypassOff = AppProxy.selector.select(URI("https://movie.douban.com/subject/123/"))
            assertEquals(Proxy.Type.HTTP, cnWhenBypassOff.first().type())
        } finally {
            AppProxy.apply(app, ProxyType.OFF, "", 0, bypassCn = true)
        }
    }

    @Test
    fun metaRepositoryFailoverWhenPrimaryThrowsOrHasMissingFields() = runBlocking {
        val sampleTmdb = MediaInfo(
            kind = "movie",
            tmdbId = 603,
            imdbId = "tt0133093",
            title = "黑客帝国",
            originalTitle = "The Matrix",
            year = "1999",
            overview = "计算机黑客尼奥发现现实世界其实是矩阵。",
            posterUrl = "https://image.tmdb.org/t/p/w500/p.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/w1280/b.jpg",
            genres = listOf("科幻"),
            length = "2 小时 16 分",
            rating = 8.2,
            voteCount = 24000,
            directors = listOf("沃卓斯基姐妹"),
            cast = listOf("基努·里维斯"),
            countries = listOf("美国"),
        )
        val settings = AppSettings(metaPrimary = "DOUBAN", doubanEnabled = true, tmdbKey = "k")
        val order = MetaRepository.sourceOrder(settings, includeBangumi = true)
        assertEquals(MetaSource.DOUBAN, order.first())
        assertTrue(MetaSource.TMDB in order)

        val (merged, anyOk) = MetaRepository.lookupWithSources(
            sources = listOf(MetaSource.DOUBAN, MetaSource.TMDB),
            requireBackdrop = true,
        ) { src, _ ->
            when (src) {
                MetaSource.DOUBAN -> throw IOException("403 rate limited")
                MetaSource.TMDB -> sampleTmdb
                else -> null
            }
        }
        assertTrue(anyOk)
        assertEquals("黑客帝国", merged?.title)
        assertEquals("https://image.tmdb.org/t/p/w1280/b.jpg", merged?.backdropUrl)
    }

    @Test
    fun subtitleBackupRestoreAndSidecarNonDestructiveWrite() {
        val app = RuntimeEnvironment.getApplication()
        val store = SubtitleStore(app)
        val videoUri = Uri.parse("file:///movies/test_backup_${System.nanoTime()}.mkv")
        val origCues = listOf(
            Cue(1, 0L, 1500L, "Hello world", null),
            Cue(2, 1600L, 3000L, "Original subtitle", null),
        )
        store.overwriteOriginalBackup(videoUri, origCues)
        assertTrue(store.hasOriginalBackup(videoUri))

        // Running saveOriginalBackupIfAbsent after AI overwrite must keep original snapshot intact
        val aiCues = listOf(Cue(1, 0L, 2000L, "你好世界", "Hello world"))
        store.saveOriginalBackupIfAbsent(videoUri, aiCues)
        assertEquals(origCues, store.loadOriginalBackup(videoUri))
        store.delete(videoUri)
        assertFalse(store.hasOriginalBackup(videoUri))

        // Existing unowned .srt on disk must never be overwritten by SidecarSrt.writeFile
        val relFolder = "Movies/SidecarTest_${System.nanoTime()}"
        val dir = File(Environment.getExternalStorageDirectory(), relFolder).apply { mkdirs() }
        try {
            val existingSrt = File(dir, "SampleVideo.srt")
            val userContent = "1\n00:00:00,000 --> 00:00:01,000\nUSER ORIGINAL SUBTITLE\n\n"
            existingSrt.writeText(userContent)
            val bytes = java.io.ByteArrayOutputStream().also {
                com.aisub.player.subtitle.writeSrt(
                    listOf(Cue(1, 0L, 1000L, "AI Text", "AI 译文")),
                    com.aisub.player.subtitle.SrtMode.BILINGUAL,
                    it,
                )
            }.toByteArray()
            val writtenName = SidecarSrt.writeFile(app, relFolder, "SampleVideo.srt", "SampleVideo.voxtitle.srt", bytes)
            assertEquals("SampleVideo.voxtitle.srt", writtenName)
            assertEquals(userContent, existingSrt.readText())
            val fallbackFile = File(dir, "SampleVideo.voxtitle.srt")
            assertTrue(fallbackFile.isFile)
            assertTrue(fallbackFile.readText().contains("AI Text"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
