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

- Emby / Jellyfin、Plex 显示海报墙：海报、年份、评分、集数 / 未看集数角标，右上角可切换回列表
- 新增文件详情页：点视频进入详情，显示 TMDB 简介、海报、背景图、类型、片长、导演主演、单集信息，以及豆瓣评分和 TMDB 评分；识别错了可以「重新匹配」。TMDB 需要在「设置 → 影视信息」填写免费申请的 API Key，豆瓣评分不需要
- 网络位置和网盘里现在显示所有文件：图片显示缩略图，点开可左右滑动、双指缩放、双击放大；文本文件（txt、nfo、srt 等）可在详情页预览内容
- 本地视频的「⋮」菜单新增「影视信息」
