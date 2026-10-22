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

- 修复：豆瓣的海报加载不出来（豆瓣图片有防盗链）
- 海报墙里服务器没有海报、或海报加载失败的剧集和电影，会自动到 TMDB / 豆瓣找一张
- 图片加载失败后过一会儿会重试，网络临时不好时回到页面就能补上
- 上一版（0.12.0）新增了豆瓣详情、Bangumi、TheTVDB、Fanart.tv 元数据源，修复了搜索框和长文件名显示不全
