译幕 Voxtitle 是开源的安卓视频播放器：用语音识别给视频生成字幕，再用大模型翻译成双语字幕。这是改名后的首个公开正式版。

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

## 本版更新（1.0.0）

- **改名「译幕 Voxtitle」**，并有了自己的应用图标（支持 Android 13 主题图标）。从旧版「AI 字幕播放器」可以直接覆盖安装，设置和记录都会保留；新下载的文件保存到 Download/Voxtitle
- **中文译文加拼音**：设置 → 字幕样式打开后，译文每个字上方标注带声调的拼音（Android 10 及以上）
- **翻译模型下拉选择**：设置里点「模型」右侧的箭头，从服务商获取可用模型列表
- **内网 + 外网双地址**：WebDAV、OpenList、Emby / Jellyfin 可以额外填一个内网地址，在家自动走局域网，出门走外网
- 项目以 AGPL-3.0 许可证开源，源码：https://github.com/clusflah/ai-subtitle-player
