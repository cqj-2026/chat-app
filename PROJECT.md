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
