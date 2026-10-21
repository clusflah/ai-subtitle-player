## 下载哪个？

| 文件 | 适用设备 |
|---|---|
| `AiSubPlayer-*-arm64-v8a.apk` | **绝大多数安卓手机（推荐）** |
| `AiSubPlayer-*-armeabi-v7a.apk` | 很老的 32 位手机 |
| `AiSubPlayer-*-x86_64.apk` | 模拟器 |
| `AiSubPlayer-*-universal.apk` | 不确定时用这个（包含所有架构，体积最大） |

## 首次使用

1. 安装时需要允许"安装未知来源应用"。
2. 识别引擎可选：
   - **本地 SenseVoice**：免费离线，第一次「生成字幕」会下载约 160MB 的识别模型（来自 GitHub），国内网络慢可在设置里换成镜像地址。
   - **Azure 语音**：在设置里填 Speech 资源的 Key 和区域即可，无需下载模型。
3. 网盘（115 / 百度网盘 / 阿里云盘 / PikPak）通过 [OpenList](https://github.com/OpenListTeam/OpenList) 访问：先在 OpenList 后台添加网盘存储，再在「浏览 → 网盘」里填 OpenList 的地址和账号。WebDAV 可以直接添加。
4. 翻译可以先选 **Index-Translate**，免费、无需 API Key；也支持 Claude、DeepSeek、Gemini 和自定义 OpenAI 兼容接口。
