## 下载哪个？

| 文件 | 适用设备 |
|---|---|
| `AiSubPlayer-*-arm64-v8a.apk` | **绝大多数安卓手机（推荐）** |
| `AiSubPlayer-*-armeabi-v7a.apk` | 很老的 32 位手机 |
| `AiSubPlayer-*-x86_64.apk` | 模拟器 |
| `AiSubPlayer-*-universal.apk` | 不确定时用这个（包含所有架构，体积最大） |

## 首次使用

1. 安装时需要允许"安装未知来源应用"。
2. 识别引擎可选（设置 → 语音识别）：
   - **本地模型**：免费离线，可选 8 种模型，首次使用时下载（来自 GitHub），国内网络慢可在设置里换成镜像地址。
   - **云端**：Azure、火山豆包、阿里 Fun-ASR、讯飞、ElevenLabs、Deepgram、OpenAI，填对应的 Key 即可。
3. 网盘直连：在「浏览 → 网盘」里登录——115 和阿里云盘用手机 App 扫码；百度网盘用自己申请的 AppKey 扫码，或粘贴 refresh_token；PikPak 用账号密码（可能需要人机验证）。也可以添加 WebDAV、OpenList。
4. 翻译可以先选 **Index-Translate**，免费、无需 API Key；也支持 Claude、DeepSeek、Gemini 和自定义 OpenAI 兼容接口。

## 本版更新

- 在线下载字幕：播放页「字幕/音轨 → 在线下载字幕」，支持射手网（中文字幕多）和 OpenSubtitles（外语片多），下载后直接替换当前字幕。需要在设置的「在线字幕」里填免费申请的 Token / API Key
- OpenSubtitles 对本地视频附带文件哈希，能找到时间轴完全匹配的版本
- 字幕文件新增 ASS/SSA 格式（含中英双语），支持 zip 压缩包、UTF-16 编码
- 文件管理：新增「复制到…」（带进度）；文件夹「⋮」菜单可全部播放、重命名、移动、删除、分享；文件夹里也能长按多选
- 列表显示文件大小，可按大小排序
