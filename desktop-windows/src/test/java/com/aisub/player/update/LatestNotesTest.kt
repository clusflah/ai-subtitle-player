package com.aisub.player.update

import org.junit.Assert.assertEquals
import org.junit.Test

class LatestNotesTest {
    @Test
    fun keepsOnlyLatestSectionAsPlainText() {
        val notes = """
            译幕 Voxtitle 是开源的安卓视频播放器。完整介绍见 [README](https://github.com/x/y#readme)。

            ## 本版更新（2.9.36）

            - **更新提示**：只显示这一版的更新
            - 网页菜单加 `点选屏蔽`，见 [说明](https://example.com)


            ## 下载哪个？

            | 文件 | 适用设备 |

            ## 2.9.35 更新

            - 旧的
        """.trimIndent()
        assertEquals(
            "• 更新提示：只显示这一版的更新\n• 网页菜单加 点选屏蔽，见 说明",
            AppUpdater.latestNotes(notes),
        )
    }

    @Test
    fun versionHeadingAndFallback() {
        assertEquals("• 修好了", AppUpdater.latestNotes("介绍\n\n## 2.9.30 更新\n\n- 修好了\n\n## 2.9.29 更新\n\n- 旧"))
        assertEquals("没有小节的说明", AppUpdater.latestNotes("没有小节的**说明**"))
    }

    @Test
    fun includesEveryVersionNewerThanInstalled() {
        val notes = "介绍\n\n## 本版更新（2.9.41）\n\n- 新三\n\n## 2.9.40 更新\n\n- 新二\n\n## 下载哪个？\n\n表格\n\n" +
            "## 2.9.39 更新\n\n- 新一\n\n## 2.9.38 更新\n\n- 已装"
        assertEquals("【2.9.41】\n• 新三\n\n【2.9.40】\n• 新二\n\n【2.9.39】\n• 新一", AppUpdater.notesSince(notes, "2.9.38"))
        assertEquals("• 新三", AppUpdater.notesSince(notes, "2.9.40"))
        assertEquals("• 新三", AppUpdater.notesSince(notes, "2.9.41-dl"))
    }
}
