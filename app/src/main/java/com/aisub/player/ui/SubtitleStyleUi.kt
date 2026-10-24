package com.aisub.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aisub.player.AppSettings
import com.aisub.player.subtitle.LineStyle

/** 画面上的一行字幕。底板不透明度为 0 时改用阴影，浅色画面上也看得清。 */
@Composable
fun SubtitleLine(text: String, style: LineStyle, globalScale: Float, pinyin: Boolean = false, modifier: Modifier = Modifier) {
    val color = Color(style.color)
    val size = (18 * style.scale * globalScale).sp
    val weight = if (style.bold) FontWeight.Medium else FontWeight.Normal
    val box = if (style.background > 0f) {
        modifier.background(Color.Black.copy(alpha = style.background)).padding(horizontal = 6.dp, vertical = 2.dp)
    } else {
        modifier.padding(horizontal = 6.dp, vertical = 2.dp)
    }
    if (pinyin) {
        PinyinText(text, color, size, fontWeight = weight, center = true, modifier = box)
        return
    }
    Text(
        text = text,
        style = TextStyle(
            color = color, fontSize = size, fontWeight = weight, textAlign = TextAlign.Center,
            shadow = if (style.background > 0f) null else Shadow(Color.Black, Offset(2f, 2f), 6f),
        ),
        modifier = box,
    )
}

/**
 * 按设置排好上下两行：默认两行叠在一起贴着底部；"上行放到画面顶部"时上面那行单独放顶上。
 * @param lines (文字, 是否译文行)，已按显示内容筛好
 * @param bottomPadding 下面那行离底部的距离（含控制栏让出的高度）
 * @param topPadding 上面那行放到顶部时额外让出的高度（状态栏）
 */
@Composable
fun BoxScope.StyledSubtitleLines(
    lines: List<Pair<String, Boolean>>,
    settings: AppSettings,
    bottomPadding: androidx.compose.ui.unit.Dp,
    topPadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    if (lines.isEmpty()) return
    val ordered = if (settings.originalOnTop) lines.sortedBy { it.second } else lines.sortedByDescending { it.second }
    @Composable
    fun line(l: Pair<String, Boolean>) = SubtitleLine(
        l.first, if (l.second) settings.translationStyle else settings.originalStyle, settings.subtitleScale,
        pinyin = l.second && settings.pinyinActive,
    )
    if (ordered.size == 2 && settings.topLineFromTop >= 0) {
        Box(Modifier.align(Alignment.TopCenter).padding(top = topPadding + settings.topLineFromTop.dp, start = 16.dp, end = 16.dp)) { line(ordered[0]) }
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPadding, start = 16.dp, end = 16.dp)) { line(ordered[1]) }
    } else {
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPadding, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(settings.subtitleLineGap.dp),
        ) { ordered.forEach { line(it) } }
    }
}

/** 16:9 的小预览，位置按真实距离的一半缩放 */
@Composable
fun SubtitleStylePreview(s: AppSettings) {
    val lines = buildList {
        if (s.showTranslation) add("这是一句翻译后的字幕" to true)
        if (s.showOriginal) add("This is the original line." to false)
    }
    val half = s.copy(subtitleOffset = s.subtitleOffset / 2, topLineFromTop = if (s.topLineFromTop >= 0) s.topLineFromTop / 2 else -1, subtitleLineGap = s.subtitleLineGap / 2)
    Box(
        Modifier.fillMaxWidth().padding(top = 8.dp).height(170.dp).clip(MaterialTheme.shapes.medium)
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF6D8BA0), Color(0xFF263238)))),
    ) {
        StyledSubtitleLines(lines, half, bottomPadding = half.subtitleOffset.dp)
    }
}

/** 字幕样式编辑：两行的顺序、位置，以及译文、原文各自的字号、颜色、底板、粗细。 */
@Composable
fun SubtitleStyleEditor(s: AppSettings, onChange: (AppSettings) -> Unit) {
    Column {
        SubtitleStylePreview(s)
        Spacer(Modifier.height(12.dp))
        Label("整体字号 ${(s.subtitleScale * 100).toInt()}%")
        Slider(value = s.subtitleScale, onValueChange = { onChange(s.copy(subtitleScale = it)) }, valueRange = 0.7f..1.8f, steps = 10)
        Label("下面那行离底部 ${s.subtitleOffset}dp")
        Slider(value = s.subtitleOffset.toFloat(), onValueChange = { onChange(s.copy(subtitleOffset = it.toInt())) }, valueRange = 8f..160f)

        Label("双语顺序")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!s.originalOnTop, { onChange(s.copy(originalOnTop = false)) }, label = { Text("译文在上") })
            FilterChip(s.originalOnTop, { onChange(s.copy(originalOnTop = true)) }, label = { Text("原文在上") })
        }
        Spacer(Modifier.height(8.dp))
        Label("上面那行的位置")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(s.topLineFromTop < 0, { onChange(s.copy(topLineFromTop = -1)) }, label = { Text("紧挨下面那行") })
            FilterChip(s.topLineFromTop >= 0, { onChange(s.copy(topLineFromTop = if (s.topLineFromTop >= 0) s.topLineFromTop else 24)) }, label = { Text("放到画面顶部") })
        }
        if (s.topLineFromTop >= 0) {
            Label("离顶部 ${s.topLineFromTop}dp")
            Slider(value = s.topLineFromTop.toFloat(), onValueChange = { onChange(s.copy(topLineFromTop = it.toInt())) }, valueRange = 0f..200f)
        } else {
            Label("两行间距 ${s.subtitleLineGap}dp")
            Slider(value = s.subtitleLineGap.toFloat(), onValueChange = { onChange(s.copy(subtitleLineGap = it.toInt())) }, valueRange = 0f..40f)
        }

        Spacer(Modifier.height(8.dp))
        LineStyleEditor("译文", s.translationStyle) { onChange(s.copy(translationStyle = it)) }
        Spacer(Modifier.height(8.dp))
        LineStyleEditor("原文", s.originalStyle) { onChange(s.copy(originalStyle = it)) }
    }
}

@Composable
private fun LineStyleEditor(name: String, style: LineStyle, onChange: (LineStyle) -> Unit) {
    Text(name + "行", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 4.dp))
    Label("字号 ${(style.scale * 100).toInt()}%")
    Slider(value = style.scale, onValueChange = { onChange(style.copy(scale = it)) }, valueRange = 0.5f..2f)
    Label("颜色")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        LineStyle.COLORS.forEach { (c, label) ->
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(Color(c))
                    .border(if (style.color == c) 3.dp else 1.dp, if (style.color == c) Orange else Color(0x66000000), CircleShape)
                    .clickable { onChange(style.copy(color = c)) },
            ) { if (style.color == c) Text("✓", color = Color.Black, modifier = Modifier.align(Alignment.Center), fontSize = 14.sp) }
        }
    }
    Label(if (style.background > 0f) "底板 ${(style.background * 100).toInt()}%" else "无底板（文字加阴影）")
    Slider(value = style.background, onValueChange = { onChange(style.copy(background = if (it < 0.03f) 0f else it)) }, valueRange = 0f..1f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("粗体", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = style.bold, onCheckedChange = { onChange(style.copy(bold = it)) })
    }
    Spacer(Modifier.width(1.dp))
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
