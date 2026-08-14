# carlink-scrcpy

Genymobile/scrcpy **server 端**的车机互联魔改版：手机端投屏采集 / 编码 / 触控注入库，以 `java_library` 形式编入 Android 系统源码树（AOSP/LineageOS），由平台签名的 privapp「互联服务」在其进程内使用。

- 上游基线：scrcpy **v4.1**（commit `2926c06c5dc3064ae6d8db706f1a98a37cfcf3f0`）
- 上游仓库：<https://github.com/Genymobile/scrcpy>
- License：Apache-2.0（保留上游 `LICENSE` 原文）
- 包名保持 `com.genymobile.scrcpy` 不变，便于与上游 diff / rebase

## 在整体方案中的角色

本库运行在自编译 LineageOS（Android 16）ROM 的平台签名 privapp「互联服务」进程内。手机端按车机屏幕参数动态创建 VirtualDisplay，本库负责采集该虚拟屏画面，经 MediaCodec 硬件编码后通过 TCP 发送到 Android 车机端解码渲染；同时通过控制通道接收车机端的触控事件，注入到该虚拟屏，实现车机反控手机。

## 目录结构

```
├── Android.bp   # Soong 模块定义：java_library "carlink_scrcpy"
├── LICENSE      # 上游 Apache-2.0 许可证（原样保留）
├── docs/        # 上游协议相关文档（车机端开发需参考其中的 wire 协议）
│   ├── control.md           # 控制通道协议（触控/按键注入、剪贴板等消息格式）
│   ├── video.md             # 视频流协议（packet/frame/session meta 格式）
│   ├── virtual-display.md   # 虚拟屏（--new-display）行为说明
│   ├── develop.md           # server 端运行/调试方式
│   └── device.md            # 设备端行为说明
└── src/com/genymobile/scrcpy/   # server 端 Java 源码（裁剪后）
```

## 裁剪清单

相对上游 server 端，已移除以下内容：

- **PC 客户端（`app/`，C/meson/gradle）**：整个客户端与本项目无关，车机端解码渲染由车机侧自行实现。
- **`audio/` 整个目录**（音频采集/编码/转发）：本项目只做画面投屏与触控注入，不涉及音频。
- **摄像头采集**（`video/CameraCapture.java`、`CameraAspectRatio.java`、`CameraFacing.java`，及 `Options` 中 `camera_*` 配置、`VideoSource.CAMERA`、`Controller` 摄像头控制分支）：投屏画面来源只有物理屏 / VirtualDisplay。
- **UHID 物理键鼠模拟**（`control/UhidManager.java`，及 `TYPE_UHID_*` 控制消息、`TYPE_UHID_OUTPUT` 设备消息的解析/构造/处理）：车机端只需注入触控事件，无需模拟物理 HID 设备。
- **`CleanUp.java` 双进程清理机制**：上游通过独立进程恢复设备设置（show_touches / stay_awake / screen_off_timeout / 熄屏状态），与 app 进程模型不兼容，后续按服务生命周期重写。
- **`util/Settings.java`**：仅被 `CleanUp.java` 使用，一并移除（`util/SettingsException.java` 仍被 `wrappers/ContentProvider.java` 使用，保留）。
- **上游 CI / 发布脚本 / 无关文档**：仅保留上述 5 个协议相关文档。

此外，上游由 Gradle 构建时自动生成的 `BuildConfig` 类，在本仓库中以普通源码文件提供（`src/com/genymobile/scrcpy/BuildConfig.java`），因为 Soong 的 `java_library` 不会生成该类。

## 集成方法

1. 将本仓库克隆到 LineageOS 源码树内，建议路径：
   - `vendor/<brand>/carlink/scrcpy`，或
   - `packages/apps/CarLink/scrcpy`
2. 在互联服务模块的 `Android.bp` 中添加静态依赖：

   ```bp
   static_libs: ["carlink_scrcpy"],
   ```

模块以 `sdk_version: "none"` 编译，直接依赖 `framework`，可访问 hidden API（树内编译不受 SDK 限制）。

## 后续魔改清单

当前代码仍为上游进程模型（`main()` 入口 + 命令行参数 + LocalSocket 连接 adb 隧道），在 privapp 进程内运行前还需要以下改造：

1. **进程模型 in-process 化**：`Server.main()` / `System.exit()` / 伪主 Looper（`prepareMainLooper()`）/ `Options.parse()` 命令行解析，改为 Builder 式配置与服务生命周期（start/stop 由互联服务调用）。
2. **删除 `FakeContext` / `Workarounds`**：app 进程已有真实 Context，无需伪造；shell 特权能力（`dropRootPrivileges()` 中 setuid(2000) 获得的权限）改用平台签名权限替代。
3. **`DesktopConnection` 网络层 LocalSocket → TCP**：当前基于 adb 隧道的抽象 socket；注意 `device/Streamer.java` 直接对 `FileDescriptor` 做 `Os.write`，改 TCP 后需改为 `OutputStream` 或 `ParcelFileDescriptor.fromSocket` 方式写入。
4. **与车机端对齐 wire 协议**：视频流格式见 `docs/video.md`，控制消息格式见 `docs/control.md`（注意本仓库已移除 UHID 与摄像头相关消息）。

## 许可与署名

本项目基于 [Genymobile/scrcpy](https://github.com/Genymobile/scrcpy) 修改，上游版权归 Genymobile 及各位贡献者所有。本仓库保留上游 Apache-2.0 许可证全文（见 `LICENSE`），修改后的代码同样以 Apache-2.0 发布。
