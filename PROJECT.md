# Chat App 项目状态

一个自建的 Android AI 聊天客户端。手机写代码 + GitHub Actions 云端构建，
不依赖电脑，不依赖 Termux 作为运行时。

## 目录结构

```
.github/workflows/build.yml     云端构建（push 自动触发）
app/build.gradle.kts            应用模块配置（R8 压缩、debug 签名）
app/proguard-rules.pro          R8 保留规则
app/src/main/AndroidManifest.xml
app/src/main/java/com/example/hello/MainActivity.kt   界面 + 网络
app/src/main/java/com/example/hello/History.kt        对话历史持久化
```

## 构建

push 到 main 即触发，约 2–5 分钟。产物名 `chat-apk`，内含 `app-release.apk`。

本地推送（Termux）：

```bash
cp -r ~/storage/downloads/chat-files/. ~/chat-app/
cd ~/chat-app
git status --short
git add -A && git commit -m "说明" && git push
```

弱网下用重试循环：

```bash
cd ~/chat-app && for i in 1 2 3 4 5 6 7 8; do echo "--- 第 $i 次 ---"; git push && break; sleep 5; done
```

## 签名

CI 用 actions/cache 缓存 `~/.android/debug.keystore`，保证每次签名一致，
因此新版本可以直接覆盖安装（API Key 等设置不会丢）。

指纹（应保持稳定）：

```
SHA1:   90:06:03:DE:37:AA:58:D9:98:90:80:36:06:91:A3:C9:63:A7:7A:2D
SHA256: 4D:43:52:E5:93:85:7C:DC:C0:8A:02:E1:8B:C4:58:CA:BC:A2:AB:94:51:32:F1:55:48:42:28:C9:00:98:A9:09
```

## 体积

| 版本 | APK |
|---|---|
| v0.2 debug 未压缩 | 8.1 MB |
| v0.4 release 压缩 | 1.0 MB |
| v0.5 release 压缩 | 1.0 MB |

关键配置：`isMinifyEnabled = true` + `isShrinkResources = true` + `assembleRelease`。

## 版本历史

| 版本 | 内容 |
|---|---|
| v0.1 | 最小 Compose 应用，验证构建流水线 |
| v0.2 | 聊天界面 + OkHttp 流式对话 + 应用内配置 |
| v0.4 | 开启 R8 压缩，固定签名密钥 |
| v0.5 | 发图片（相册选择 + 压缩 + 多模态消息）、TTS 朗读 |
| v0.6 | 对话历史持久化（未构建） |

## 已知问题

1. **图片需要多模态模型。** DeepSeek 的 `deepseek-flash` / `deepseek-v4-pro`
   都是纯文本，发图片不会被理解。需要换 `qwen-vl-max`（阿里百炼）或
   `glm-4v`（智谱），或做双模型配置。
2. **历史图片每轮重发 base64 会很费流量**，目前只让最后一张图带 base64，
   更早的用 `[图片]` 占位。
3. **TTS 依赖系统语音引擎。** 部分机型需先安装中文 TTS 引擎。
4. **没有清空历史的入口**（v0.6 暂未实现）。
5. **服务端偶发把 `content` 返回 JSON null**，已用 `isNull()` 判掉，
   但其他字段可能还有类似情况。

## 待办

- [ ] 语音输入（录音 + ASR 转文字）
- [ ] 清空历史按钮
- [ ] Markdown 渲染
- [ ] 夜间静默 / 长回答分段朗读

## 模型配置参考

```
DeepSeek（纯文字）
  Base URL: https://api.deepseek.com/v1
  模型:     deepseek-flash 或 deepseek-v4-pro

阿里百炼（图文都支持）
  Base URL: https://dashscope.aliyuncs.com/compatible-mode/v1
  模型:     qwen-vl-max

智谱（图文都支持）
  Base URL: https://open.bigmodel.cn/api/paas/v4
  模型:     glm-4v
```

**不要把 API Key 写进代码或提交到仓库**，只在 App 的设置页里填。

## 离线神经 TTS 调研结论（2026-11）

### 结论：可行，但代价不小

