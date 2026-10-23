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

## 本版更新（1.3.0）

- **新增下载版**（带 `-dl` 的安装包）：内置 yt-dlp，在 YouTube、B 站、抖音等 App 里点「分享」选择译幕，自动下载视频、保存到相册（Movies/Voxtitle）、准备字幕并播放。平台有字幕时直接使用（原文字幕 + 目标语言字幕作为译文），没有时自动 AI 识别再翻译
- **登录 cookies**：会员、登录后才能看的视频可以在「设置 → 视频下载 → 登录 cookies」里用应用内网页登录 B 站、抖音、YouTube 等，或导入电脑浏览器导出的 cookies.txt
- 下载设置：最高画质、是否自动 AI 识别、一键更新 yt-dlp
- 上一版（1.2.0）：视频自带字幕直接翻译、两种字幕分别开关

项目以 AGPL-3.0 许可证开源，源码：https://github.com/clusflah/ai-subtitle-player
