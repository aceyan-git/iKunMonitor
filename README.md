# iKunMonitor

[English](#english) | [中文](#中文)

---

<a id="english"></a>

## English

A lightweight Android performance monitor with a desktop activator for advanced ADB-based metrics (FPS, CPU, Memory, Network, etc.).

### Features

- **On-device overlay** — Real-time FPS, CPU, memory, and network stats displayed as a floating window
- **Minimized mode** — Collapse the overlay to a compact bar while monitoring continues
- **Desktop Activator** — Unlock ADB-level metrics (frame timing, per-core CPU frequency, etc.) via USB connection
- **Recording & Reports** — Record sessions and review performance data with interactive charts
- **Cross-platform** — Activator available for both macOS and Windows

### Getting Started

#### Step 1: Install the Android App

1. Download **iKunMonitor.apk** from the [Software](./Software) directory (or from the [Releases](https://github.com/aceyan-git/iKunMonitor/releases) page)
2. Install the APK on your Android device
3. **Grant overlay permission** — The app will prompt you to enable "Display over other apps" on first launch. This is required for the floating monitor window

#### Step 2: Download the Desktop Activator (Optional — for ADB metrics)

If you need advanced ADB-based metrics such as real-time FPS, download the **iKunMonitor Activator** for your platform:

| Platform | Download |
|----------|----------|
| macOS    | `iKunMonitorActivator-Mac.zip` |
| Windows  | `iKunMonitorActivator-Win.zip` |

> Download from [Releases](https://github.com/aceyan-git/iKunMonitor/releases) or [Actions Artifacts](https://github.com/aceyan-git/iKunMonitor/actions)

##### macOS: Trust the App

Since the Activator is not signed with an Apple Developer certificate, macOS will block it on first launch:

1. Double-click to open — a security dialog will appear
2. Click **"Done"** (do **not** click "Move to Trash")
3. Open **System Settings → Privacy & Security**
4. Scroll down to the **Security** section — you'll see:
   > *"iKunMonitor Activator was blocked from use because it is not from an identified developer."*
5. Click **"Open Anyway"**
6. A confirmation dialog will appear — click **"Open"**

After this one-time setup, the app will open normally in the future.

#### Step 3: Enable USB Debugging on Your Phone

1. Go to **Settings → About Phone** and tap **Build Number** 7 times to enable Developer Options
2. Go to **Settings → Developer Options** and enable **USB Debugging**
3. When connecting to the computer, tap **"Allow"** on the USB debugging authorization prompt

#### Step 4: Connect and Activate

1. Connect your phone to the computer via USB cable
2. Launch **iKunMonitor Activator** on your computer
3. The Activator will automatically detect your device
4. Click **"One-Click Activate"** to start
5. **Keep the USB cable connected** throughout the monitoring session — disconnecting will interrupt ADB-based metrics

### Demo

> Video tutorials coming soon

| Demo | Description |
|------|-------------|
| 📱 Mobile | How to use the on-device floating monitor |
| 💻 Desktop | How to use the Activator to enable ADB metrics |

### Project Structure

```
├── iKunMonitor/              # Android app source (Kotlin/Jetpack Compose)
├── iKunMonitorActivator/     # Desktop activator (Python/Tkinter)
├── adb/                      # Platform-tools for CI packaging
├── Software/                 # Pre-built releases (APK, etc.)
└── .github/workflows/        # CI: auto-build Win/Mac packages
```

### Contributing

This is an open-source project and contributions are welcome!

- **Found a bug?** — [Open an Issue](https://github.com/aceyan-git/iKunMonitor/issues/new)
- **Have an idea?** — Feel free to submit a feature request via Issues
- **Want to contribute code?** — Fork the repo, make your changes, and submit a Pull Request

All feedback, bug reports, and suggestions are appreciated. Let's make performance monitoring easier together. 🛠️

---

<a id="中文"></a>

## 中文

一款轻量级 Android 性能监控工具，配合桌面端激活器可获取 ADB 级别的高级指标（FPS、CPU、内存、网络等）。

### 功能特性

- **设备端悬浮窗** — 实时显示 FPS、CPU、内存、网络等性能数据
- **最小化模式** — 可收缩为迷你悬浮条，监控不中断
- **桌面端激活器** — 通过 USB 连接解锁 ADB 级别指标（帧时序、每核 CPU 频率等）
- **录制与报告** — 录制监控会话，通过交互式图表回顾性能数据
- **跨平台** — 激活器支持 macOS 和 Windows

### 使用指南

#### 第一步：安装 Android 应用

1. 从 [Software](./Software) 目录（或 [Releases](https://github.com/aceyan-git/iKunMonitor/releases) 页面）下载 **iKunMonitor.apk**
2. 在 Android 设备上安装 APK
3. **授权悬浮窗权限** — 首次启动时，应用会引导你开启「显示在其他应用上层」权限，这是悬浮窗监控的必要条件

#### 第二步：下载桌面端激活器（可选 — 用于 ADB 指标）

如需获取实时 FPS 等 ADB 级别的高级指标，请根据平台下载 **iKunMonitor Activator**：

| 平台    | 下载文件 |
|---------|----------|
| macOS   | `iKunMonitorActivator-Mac.zip` |
| Windows | `iKunMonitorActivator-Win.zip` |

> 下载地址：[Releases](https://github.com/aceyan-git/iKunMonitor/releases) 或 [Actions Artifacts](https://github.com/aceyan-git/iKunMonitor/actions)

##### macOS：信任应用

由于激活器未经 Apple 开发者签名，macOS 会在首次打开时阻止运行：

1. 双击打开 — 弹出安全提示
2. 点击 **「完成」**（**不要**点「移到废纸篓」）
3. 打开 **系统设置 → 隐私与安全性**
4. 往下滚动到「安全性」区域，会看到类似提示：
   > *"已阻止打开 iKunMonitor Activator，因为它不是来自可识别的开发者。"*
5. 点击 **「仍要打开」**
6. 再次弹出确认对话框 — 点击 **「打开」**

完成以上一次性设置后，之后可正常打开。

#### 第三步：开启手机 USB 调试

1. 进入 **设置 → 关于手机**，连续点击 **版本号** 7 次，开启开发者选项
2. 进入 **设置 → 开发者选项**，开启 **USB 调试**
3. 连接电脑时，在手机弹出的 USB 调试授权对话框中点击 **「允许」**

#### 第四步：连接并激活

1. 通过 USB 数据线将手机连接到电脑
2. 在电脑上打开 **iKunMonitor Activator**
3. 激活器会自动识别已连接的设备
4. 点击 **「一键激活」** 即可开始使用
5. **使用过程中请保持 USB 连接** — 拔掉数据线会中断 ADB 指标采集

### 演示

> 视频教程即将上线

| 演示 | 说明 |
|------|------|
| 📱 手机端 | 设备端悬浮窗监控的使用方法 |
| 💻 电脑端 | 桌面激活器的使用方法 |

### 项目结构

```
├── iKunMonitor/              # Android 应用源码（Kotlin/Jetpack Compose）
├── iKunMonitorActivator/     # 桌面端激活器（Python/Tkinter）
├── adb/                      # ADB 平台工具（CI 打包用）
├── Software/                 # 预编译发布包（APK 等）
└── .github/workflows/        # CI：自动构建 Win/Mac 安装包
```

### 参与贡献

本项目完全开源，欢迎参与！

- **发现 Bug？** — [提交 Issue](https://github.com/aceyan-git/iKunMonitor/issues/new)
- **有新想法？** — 欢迎通过 Issue 提交功能建议
- **想贡献代码？** — Fork 仓库，修改后提交 Pull Request

任何反馈、Bug 报告和建议都非常欢迎，让我们一起把性能监控做得更好。🛠️

---

## License

This project is open-source. See [LICENSE](./LICENSE) for details.