| 项 | 实际情况 |
|---|---|
| 依赖来源 | **不在 Maven Central**，必须从 GitHub Releases 下载 `.aar` 后本地引用 |
| 最新版本 | `v2.0.2` |
| AAR 体积 | `sherpa-onnx-2.0.2.aar` = **52,321,252 字节（约 52 MB）**，含多个 ABI |
| 只打包 arm64-v8a | 预计 APK 增加 12–15 MB |
| 模型 | 需 `.onnx` 中文模型，**几十 MB 量级**，建议运行时下载而非打包 |
| 打包要求 | `aaptOptions { noCompress "onnx" }`，否则模型会被压缩导致加载失败 |

AAR 下载地址：
`https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.2/sherpa-onnx-2.0.2.aar`

参考实现：https://github.com/ybsoftware/AndroidTTS

### 两条路线

**路线 A：换系统 TTS 引擎（零代码、零体积）**

系统 TTS 已验证可用。用户安装一个音质更好的离线 TTS 引擎 App
（如讯飞语记、Google 语音服务），系统 TTS 自动使用它。
本 App 无需任何改动。

**路线 B：内置 sherpa-onnx（完全可控）**

- APK 从 1.0 MB 增至约 13–15 MB
- 首次使用下载模型几十 MB（可断点续传）
- 音质与音色完全可控，可离线、可换模型

### 权衡

当前网络 10–100 KB/s 的情况下，路线 B 的首次成本是
**15 MB APK + 几十 MB 模型**，约 10–30 分钟。
路线 A 成本为零，但受限于手机上已装的引擎质量。

## 模型管理（v0.10）

设置页新增「语音模型管理」，提供两个模型的下载入口：

- 语音识别模型
- 语音合成模型

实现要点（`Models.kt`）：

| 能力 | 说明 |
|---|---|
| 断点续传 | 用 `Range: bytes=N-`，中断后再点继续 |
| 解包 | 支持 `.tar.bz2` / `.tar.gz` / 单个 `.onnx` |
| 依赖 | `org.apache.commons:commons-compress:1.26.2`（Android 无内置 bzip2） |
| 进度回传 | IO 线程 → 主线程 Handler → Compose 状态 |
| 存储位置 | `filesDir/models/{asr,tts}/` |

模型地址做成**可填写字段**，这样：

1. GitHub 下载慢时可以换成镜像地址
2. 官方换了模型名不用改代码
3. 想换音色只换地址

尚未完成：把模型接到 sherpa-onnx 的识别/合成 API 上，
需要先拿到 AAR 的真实类与方法签名（由 CI 的 `javap` 步骤导出）。

## sherpa-onnx 接入（v0.12）

### 构建配置

| 项 | 值 |
|---|---|
| AAR | `libs/sherpa-onnx-2.0.2.aar`（52 MB，CI 里下载并缓存） |
| ABI | 只打 `arm64-v8a`（jni 里该目录 32 MB） |
| 原生库压缩 | `useLegacyPackaging = true` |
| onnx 不压缩 | `androidResources { noCompress += "onnx" }` |
| 混淆 | `-keep class com.k2fsa.sherpa.onnx.** { *; }` |
| **实测 APK** | **13.7 MB**（不压缩会是 33 MB） |

### 代码结构

```
Sherpa.kt
├── SherpaFiles    模型目录里定位 onnx / tokens
├── AudioCapture   AudioRecord 16k 单声道 → FloatArray
├── SherpaAsr      OfflineRecognizer，自动区分 paraformer / zipformer-ctc
├── SherpaTts      OfflineTts（VITS），生成 PCM
└── AudioSink      AudioTrack 播放
```

识别和合成都做了**优雅降级**：模型没下载就自动退回系统引擎。

### 推荐模型地址（填到 App 的「语音模型管理」）

语音识别（48.2 MB，int8，快）
```
https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-zipformer-ctc-small-zh-int8-2025-07-16.tar.bz2
```

语音合成（13.4 MB，女声）
```
https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-zh_CN-xiao_ya-medium-int8.tar.bz2
```

备选：识别用 paraformer-zh-small（74.3 MB，准确率更高）；合成用
vits-icefall-zh-aishell3（30.1 MB）。

### 关键 API（来自 CI 的 javap 实测，勿凭记忆写）

```kotlin
OfflineRecognizer(assetManager, OfflineRecognizerConfig(featConfig, modelConfig, decodingMethod))
  .createStream().acceptWaveform(FloatArray, Int) → decode(stream) → getResult(stream).text

OfflineTts(assetManager, OfflineTtsConfig(model = OfflineTtsModelConfig(vits = ...)))
  .generate(text, speakerId, speed) → GeneratedAudio.samples / .sampleRate
```
