package com.aisub.player.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aisub.player.manga.MangaArchive
import com.aisub.player.manga.MangaBook
import com.aisub.player.manga.MangaPage
import com.aisub.player.manga.MangaRecent
import com.aisub.player.manga.MangaStore
import com.aisub.player.manga.NaturalOrder
import com.aisub.player.media.MediaEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 打开一本漫画：一组图片（看图时切到漫画模式），一个压缩包，或手机里的一个图片文件夹 */
sealed interface MangaOpen {
    val title: String

    data class Images(override val title: String, val key: String, val pages: List<MangaPage>, val start: Int) : MangaOpen
    /** url：content://、file://、网盘 cloud://、WebDAV 地址 */
    data class Archive(override val title: String, val url: String) : MangaOpen
    /** 系统文件选择器选的文件夹（content://…/tree/…） */
    data class Folder(override val title: String, val tree: String) : MangaOpen
}

/** 漫画首页：继续阅读、打开本地漫画、网页漫画的说明 */
@Composable
fun MangaScreen(onBack: () -> Unit, openWeb: (String?) -> Unit, open: (MangaOpen) -> Unit) {
    val context = LocalContext.current
    remember { MangaStore.init(context) }
    val recent by MangaStore.recent.collectAsStateWithLifecycle()

    val pickArchive = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // 记住权限：「继续阅读」下次还能打开
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        open(MangaOpen.Archive(displayName(context, uri) ?: "漫画", uri.toString()))
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':').substringAfterLast('/').ifEmpty { "漫画" }
        open(MangaOpen.Folder(name, uri.toString()))
    }
    fun launch(block: () -> Unit) {
        // 有的电视没有系统文件选择器
        runCatching(block).onFailure { Toast.makeText(context, "这台设备没有系统文件选择器，可以把漫画放到网盘、WebDAV 里打开", Toast.LENGTH_LONG).show() }
    }

    DetailScaffold("漫画", onBack) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            item {
                SectionHeader("打开漫画")
                SurfaceCard(Modifier.padding(horizontal = 16.dp)) {
                    ListRow("网页漫画", "在网页里打开漫画网站，看到某一话时会出现「阅读模式」（电视：工具栏的书本按钮或菜单键）", AppIcons.Web, subtitleMaxLines = 3) { openWeb(null) }
                    HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    ListRow("CBZ / ZIP / PDF 漫画文件", "手机里的漫画文件；网盘、WebDAV 里的直接在文件列表里点开，文件管理器里也可以「用译幕打开」", AppIcons.File, subtitleMaxLines = 3) {
                        launch { pickArchive.launch(arrayOf("application/zip", "application/x-cbz", "application/vnd.comicbook+zip", "application/pdf", "application/octet-stream")) }
                    }
                    HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    ListRow("图片文件夹", "一个文件夹里的图片按文件名排序当一话看", AppIcons.Folder) { launch { pickFolder.launch(null) } }
                }
            }
            item { SectionHeader("继续阅读") }
            if (recent.isEmpty()) item {
                Text(
                    "还没有看过漫画。看过的网页漫画、本地漫画会记在这里，下次从上次的那一页接着看。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            items(recent, key = { it.key }) { r ->
                Box(Modifier.padding(horizontal = 16.dp, vertical = 3.dp)) {
                    SurfaceCard {
                        ListRow(
                            title = r.title,
                            subtitle = recentSubtitle(r),
                            icon = if (r.web) AppIcons.Web else AppIcons.MenuBook,
                            trailing = { IconButton(onClick = { MangaStore.removeRecent(r.key) }) { Icon(AppIcons.Close, "从列表移除") } },
                            onClick = {
                                when {
                                    r.web -> openWeb(r.location)
                                    r.location.startsWith(TREE) -> open(MangaOpen.Folder(r.title, r.location.removePrefix(TREE)))
                                    else -> open(MangaOpen.Archive(r.title, r.location))
                                }
                            },
                        )
                    }
                }
            }
            item {
                Text(
                    "遥控器：左右翻页，确认键显示控制栏，长按确认或菜单键打开阅读设置（适应屏幕、适应宽度、双页、长条，日漫从右往左）。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }
        }
    }
}

private const val TREE = "tree:"

private fun recentSubtitle(r: MangaRecent): String {
    val where = if (r.web) Uri.parse(r.location).host?.removePrefix("www.").orEmpty() else "本地"
    val days = ((System.currentTimeMillis() - r.time) / 86_400_000L).toInt()
    val time = when (days) { 0 -> "今天"; 1 -> "昨天"; else -> "$days 天前" }
    return "第 ${r.page + 1} / ${r.total} 页 · $where · $time"
}

private fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()?.substringBeforeLast('.')

/** 图片文件夹里的图片（按文件名自然排序） */
private fun folderPages(context: Context, tree: Uri): List<MangaPage> {
    val docId = DocumentsContract.getTreeDocumentId(tree)
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
    val list = ArrayList<Pair<String, String>>()
    context.contentResolver.query(
        children,
        arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
        null, null, null,
    )?.use { c ->
        while (c.moveToNext()) {
            val id = c.getString(0) ?: continue
            val name = c.getString(1) ?: continue
            val mime = c.getString(2).orEmpty()
            if (mime.startsWith("image/") || MediaEntry.isImageName(name)) list.add(name to id)
        }
    }
    return list.sortedWith { a, b -> NaturalOrder.compare(a.first, b.first) }
        .map { MangaPage(DocumentsContract.buildDocumentUriUsingTree(tree, it.second).toString()) }
}

/** 打开本地 / 网盘漫画：压缩包先准备好（网盘的要先下载），再进入阅读 */
@Composable
fun MangaReadScreen(open: MangaOpen, onBack: () -> Unit) {
    val context = LocalContext.current
    var book by remember(open) { mutableStateOf<MangaBook?>(null) }
    var error by remember(open) { mutableStateOf<String?>(null) }
    var progress by remember(open) { mutableFloatStateOf(-1f) }
    var attempt by remember(open) { mutableStateOf(0) }

    LaunchedEffect(open, attempt) {
        error = null
        runCatching {
            when (open) {
                is MangaOpen.Images -> MangaBook(open.key, open.title, open.pages, "local", recent = false)
                is MangaOpen.Archive -> {
                    val pages = MangaArchive.pages(context, open.url) { progress = it }
                    MangaBook(open.url, open.title, pages, "local")
                }
                is MangaOpen.Folder -> {
                    val pages = withContext(Dispatchers.IO) { folderPages(context, Uri.parse(open.tree)) }
                    MangaBook(TREE + open.tree, open.title, pages, "local")
                }
            }
        }.onSuccess {
            if (it.pages.isEmpty()) error = "里面没有图片" else book = it
        }.onFailure { error = it.message ?: it.toString() }
    }

    val b = book
    if (b != null) {
        MangaReader(b, onExit = onBack, startPage = (open as? MangaOpen.Images)?.start)
        return
    }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(24.dp)) {
            val e = error
            if (e == null) {
                Text(open.title, color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                if (progress >= 0f) {
                    Text("正在下载 ${(progress * 100).toInt()}%", color = Color.Gray)
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.width(240.dp))
                } else CircularProgressIndicator(color = Color.White)
            } else {
                Text("打不开：$e", color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { attempt++ }, modifier = Modifier.tvFocusRing(androidx.compose.foundation.shape.RoundedCornerShape(50))) { Text("重试") }
                TextButton(onClick = onBack, modifier = Modifier.tvFocusRing(androidx.compose.foundation.shape.RoundedCornerShape(50))) { Text("返回") }
            }
        }
    }
}
