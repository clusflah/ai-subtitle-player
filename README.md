# 译幕 Voxtitle（Android）

本地语音识别生成字幕 + LLM 翻译的安卓视频播放器。

- 界面：仿 VLC，底部五个标签——视频 / 音频 / 浏览 / 播放列表 / 更多（设置、串流、历史）
- 媒体来源：手机媒体库、系统文件选择器、网络串流、WebDAV、OpenList，以及直连网盘：115（扫码）、阿里云盘（扫码）、百度网盘（自有 AppKey 扫码或 refresh_token）、PikPak（账号密码）
- 播放：Media3 ExoPlayer，支持播放队列、记住播放进度
- 识别：
  - 本地离线（sherpa-onnx，按需下载）：SenseVoice、Paraformer 中文（配标点模型）、FireRedASR2、Qwen3-ASR 0.6B、Whisper base、Parakeet TDT v3、Moonshine、Dolphin
  - 云端：Azure、火山引擎豆包语音、阿里云 Fun-ASR、讯飞录音文件转写、ElevenLabs Scribe、Deepgram、OpenAI Whisper（及兼容接口）
  - 字幕按逐字/逐词时间戳切成短句，逐句显示
- 翻译：Claude（官方 anthropic-java SDK）、DeepSeek、Gemini、Index-Translate（B 站开源翻译模型，公共接口免费）或任意 OpenAI 兼容接口（通义千问、Kimi、Ollama 等）

## 构建

```bash
./gradlew assembleDebug
```

输出在 `app/build/outputs/apk/debug/`，真机一般装 `app-arm64-v8a-debug.apk`。

## 使用

1. 在「视频」「音频」「浏览」里选择要播放的文件（也可以在文件管理器里用本应用打开）。
   网盘：在「浏览 → 网盘」里点对应网盘登录（115、阿里云盘用手机 App 扫码）。
   也支持 Emby / Jellyfin、Plex 媒体服务器，以及 OneDrive、Dropbox、Google Drive（这三个需要在各自开发者平台免费创建应用，应用内有步骤说明）。
2. 「设置」里填翻译服务的 API Key，选择视频语言（默认自动识别）。
3. 「生成字幕」：使用本地模型时首次会下载所选模型，之后边识别边显示字幕，开启自动翻译时同步翻译。
4. 点字幕列表可跳转；「导出」可保存双语 / 译文 / 原文 SRT。
5. 已有字幕：播放页「字幕/音轨」里可选择本地 SRT / VTT / ASS 文件，或在线下载（需在设置里填射手网 Token 或 OpenSubtitles API Key，均可免费申请）。
6. 文件管理：文件的「⋮」菜单可重命名、移动、复制、删除、分享；文件夹的「⋮」菜单可重命名、整体移动或删除；长按进入多选。
   网络位置和网盘：文件和文件夹的「⋮」菜单可重命名、删除（网盘移到回收站），右上角「⋮」可新建文件夹。
7. ISO 光盘镜像（DVD / 蓝光）自动使用 VLC 内核播放，直接进入正片；普通视频播放失败时可在错误提示里「换用 VLC 内核播放」。
8. 下载：网络位置和网盘里文件或文件夹的「⋮」菜单选「下载」，保存到 Download/Voxtitle（文件夹保留目录结构），进度见通知栏。
9. 文件详情：网络位置和网盘里点视频进入详情页，显示 TMDB 简介、演职员和豆瓣评分（TMDB 需在「设置 → 影视信息」填 API Key；国内可填反代地址），识别错了点「重新匹配」；本地视频在「⋮」菜单里选「影视信息」。
   优先读取视频旁边的刮削文件（Kodi / Emby / Jellyfin / tinyMediaManager 格式）：`视频名.nfo`、`movie.nfo`、`tvshow.nfo`，`poster.jpg`、`folder.jpg`、`视频名-poster.jpg`，`fanart.jpg`、`视频名-fanart.jpg`，以及文件夹名里的 `{tmdb-123}`、`[imdbid-tt123]`；本地缺简介或图片时才联网补全，NFO 里有豆瓣评分时不再联网查询。
   在线来源参考 [MediaOrganizer](https://github.com/clusflah/MediaOrganizer)：TMDB、豆瓣（不用 Key，简介、海报、演职员、评分）、Bangumi（动画，不用 Key）、TheTVDB（分集信息，需 Key）、Fanart.tv（标题图、背景图，需 Key）。
   「设置 → 影视信息」里选主数据源，其余开启的来源按 TMDB → 豆瓣 → TheTVDB 的顺序补全缺的字段；动画另查 Bangumi 评分。
   图片点开可滑动浏览、缩放；文本文件在详情页预览。Emby / Jellyfin、Plex 默认以海报墙显示，右上角可切换列表。

字幕结果按视频缓存在应用私有目录，再次打开同一视频会直接加载。

## 代码结构

| 路径 | 作用 |
|---|---|
| `asr/AudioDecoder.kt` | MediaExtractor + MediaCodec 解码音轨，重采样到 16kHz 单声道 |
| `asr/Transcriber.kt` | 本地引擎：VAD 切段 → sherpa-onnx 识别 →（补标点）→ 字幕条目 |
| `asr/ModelCatalog.kt` | 本地模型目录（下载包、文件名、配置） |
| `asr/cloud/` | 云端识别：公共切块上传基类 + 各家实现 |
| `asr/ModelManager.kt` | 首次使用时下载并解压模型 |
| `translate/` | 翻译接口、Claude 实现、OpenAI 兼容实现、提示词 |
| `subtitle/` | 字幕数据结构、缓存、SRT 导出、SRT/VTT/ASS 解析、在线字幕 |
| `meta/` | 文件名解析、本地 NFO / 海报读取、TMDB / 豆瓣 / Bangumi / TheTVDB / Fanart.tv，`MetaPipeline` 串起查询顺序 |
| `MainViewModel.kt` | 串起识别、翻译、播放器 |
| `ui/` | Compose 界面 |

## 已知限制

- 识别在 ViewModel 里运行，应用被系统杀掉时会中断（后续可改成前台服务）。
- 从头顺序识别；拖到还没识别到的位置时暂时没有字幕。
- API Key 以明文存在应用私有的 SharedPreferences 里。

## 网盘接口说明

115、阿里云盘、百度网盘、PikPak 的接入参考并移植自
[OpenList](https://github.com/OpenListTeam/OpenList)（AGPL-3.0）、
[OpenList-APIPages](https://github.com/OpenListTeam/OpenList-APIPages)（AGPL-3.0）与
[115driver](https://github.com/SheltonZhu/115driver) 的实现。
其中 115、PikPak、阿里云盘 TV 授权属于非官方接口，可能随时失效，也存在账号风控风险。
若公开分发本项目，请遵守上述项目的开源许可（AGPL-3.0 要求公开对应源码）。
