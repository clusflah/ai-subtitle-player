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

- 新增 Emby / Jellyfin 媒体服务器：服务器地址 + 账号密码登录，按媒体库、剧集、季浏览，播放原始文件
- 新增 Plex：用 plex.tv 账号在浏览器里授权，自动找到服务器（局域网地址优先）；也可以手动填服务器地址和令牌
- 新增 OneDrive、Dropbox、Google Drive：可浏览、播放、下载、重命名、删除、新建文件夹。这三家要求用自己创建的免费应用登录，添加时有逐步说明
- 新增下载：网络位置和网盘里的文件或整个文件夹都能下载到 Download/AiSubPlayer，文件夹保留目录结构，系统通知栏显示进度；文件夹下载前会显示文件数和总大小
