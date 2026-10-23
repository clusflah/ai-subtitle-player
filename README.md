<p align="center">
  <img src="docs/icon.png" width="128" alt="译幕 Voxtitle 图标">
</p>

<h1 align="center">译幕 Voxtitle</h1>

<p align="center">
  会自己听写、翻译、朗读字幕的安卓视频播放器
</p>

<p align="center">
  <a href="https://github.com/clusflah/ai-subtitle-player/releases/latest">下载最新版</a> ·
  <a href="#快速上手">快速上手</a> ·
  <a href="#功能详解">功能详解</a> ·
  <a href="#常见问题">常见问题</a>
</p>

---

没有字幕的视频，用语音识别现场生成带时间轴的字幕；外语字幕，交给大模型翻译成双语；想用耳朵听，还能按角色配音朗读译文。播放器本身仿 VLC 设计，本地文件、NAS、网盘、Emby / Plex 媒体服务器都能直接打开。

## 功能特点

**AI 字幕**
- 🎙️ **语音识别生成字幕**：8 种本地离线模型（免费、不联网）+ 7 家云端服务，边识别边显示，按逐字时间戳切成适合阅读的短句
- 🌐 **大模型翻译**：Claude、DeepSeek、Gemini、免费的 Index-Translate，或任意 OpenAI 兼容接口；结合上下文翻译，人名前后一致；模型列表可在线获取、下拉选择
- 🧠 **聪明地省钱**：原文已经是目标语言的句子（例如看中文视频）不发给大模型，直接显示原文；简体 ↔ 繁体仍会转换
- 🔤 **拼音标注**：中文译文可在每个字上方标注带声调的拼音，适合学中文
- 🗣️ **朗读译文**：跟随字幕时间朗读，大模型识别说话角色，每个角色配不同的声音
- 📥 **已有字幕**：导入 SRT / VTT / ASS，或从射手网、OpenSubtitles 在线下载；可导出双语 / 译文 / 原文 SRT

**播放器**
- 仿 VLC 的界面与手势：双击快进快退、滑动调亮度音量、长按 2 倍速
- 倍速、睡眠计时器、A-B 循环、均衡器、仅播放声音、画中画、锁屏、跳转到指定时间
- ExoPlayer 内核为主；DVD / 蓝光 ISO 镜像和疑难格式自动换用 VLC 内核
- 播放队列、播放列表、播放历史，记住每个视频的播放进度

**媒体来源**
- 手机媒体库、系统文件选择器、网络串流地址
- WebDAV、OpenList（AList）；可同时填内网和外网地址，在家自动走局域网
- 直连网盘：115、阿里云盘、百度网盘、PikPak、OneDrive、Dropbox、Google Drive
- 媒体服务器：Emby / Jellyfin、Plex（海报墙浏览）

**影视信息与文件管理**
- 详情页显示简介、海报、演职员、评分：优先读取视频旁的 NFO 和图片，缺的再从 TMDB、豆瓣、Bangumi、TheTVDB、Fanart.tv 补全
- 文件重命名、移动、复制、删除、分享、多选；网络位置和网盘可新建文件夹、删除、下载（保留目录结构）

## 快速上手

