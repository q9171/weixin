# 威信 (RoomChat)

一个**轻量级安卓聊天 App**：输入一个「频道号」就能进房聊天，无需注册、无需自建服务器。界面由 WebView 承载，消息走公共 MQTT 服务，开箱即用。

> 适合：两人私密聊天、小群聊、临时讨论间。频道号即房间标识。

---

## ✨ 功能特性

- 🚪 **频道号进房**：输入任意频道号即可加入对应房间，无需账号
- 🏷️ **昵称**：进房时填个昵称即可
- 💬 **本地聊天记录**：每频道最多保留 500 条，退出再进不丢
- 🎨 **聊天背景换色**：浅红 / 浅蓝 / 浅紫 随心切换（设置里改）
- 🔔 **未读红点 + 多频道计数**：后台实时监听所有频道，列表显示「N 条未读」
- 📝 **长按消息菜单**：复制 / 撤回（仅自己消息，对方同步显示「撤回了一条消息」）/ 引用 / 删除（仅本地）
- 💭 **引用回复**：发送时携带被引用的原消息，气泡内显示引用片段
- 🔍 **消息搜索**：聊天页点 🔍 按关键词过滤历史并高亮
- 📝 **草稿自动保存**：输入框内容随输入留存，再次进入频道自动恢复
- 🗂️ **时间分组 + 已读分隔**：按今天/昨天/日期分隔，并显示对方已读位置
- 🔄 **应用内自动更新**：启动后自动检查新版，点一下跳浏览器下载安装

---

## 🏗️ 技术架构

```
安卓原生壳 (MainActivity / WebView)
        │
        ▼
 前端界面 (assets/index.html，纯原生 JS)
        │  MQTT over WebSocket
        ▼
  公共 MQTT Broker (broker.emqx.io)
        │  pub/sub 主题: roomchat/<频道号>
        ▼
   同频道的其他客户端
```

- **无后端**：不依赖任何自建服务器，消息通过公共 broker 的发布/订阅（pub/sub）中转
- **频道即主题**：`roomchat/<频道号>` 既是房间也是 MQTT 主题，天然支持多人
- **轻量构建**：APK 用手工脚本链构建（`aapt2 → javac → d8 → inject_dex → zipalign → apksigner`），不依赖 Android Studio

---

## 📂 目录结构

```
.
├── app/                      # 安卓原生工程（壳 + WebView + Java 逻辑）
│   └── src/main/
│       ├── assets/index.html # 前端界面（核心聊天逻辑都在这里）
│       ├── java/.../MainActivity.java
│       └── res/              # 图标、样式等资源
├── web/index.html            # 与 assets 同步的网页版（便于桌面预览）
├── build_apk.sh             # 构建 APK 脚本
├── publish.sh                # 一键发版（构建 + 更新 version.json + 上传 + 建标签）
├── inject_dex.py            # 注入 dex 的小工具
├── version.json             # 更新清单（versionCode / versionName / apkUrl / note）
└── wei-<版本>.apk            # 各版本成品（发布产物，不入库）
```

---

## 🛠️ 构建与发版

需要本地装有 Android SDK（`aapt2` / `javac` / `d8` / `zipalign` / `apksigner`）。

```bash
# 1) 构建 APK（默认 versionCode=1 versionName=1.0.0）
bash build_apk.sh

# 2) 一键发版（构建 + 更新 version.json + 上传 + 建 Git 标签）
bash publish.sh
# 大版本发版示例：
VC=25 VN=1.2.4 bash publish.sh
```

> 发版脚本通过 GitHub API 上传，需要把仓库 `remote.origin.url` 配成
> `https://<你的PAT>@github.com/Q9171/weixin.git`，或设置环境变量 `GITHUB_TOKEN`。

---

## 📲 下载与安装

- **最新版直链（jsDelivR CDN）**：
  `https://gcore.jsdelivr.net/gh/Q9171/weixin@v1.2.3/wei-1.2.3.apk`
- 已装旧版：打开 App 会自动弹出「发现新版本」，点立即更新即可
- 安装时若提示「未知来源」，按系统提示允许本次安装即可

---

## 🔒 隐私说明（请先读完）

当前版本消息通过**公共 MQTT broker（`broker.emqx.io`）**明文中转，因此：

- ⚠️ **任何知道你频道号的人都可能订阅到该房间的消息**（频道号相当于房间钥匙）
- ⚠️ **消息目前未做端到端加密**，broker 服务端理论上可见明文

如果你用于敏感聊天，请务必使用**足够随机、不易猜测的频道号**。端到端加密已在规划中（参考 Signal / SimpleX 方案），完成后即使 broker 也无法读取内容。

---

## 📄 开源协议

本项目以 **MIT 协议** 开源，详见 [LICENSE](LICENSE)。

---

## ⚠️ 免责声明

本 App 为个人开源项目，按「现状」提供，不对使用产生的任何后果负责。请勿用于违法违规用途。
