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

- 新增影视信息来源（参考 MediaOrganizer）：
  - **豆瓣**：除评分外还有中文简介、海报、类型、导演演员，不用 Key——没填 TMDB Key 也能看到简介和海报
  - **Bangumi 番组计划**：动画的评分、简介和分集标题，不用 Key
  - **TheTVDB**：剧集的中文分集标题和简介，需要 API Key
  - **Fanart.tv**：透明标题图和高清背景图，需要 API Key
- 「设置 → 影视信息」可以选主数据源（TMDB / 豆瓣 / Bangumi / TheTVDB），其他开启的来源自动补全缺少的内容；详情页同时显示豆瓣、Bangumi、TMDB 等多个评分，菜单里可以跳到各站点的页面
- 修复首页搜索框提示文字换行后显示不全，输入的长文字改为单行横向滚动
- 文件夹和文件列表显示完整的名字，不再截断成三行