1. **安装**：到 [Releases](https://github.com/clusflah/ai-subtitle-player/releases/latest) 下载 APK。大多数手机选 `Voxtitle-*-arm64-v8a.apk`，不确定就选 `universal`。需要 Android 8.0 及以上。
2. **打开视频**：在「视频」标签里选一个视频，或者在文件管理器里「用其他应用打开」。
3. **生成字幕**：播放页点左下角「字幕与音轨」→「AI 生成字幕」。默认用本地 SenseVoice 模型，第一次会下载约 160 MB 的模型，之后完全离线。
4. **翻译**：到「设置 → 字幕翻译」选一个翻译服务。想零成本试用就选 **Index-Translate**（免费、无需 Key）。开启「识别时自动翻译」后，识别和翻译会同时进行。
5. **看双语字幕**：字幕面板里选「双语 / 原文 / 译文」，设置里还能调字号和位置。

> 识别是从头往后进行的。拖到还没识别到的位置时暂时没有字幕，等识别跟上即可。识别结果按视频缓存，下次打开直接加载。

## 功能详解

### 语音识别

在「设置 → 语音识别」选择引擎，并在下方「视频语言」里选择视频的语言（不确定选「自动」）。

**本地模型**（免费、离线，首次使用时下载；国内网络慢可在设置里把下载地址换成镜像）

| 模型 | 适合 | 大小 |
|---|---|---|
| SenseVoice（推荐） | 中 / 英 / 日 / 韩 / 粤，速度快，自带标点 | 约 160 MB |
| Paraformer 中文 | 普通话准确率高（配标点模型） | 约 280 MB |
| FireRedASR2 中英 | 中文和方言效果强 | 约 560 MB |
| Qwen3-ASR 0.6B | 多语种效果好，适合高端机 | 约 840 MB |
| Whisper base | 99 种语言，覆盖面最广 | 约 200 MB |
| Parakeet TDT 0.6B v3 | 英语及 25 种欧洲语言 | 约 460 MB |
| Moonshine base | 仅英语，体积小、速度快，适合低端机 | 约 240 MB |
| Dolphin base | 东亚、东南亚语言及中文方言 | 约 140 MB |

**云端服务**（填写各家的 API Key，设置里每个都有「测试连接」）

| 服务 | 特点 |
|---|---|
| Azure | 微软快速转写，多语种 |
| 火山豆包 | 中文影视口语效果好 |
| 阿里 Fun-ASR | 中文、方言、多语种 |
| 讯飞转写 | 老牌稳定，需要排队，较慢 |
| ElevenLabs Scribe | 多语种准确率第一梯队 |
| Deepgram | 英语又快又便宜 |
| OpenAI Whisper | 也可填 Groq 等兼容接口 |

### 字幕翻译

在「设置 → 字幕翻译」选择服务、填 API Key，点「模型」输入框右侧的箭头可以从服务商**在线获取模型列表**下拉选择，也可以手动填。

| 服务 | 说明 |
|---|---|
| Claude | Anthropic 官方 API，翻译质量高 |
| DeepSeek | 性价比高，国内可直连 |
| Gemini | Google AI Studio 的 API Key |
| Index-Translate | B 站开源翻译模型，公共接口免费、无需 Key |
| 自定义 | 任意 OpenAI 兼容接口：通义千问、Kimi、Ollama 本地模型等 |

- 目标语言可选简体中文、繁體中文、English、日本語、한국어，也可以手动输入任意语言
- 字幕每 20 句一批，带上前文一起翻译，语气和人名更连贯
- 某句翻得不好：字幕列表里长按 →「重新翻译这句」
- **原文已是目标语言时不翻译**：逐句判断，比如英文片里夹着几句中文，只跳过中文那几句；没有文字的句子（♪、数字）也直接跳过

### 拼音标注

「设置 → 字幕样式 → 中文译文加拼音」，或播放页字幕面板里一键开关。视频字幕和字幕列表里，中文的每个字上方会标出带声调的拼音。

- 需要 Android 10 及以上（使用系统自带的转换，不增加安装包体积）
- 目标语言是中文时才可用
- 能读对「重庆」等少数多音词；大部分多音字取最常用读音，可能不准

### 朗读译文与多角色配音

**打开朗读**：「设置 → 朗读译文」，或播放页「字幕与音轨」→「朗读译文」。

| 引擎 | 说明 |
|---|---|
| Azure 神经语音 | 音色自然，在线获取目标语言的全部语音；可以直接沿用语音识别里填的 Azure Key |
| 系统 TTS | 免费、可离线，音色取决于手机里安装的语音引擎 |

**跟随字幕时间**：每句字幕出现时开始朗读，后面几句提前在后台合成好。语音比字幕长时自动加快（最多 1.6 倍）。下一句开始时，上一句没读完会被打断，保证和画面对得上。暂停、拖动进度都会同步。朗读期间原声自动调小，音量在设置里调。

**多角色配音**：播放页「字幕与音轨」→「角色与配音」→「识别角色」。

1. 翻译用的大模型会根据台词、称呼、语气判断每句是谁说的，并推断角色的名字和性别
2. 按性别自动给每个角色分配一个不同的语音
3. 点任意角色可以换语音：语音列表按目标语言在线获取，可按男声 / 女声筛选，每个都能试听
4. 没识别出角色的台词（旁白）用「默认语音」
5. 字幕列表里每句时间后面会显示说话人

> 角色识别只根据台词文字判断，独白或多人快速对话时可能分错，可以手动换语音。Index-Translate 是专用翻译模型，不能识别角色，请换用 Claude、DeepSeek 等通用大模型。

### 已有字幕

- **选择字幕文件**：支持 SRT、VTT、ASS；中英双行的字幕会自动拆成原文和译文
- **在线下载字幕**：射手网（中文字幕多，需在 assrt.net 免费申请 Token）、OpenSubtitles（外语片多，需免费申请 API Key）
- **内嵌字幕**：MKV、蓝光等自带的字幕轨在「字幕与音轨」面板里选择
- **字幕延迟**：字幕和声音对不上时可以整体提前或推后
- **导出字幕**：双语 / 仅译文 / 仅原文 SRT

### 播放器操作

| 操作 | 效果 |
|---|---|
| 单击 | 显示 / 隐藏控制栏 |
| 双击左侧 / 右侧 | 快退 / 快进 10 秒 |
| 双击中间 | 暂停 / 继续 |
| 左半边上下滑 | 调亮度 |
| 右半边上下滑 | 调音量 |
| 左右滑 | 拖动进度 |
| 长按 | 2 倍速播放，松开恢复 |

右下角「更多」里有：锁定屏幕、睡眠计时器（定时或本集结束后暂停）、播放速度、转到时间、均衡器、仅播放声音、弹出播放器（画中画）、循环模式、视频信息、A-B 循环、保存播放列表。

DVD / 蓝光 ISO 镜像自动用 VLC 内核播放，直接进入正片。普通视频播放失败时，可以在错误提示里选「换用 VLC 内核播放」。

### 网络位置与网盘

在「浏览」标签添加：

- **WebDAV / OpenList**：填地址和账号。家宽用域名访问时，可以再填一个**内网地址**：在家连得上内网就走局域网（速度快、不占上行带宽），出门自动走外网；历史和播放列表里的链接会自动跟着切换
- **115、阿里云盘**：用手机 App 扫码登录
- **百度网盘**：用自己申请的 AppKey 扫码，或粘贴 refresh_token
- **PikPak**：账号密码（可能需要人机验证）
- **OneDrive、Dropbox、Google Drive**：需要在各自开发者平台免费创建应用，应用内有步骤说明
- **Emby / Jellyfin**：服务器地址 + 账号，同样可以填内网地址；默认海报墙显示
- **Plex**：浏览器授权登录后自动找到服务器，按局域网 → 公网 → 中继的顺序连接

文件和文件夹的「⋮」菜单可以重命名、删除（网盘移到回收站）、下载；右上角「⋮」可新建文件夹。下载保存到 `Download/Voxtitle`，保留目录结构，进度见通知栏。

### 影视信息

网络位置和网盘里点视频进入详情页；本地视频在「⋮」菜单里选「影视信息」。

- **优先读本地刮削文件**（Kodi / Emby / Jellyfin / tinyMediaManager 格式）：`视频名.nfo`、`movie.nfo`、`tvshow.nfo`，`poster.jpg`、`folder.jpg`、`视频名-poster.jpg`，`fanart.jpg`、`视频名-fanart.jpg`，以及文件夹名里的 `{tmdb-123}`、`[imdbid-tt123]`
- **缺的再联网补全**：TMDB（需 Key，国内可填反代地址）、豆瓣（不用 Key）、Bangumi（动画，不用 Key）、TheTVDB（分集信息，需 Key）、Fanart.tv（标题图、背景图，需 Key）
- 在「设置 → 影视信息」选主数据源；匹配错了点「重新匹配」
- 图片可滑动浏览、缩放，文本文件可直接预览

### 本地文件管理

文件的「⋮」菜单可以重命名、移动、复制、删除、分享；文件夹可以重命名、整体移动或删除；长按进入多选。顶部搜索可以按文件名、文件夹，甚至**字幕内容**查找视频。

## 常见问题

**完全免费能用吗？**
能。语音识别用本地模型（离线、免费），翻译选 Index-Translate（公共接口免费），朗读用系统 TTS。

**API Key 安全吗？**
所有 Key 只保存在手机本地（应用私有存储），只发给对应的服务商。项目开源，可以自行审查代码。

**识别到一半切到后台会怎样？**
应用被系统回收时识别会中断，回来后需要重新开始。建议识别时保持应用在前台，或者在系统设置里允许本应用后台运行。

**在家用 WiFi 访问 NAS，走的是局域网还是公网？**
只填外网域名时，取决于路由器是否支持 NAT 回环。建议在 WebDAV / OpenList / Emby 里同时填内网地址，App 会自动选能连上的那个。

**从旧版「AI 字幕播放器」升级？**
直接覆盖安装即可，设置、播放记录和网盘登录都会保留。

**网盘接口会失效吗？**
115、PikPak、阿里云盘 TV 授权等属于非官方接口，可能随服务商调整而失效，也存在账号风控风险，请自行斟酌。

## 从源码构建

需要 JDK 17+ 和 Android SDK（compileSdk 36）。

```bash
git clone https://github.com/clusflah/ai-subtitle-player.git
cd ai-subtitle-player
./gradlew assembleDebug          # 调试版，输出在 app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # 单元测试
```

每个 CPU 架构单独出一个 APK，真机一般装 `app-arm64-v8a-debug.apk`。

**发布**：推送 `v*` 标签，或在 Actions 页面手动运行「Build & Release APK」并勾选 publish，会按 `versionName` 打标签、构建并发布 Release。配置仓库 Secrets（`RELEASE_KEYSTORE_BASE64`、`RELEASE_KEYSTORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`）后使用固定证书签名，保证可以覆盖升级。

## 代码结构

源码在 `app/src/main/java/com/aisub/player/`：

| 路径 | 作用 |
|---|---|
| `MainViewModel.kt` | 串起播放器、识别、翻译、朗读 |
| `Settings.kt`、`AsrSettings.kt` | 设置项与持久化 |
| `asr/` | 音轨解码（16kHz 单声道）、本地识别（VAD + sherpa-onnx）、模型下载、`cloud/` 云端识别 |
| `translate/` | 翻译接口、Claude 与 OpenAI 兼容实现、提示词、模型列表、语言判断 |
| `tts/` | Azure / 系统 TTS、跟随字幕朗读、大模型角色识别 |
| `subtitle/` | 字幕数据、缓存、SRT/VTT/ASS 解析与导出、在线字幕、拼音 |
| `remote/` | WebDAV、OpenList、内外网双地址、`cloud/` 各网盘与媒体服务器 |
| `meta/` | 文件名解析、NFO、TMDB / 豆瓣 / Bangumi / TheTVDB / Fanart.tv |
| `media/` | 媒体库、播放列表与历史、下载、VLC 内核、本机中转 |
| `ui/` | Jetpack Compose 界面 |

## 已知限制

- 识别在应用进程内运行，应用被系统杀掉时会中断（后续可改成前台服务）
- 识别从头顺序进行，拖到还没识别到的位置时暂时没有字幕
- 角色识别只看台词文字，不分析声音
- API Key 以明文存在应用私有存储里

## 致谢

- 播放：[Media3 ExoPlayer](https://github.com/androidx/media)、[libVLC](https://code.videolan.org/videolan/vlc-android)
- 识别：[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)、SenseVoice、Silero VAD 等开源模型
- 网盘接入参考并移植自 [OpenList](https://github.com/OpenListTeam/OpenList)（AGPL-3.0）、[OpenList-APIPages](https://github.com/OpenListTeam/OpenList-APIPages)（AGPL-3.0）与 [115driver](https://github.com/SheltonZhu/115driver)
- 影视信息来源参考 [MediaOrganizer](https://github.com/clusflah/MediaOrganizer)
- Azure 朗读的做法参考 [fcitx5-android-mod](https://github.com/clusflah/fcitx5-android-mod)

## 许可证

本项目以 [GNU Affero General Public License v3.0](LICENSE)（AGPL-3.0）发布。分发修改版（包括 APK）时，需要以同样的许可证公开对应的完整源码。
