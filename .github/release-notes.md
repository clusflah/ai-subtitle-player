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

- 支持 ISO 光盘镜像播放（DVD、蓝光）：自动改用 VLC 内核，跳过光盘菜单直接播放正片；本地、WebDAV、OpenList、网盘里的 ISO 都可以
- 普通视频播放失败时，错误提示里可以「换用 VLC 内核播放」，能播 ExoPlayer 不支持的编码（如 MPEG-2、VC-1、RMVB 等）
- 新增「内嵌字幕」：MKV、蓝光、DVD 自带的字幕轨可以在「字幕/音轨」面板里选择显示
- 文件和文件夹名过长时换行显示（最多 3 行），不再截断
- 注意：加入 VLC 内核后安装包变大约 20 MB；ISO 暂不支持 AI 识别字幕（可用在线下载或选择字幕文件），加密的商业蓝光无法播放
