译幕 Voxtitle 是开源的安卓视频播放器：用语音识别给视频生成字幕，大模型翻译成双语字幕，还能按角色配音朗读。完整介绍见 [README](https://github.com/clusflah/ai-subtitle-player#readme)。

## 下载哪个？

| 文件 | 适用设备 |
|---|---|
| `Voxtitle-*-arm64-v8a.apk` | **绝大多数安卓手机（推荐）** |
| `Voxtitle-*-armeabi-v7a.apk` | 很老的 32 位手机 |
| `Voxtitle-*-x86_64.apk` | 模拟器 |
| `Voxtitle-*-universal.apk` | 不确定时用这个（包含所有架构，体积最大） |

## 首次使用

1. 安装时需要允许"安装未知来源应用"。
2. 识别引擎可选（设置 → 语音识别）：
   - **本地模型**：免费离线，可选 8 种模型，首次使用时下载（来自 GitHub），国内网络慢可在设置里换成镜像地址。
   - **云端**：Azure、火山豆包、阿里 Fun-ASR、讯飞、ElevenLabs、Deepgram、OpenAI，填对应的 Key 即可。
3. 网盘直连：在「浏览 → 网盘」里登录——115 和阿里云盘用手机 App 扫码；百度网盘用自己申请的 AppKey 扫码，或粘贴 refresh_token；PikPak 用账号密码（可能需要人机验证）。也可以添加 WebDAV、OpenList。
4. 翻译可以先选 **Index-Translate**，免费、无需 API Key；也支持 Claude、DeepSeek、Gemini 和自定义 OpenAI 兼容接口。

## 本版更新（1.1.0）

- **朗读译文**：播放时跟随字幕时间朗读译文。可选 Azure 神经语音（在线获取目标语言的全部语音，Key 可沿用语音识别里的 Azure Key）或系统 TTS。语音比字幕长时自动加快，朗读时原声自动调小。在设置 → 朗读译文，或播放页「字幕」面板里打开
- **多角色配音**：播放页「字幕」→「角色与配音」，用翻译大模型识别每句台词是谁说的，按性别自动给每个角色分配不同的语音，也可以逐个更换、试听
- **原文已是目标语言时不翻译**：比如看中文视频、目标语言是简体中文，原文直接当译文，不再发给大模型（简体↔繁体仍会转换）；只显示一行，不重复
- 上一版（1.0.0）：改名「译幕 Voxtitle」、新图标、中文译文拼音、翻译模型下拉选择、内网 + 外网双地址

项目以 AGPL-3.0 许可证开源，源码：https://github.com/clusflah/ai-subtitle-player
