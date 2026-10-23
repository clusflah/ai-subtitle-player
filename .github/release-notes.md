译幕 Voxtitle 是开源的安卓视频播放器：用语音识别给视频生成字幕，大模型翻译成双语字幕，还能按角色配音朗读。完整介绍见 [README](https://github.com/clusflah/ai-subtitle-player#readme)。

## 下载哪个？

| 文件 | 适用设备 |
|---|---|
| `Voxtitle-*-arm64-v8a.apk` | **绝大多数安卓手机（推荐）** |
| `Voxtitle-*-armeabi-v7a.apk` | 很老的 32 位手机 |
| `Voxtitle-*-x86_64.apk` | 模拟器 |
| `Voxtitle-*-universal.apk` | 不确定时用这个（包含所有架构，体积最大） |

带 `-dl` 的是**下载版**（`Voxtitle-dl-*-arm64-v8a.apk` 等），内置 yt-dlp：在 YouTube、B 站等 App 里点「分享」选择译幕，就会自动下载视频、准备字幕并播放。比标准版大一些；两个版本可以互相覆盖安装，设置都会保留。

## 首次使用

1. 安装时需要允许"安装未知来源应用"。
2. 识别引擎可选（设置 → 语音识别）：
   - **本地模型**：免费离线，可选 8 种模型，首次使用时下载（来自 GitHub），国内网络慢可在设置里换成镜像地址。
   - **云端**：Azure、火山豆包、阿里 Fun-ASR、讯飞、ElevenLabs、Deepgram、OpenAI，填对应的 Key 即可。
3. 网盘直连：在「浏览 → 网盘」里登录——115 和阿里云盘用手机 App 扫码；百度网盘用自己申请的 AppKey 扫码，或粘贴 refresh_token；PikPak 用账号密码（可能需要人机验证）。也可以添加 WebDAV、OpenList。
4. 翻译可以先选 **Index-Translate**，免费、无需 API Key；也支持 Claude、DeepSeek、Gemini 和自定义 OpenAI 兼容接口。

## 本版更新（1.4.2）

- **配色改回品牌橙**：保留 1.4 的新版式（白色圆角卡片、可折叠设置、更轻的输入框），界面和播放器颜色恢复为橙色
- **设置页可折叠**：语音识别、字幕翻译、在线字幕、影视信息、字幕样式、视频下载、朗读译文各是一张卡片，点标题展开或收起；收起时显示当前配置摘要，配置不完整时标红提醒
- 首页标题栏显示当前页面说明
- 上一版（1.3.0）：下载版（分享链接自动下载、准备字幕、播放）、登录 cookies

项目以 AGPL-3.0 许可证开源，源码：https://github.com/clusflah/ai-subtitle-player
