package com.aisub.player.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aisub.player.MainViewModel
import com.aisub.player.subtitle.OnlineSubtitle
import com.aisub.player.subtitle.OnlineSubtitleSource
import kotlinx.coroutines.launch

/** 在线搜索并下载字幕（射手网 / OpenSubtitles），下载后直接替换当前字幕。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineSubtitleSheet(vm: MainViewModel, onDismiss: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sources = remember { vm.onlineSubtitles().sources }
    var source by remember { mutableStateOf(sources.firstOrNull()) }
    var query by remember { mutableStateOf(vm.defaultSubtitleQuery()) }
    var results by remember { mutableStateOf<List<OnlineSubtitle>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf<OnlineSubtitle?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun search() {
        val src = source ?: return
        searching = true
        error = null
        scope.launch {
            try {
                results = vm.searchOnlineSubtitles(src, query)
            } catch (e: Exception) {
                results = null
                error = e.message ?: e.toString()
            }
            searching = false
        }
    }

    fun download(sub: OnlineSubtitle) {
        if (downloading != null) return
        downloading = sub
        error = null
        scope.launch {
            try {
                val n = vm.downloadOnlineSubtitle(sub)
                Toast.makeText(context, "已载入 $n 句字幕", Toast.LENGTH_SHORT).show()
                onDismiss()
            } catch (e: Exception) {
                error = "下载失败：${e.message ?: e}"
            }
            downloading = null
        }
    }

    LaunchedEffect(source) { if (source != null && query.isNotBlank()) search() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        
    ) {
        Column(Modifier.fillMaxHeight(0.9f).padding(horizontal = 16.dp)) {
            Text("在线下载字幕", color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(12.dp))

            if (sources.isEmpty()) {
                NoSourceHint(onOpenSettings)
                return@Column
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sources.forEach { s ->
                    FilterChip(selected = s == source, onClick = { source = s }, label = { Text(s.label) })
                }
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text("片名或文件名") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
                trailingIcon = { IconButton(onClick = { search() }) { Icon(AppIcons.Search, "搜索") } },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            if (source == OnlineSubtitleSource.OPENSUBTITLES) {
                Text(
                    "本地视频会附带文件哈希，标着「完全匹配」的字幕时间轴最准",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
                )
            }
            error?.let { Text(it, color = Color(0xFFFF6B5E), fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp)) }

            Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
                when {
                    searching -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Orange)
                    results?.isEmpty() == true -> Text(
                        "没有找到字幕，试试只保留片名（英文名通常更容易搜到）",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                    results != null -> LazyColumn {
                        items(results!!, key = { it.source.name + it.id }) { sub ->
                            ResultRow(sub, downloading = downloading == sub, onClick = { download(sub) })
                            HorizontalDivider(color = Color(0xFF2A2A2A))
                        }
                    }
                }
            }
            if (source == OnlineSubtitleSource.ASSRT) {
                Text("字幕服务由 assrt.net 提供", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f), fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun ResultRow(sub: OnlineSubtitle, downloading: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !downloading, onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(sub.title, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (sub.exactMatch) {
                    Text("完全匹配", color = Orange, fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                }
                if (sub.language.isNotEmpty()) {
                    Text(sub.language, color = Color(0xFFDDDDDD), fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                }
            }
            if (sub.info.isNotEmpty()) {
                Text(sub.info, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (downloading) CircularProgressIndicator(Modifier.size(24.dp), color = Orange, strokeWidth = 2.dp)
            else Icon(AppIcons.Download, "下载", tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun NoSourceHint(onOpenSettings: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("还没有配置字幕来源。两个都是免费的，填一个就能用：", color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
        Text(
            "• 射手网（中文字幕多）：在 assrt.net 注册后，到「用户面板」复制 API Token",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp,
        )
        Text(
            "• OpenSubtitles（外语片多）：在 opensubtitles.com 注册后，到 opensubtitles.com/consumers 新建一个 API Key；" +
                "可再填账号密码，不填每天限 5 次下载",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp,
        )
        Button(onClick = onOpenSettings) { Text("去设置填写") }
    }
}
