package com.aisub.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.aisub.player.subtitle.Pinyin

/**
 * 每个汉字上方标注拼音（类似注音 ruby），标点和外文原样显示、与汉字底部对齐。
 * 拼音字号是正文的一半，颜色略淡。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PinyinText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    center: Boolean = false,
) {
    // 外文按单词拆开，长句才能折行
    val lines = remember(text) {
        text.split('\n').map { line ->
            Pinyin.segments(line).flatMap { seg ->
                if (seg.pinyin != null) listOf(seg) else seg.text.split(WORD_BREAK).filter { it.isNotEmpty() }.map { Pinyin.Segment(it, null) }
            }
        }
    }
    val pinyinSize = fontSize * 0.5f
    val pinyinColor = color.copy(alpha = color.alpha * 0.8f)
    Column(modifier, horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start) {
        for (segments in lines) {
            FlowRow(
                horizontalArrangement = if (center) Arrangement.Center else Arrangement.Start,
            ) {
                for (seg in segments) {
                    Column(Modifier.align(Alignment.Bottom).padding(horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        // 非汉字片段上方留同样高度的空行，保证整行底部对齐
                        Text(seg.pinyin ?: " ", color = pinyinColor, fontSize = pinyinSize, maxLines = 1, softWrap = false)
                        Text(seg.text, color = color, fontSize = fontSize, fontWeight = fontWeight, softWrap = false)
                    }
                }
            }
        }
    }
}

/** 在空白之后断开，空格留在前一个词末尾 */
private val WORD_BREAK = Regex("(?<=\\s)")
