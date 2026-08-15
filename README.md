# carlink-scrcpy

Genymobile/scrcpy **server 端**的车机互联魔改版：手机端投屏采集 / 编码 / 触控注入**库**。以 `android_library` 编入 Android 系统源码树（AOSP/LineageOS），由平台签名 privapp「互联服务」在其进程内加载运行——不再是上游「adb shell + app_process 独立进程」模型。

- 上游基线：scrcpy **v4.1**（commit `2926c06c5dc3064ae6d8db706f1a98a37cfcf3f0`）
- 上游仓库：<https://github.com/Genymobile/scrcpy>
- License：Apache-2.0（保留上游 `LICENSE` 原文）
- 包名保持 `com.genymobile.scrcpy` 不变，便于与上游 diff / rebase

## 在整体方案中的角色

车机（Android）通过 TCP 连接手机 → 手机端互联服务完成握手后调用本库 `CarLinkServer.start()` → 本库按车机屏幕参数创建 VirtualDisplay，采集画面经 MediaCodec 硬件编码，通过视频 TCP 通道发给车机解码渲染；车机触控事件经控制 TCP 通道送达，由本库注入虚拟屏，实现车机反控手机。

## 与上游的差异

### 裁剪清单（相对上游 server 端）

- **PC 客户端（`app/`，C/meson/gradle）**：车机端解码渲染由车机侧自行实现。
- **`audio/` 整个目录**：只做画面投屏与触控注入，不涉及音频。
- **摄像头采集**（`CameraCapture` 及 `Options` 中 `camera_*` 配置等）：画面来源只有 VirtualDisplay（`ScreenCapture` 物理屏镜像路径保留作备用）。
- **UHID 物理键鼠模拟**（`UhidManager` 及 `TYPE_UHID_*` 消息）：不模拟物理 HID 设备，不使用 `/dev/uhid`。
- **`CleanUp.java` 双进程清理机制**与 `util/Settings.java`：独立进程恢复设备设置的机制与 app 进程模型不兼容；会话清理由 `CarLinkServer` 的 stop/finally 链承担。
- **死代码二次清理**（随健壮性改造）：整文件删除 `wrappers/ContentProvider`（上游 Settings 的 ContentProvider 包装）、`util/SettingsException`、`util/HandlerExecutor`、`video/VideoSource`；并清理 `Options`、`util/LogUtils`、`wrappers/ActivityManager`、`control/`、`model/` 等中仅服务已裁特性的字段与方法（如 `Options` 的 `scid`/`tunnel_forward`/`cleanup`/`list_*`/`send_device_meta` 等 key 已不存在），合计净删约 500 行。
- **上游 CI / 发布脚本 / 无关文档**：仅保留 5 篇上游用户/开发参考文档（适用性见 `docs/carlink-protocol.md` 开头说明）。

### 本次魔改清单（进程模型与网络层）

1. **入口 in-process 化**：删除 `Server.java`（`main()`/`System.exit()`/反射改写 `sMainLooper` 的伪主 Looper/`dropRootPrivileges()`），新增 **`CarLinkServer`** 公开 API（Builder 配置 + start/stop/Listener 回调，见下文）。
2. **删除 `FakeContext` / `Workarounds`**：app 进程有真实 Context，新增 `util/AppContext`（由 `CarLinkServer.start()` 用 application Context 初始化），全部内部调用点改为 `AppContext.get()`；原 `FakeContext.PACKAGE_NAME`（`com.android.shell`）调用点改为真实包名。
3. **网络层 LocalSocket → TCP**：删除 `device/DesktopConnection.java`（adb 隧道 abstract socket），新增 `device/CarLinkConnection.java`——视频通道为本库 `ServerSocket` accept 的 TCP 连接（经 `ParcelFileDescriptor.fromSocket()` 保住 `Streamer` 的 `Os.write` 路径），控制通道为调用方握手完成后移交的已连接 TCP socket；`ControlChannel` 改为接收 `InputStream`/`OutputStream`。
4. **wire 协议精简**：不发送 64 字节设备名 meta、无 dummy byte、无 session meta 包；视频流首 4 字节即 codec id（大端 `"h264"`/`"h265"`），随后为「12 字节头 + 裸 Annex-B」packet 序列。详见 `docs/carlink-protocol.md`。
5. **`util/Ln` 精简**：只保留 logcat 输出（TAG `scrcpy`），删除控制台/文件描述符输出与 `disableSystemStreams()`。
6. **`Options` 新增 `i_frame_interval` key**（上游硬编码 10s），供 `Config.iFrameIntervalSec` 透传。
7. **虚拟屏事件导出**：`NewDisplayCapture` 的 `VirtualDisplayListener` 通知在会话编排层同时转发给 `CarLinkServer.Listener.onVirtualDisplayReady(displayId)`。

### 健壮性改造（in-process 托管）

上游 server 运行在独立进程，线程漏出 `Error`、join 卡死最多杀死自己；in-process 托管后这些都直接威胁宿主互联服务进程，因此做了以下改造（均已真机验证）：

1. **全链路 Throwable 兜底**：所有自建线程（会话/video/control-recv/control-send）捕获 `Throwable`，任何 `Error`（如虚拟屏创建失败的 `AssertionError`）不会逃出线程杀死宿主进程；processor 异常终止的首个错误经 `AsyncProcessor.onTerminated(fatalError, cause)` 传递，由会话线程通过 `Listener.onError()` 上报（随后 `onStopped()`）。
2. **单消息容错**：`Controller` 应用单条控制消息时的 `RuntimeException`（权限被收回、事件被系统拒绝等）只记日志、会话继续；已定义但不支持的 CAMERA_* / RESIZE_DISPLAY 消息不会落到上游的 `AssertionError`（后者由非 flex 虚拟屏抛 `IllegalStateException`，同样被容错捕获）。
3. **有界 join**：会话拆除链上所有 `join()`（`Controller`/`DeviceMessageSender`/`SurfaceEncoder`/`OpenGLRunner`）均有 2s 上限，卡死的线程不再永久阻塞清理；`stop()` 中断会话线程也不再跳过后续 join 与 GL 线程关停。
4. **视频 accept 看门狗**：`accept()` 以 2s 轮询（`VIDEO_ACCEPT_POLL_MS`），等待车机视频连接期间检测到控制通道已断开（`isControlSocketDead()`）即结束会话，不留孤儿会话空转；另有 30s 总体超时（`VIDEO_ACCEPT_TIMEOUT_MS`）兜底——车机断电/拔线造成的半开死控制连接（收不到 FIN）轮询探测不到，超时即到点结束会话并释放库实例，后续连接不会被永久 busy 拒绝。
5. **协议长度上限**：`ControlMessageReader` 对带长度前缀的字段在分配缓冲前拒绝超过 256 KiB（`MESSAGE_MAX_SIZE`）的长度——上游的 4 字节长度字段可被诱导分配至多 4GB。
6. **资源清理补全**：stop() 注销系统剪贴板 autosync 监听（否则每会话泄漏一个 listener 并回调进死会话）、`shutdownNow()` 关闭 `startAppExecutor`；视频连接建立前中止的路径同样关闭调用方移交的控制 socket；`CarLinkConnection` 建立失败不泄漏 dup 出的 fd；`SurfaceEncoder` 启动阶段失败即释放 codec/capture；`OpenGLRunner` 关停后复位静态线程引用（重开会话拿到新线程）。
7. **日志埋点**：视频连接 accept 成功、首次触控注入成功（里程碑，证明注入链路端到端可用）、注入被系统持续拒绝（一次性告警，不刷屏）等关键路径日志。

## 架构与线程模型

调用方在任意线程调用 `start()`：同步完成参数解析与视频 `ServerSocket` 绑定后，全部会话工作（阻塞 accept 视频连接、启动编码/控制 processor、Looper 事件泵、结束时的完整清理）运行在自建 **`HandlerThread("carlink-scrcpy")`** 上，停止时 `quitSafely()` 的只会是这个 Looper，绝不触碰 app 主 Looper。编码、控制收发各有独立线程；`Listener` 全部回调直接在本库内部线程上触发，**调用方需自行切线程**。

```
调用方线程                carlink-scrcpy (HandlerThread)        video 线程            control-recv / control-send
─────────────            ──────────────────────────────        ────────────          ──────────────────────────
start() ───────────────> accept() 阻塞等车机视频连接
  (同步 bind 完即返回)     启动 SurfaceEncoder / Controller ──> MediaCodec 编码循环      读控制消息→注入虚拟屏
                            Looper.loop() 事件泵               Os.write→视频 TCP       device 消息→控制 TCP
stop()  ───────────────> finally 清理链：
(任意线程, 幂等,            stop/join processors →
  不阻塞)                   shutdown/close sockets →
                            release 虚拟屏 → quitSafely()
                          回调 onStopped()
```

## API 快速上手

```java
public final class CarLinkServer {
    public static final class Config { /* Builder: width/height/densityDpi 必填；
        bitRate(默认 8Mbps)、codec("h264"/"h265", 默认 h264)、maxFps(默认 0=不限)、
        iFrameIntervalSec(默认 10)、videoPort(默认 0=自动分配)、
        displayImePolicy(默认 DISPLAY_IME_POLICY_LOCAL：IME 弹在虚拟屏上；Android 13 以下忽略) 可选 */ }
    public interface Listener {
        void onVirtualDisplayReady(int displayId); // 虚拟屏已创建，可 launch Activity
        void onError(String message, Throwable cause);
        void onStopped();                          // 会话完全结束（清理完成）
    }
    public static synchronized CarLinkServer start(Context context, Config config, Socket controlSocket, Listener listener);
    public int getVideoPort();  // start() 返回后即有效
    public void stop();         // 任意线程可调用，幂等，不阻塞（完成通知走 onStopped）
    public boolean isRunning();
}
```

互联服务典型用法（在工作线程中执行）：

```java
// 1. 接受车机控制 TCP 连接（默认端口 27183，握手协议见 docs/carlink-protocol.md）
Socket controlSocket = controlServerSocket.accept();

// 2. 读车机 hello：4 字节大端长度 + UTF-8 JSON
//    {"type":"hello","width":W,"height":H,"dpi":D,"codecs":["h264","h265"]}
Hello hello = readHello(controlSocket); // 互联服务自行实现 JSON 握手

// 3. 构造配置并启动（start 内同步绑定视频端口）
CarLinkServer.Config config = new CarLinkServer.Config.Builder(hello.width, hello.height, hello.dpi)
        .codec(hello.codecs.contains("h265") ? "h265" : "h264")
        .bitRate(8_000_000)
        .build();

CarLinkServer server = CarLinkServer.start(getApplicationContext(), config, controlSocket,
        new CarLinkServer.Listener() {
            @Override
            public void onVirtualDisplayReady(int displayId) {
                // 注意：回调在本库内部线程上，需要时自行切到主线程
                ActivityOptions options = ActivityOptions.makeBasic();
                options.setLaunchDisplayId(displayId); // 需要 INTERNAL_SYSTEM_WINDOW（平台签名 privapp）
                Intent intent = new Intent(context, CarLauncherActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent, options.toBundle());
            }

            @Override
            public void onError(String message, Throwable cause) {
                Log.w(TAG, "CarLink session error: " + message, cause);
            }

            @Override
            public void onStopped() {
                // 虚拟屏已销毁、socket 已关闭，可回到待连接状态
            }
        });

// 4. 回 ready（含视频端口），车机随即连接该端口，会话自动开始
writeReady(controlSocket, config.getCodec(), server.getVideoPort());

// 结束会话（或任一侧断线自动结束）：
server.stop();
```

约束：同时只允许一个会话，重复 `start()` 抛 `IllegalStateException`；`controlSocket` 必须是**已完成握手**的已连接 TCP socket，本库不做握手，只在其上读控制消息 / 写 device 消息。

## 集成方法

1. 将本仓库放入 LineageOS 源码树，建议路径 `vendor/carlink/scrcpy`；
2. 互联服务模块 `Android.bp` 添加 `static_libs: ["carlink_scrcpy"]`，并使用 `certificate: "platform"` + `privileged: true`；
3. 配置 signature 权限白名单（`INJECT_EVENTS` / `INTERNAL_SYSTEM_WINDOW` / `ADD_TRUSTED_DISPLAY` / `ADD_ALWAYS_UNLOCKED_DISPLAY`）。

完整步骤、Android.bp 片段、白名单 XML 示例与 sepolicy 说明见 **[docs/integration.md](docs/integration.md)**。

## 文档索引

- **[docs/carlink-protocol.md](docs/carlink-protocol.md)**：车机端实现的权威协议文档（握手 JSON、视频流字节布局、控制消息子集、断线语义）
- **[docs/integration.md](docs/integration.md)**：ROM 集成指南（树内放置、Android.bp、privapp 权限、平台签名、sepolicy）
- 上游保留文档（用户向，适用性以 `carlink-protocol.md` 开头说明为准）：
  - [docs/control.md](docs/control.md) / [docs/video.md](docs/video.md) / [docs/virtual-display.md](docs/virtual-display.md) / [docs/device.md](docs/device.md) / [docs/develop.md](docs/develop.md)

## 同步上游策略

- 包名与目录结构保持 `com.genymobile.scrcpy` 不变；魔改集中在少数文件，rebase 时重点关注：
  - 新增：`CarLinkServer.java`、`device/CarLinkConnection.java`、`util/AppContext.java`（及本仓库的 `docs/carlink-*.md`）；
  - 修改：`Options.java`（`i_frame_interval`；裁剪已删特性的选项）、`video/SurfaceEncoder.java`（i-frame 间隔透传；启动失败资源释放、有界 join）、`device/Streamer.java`（codec id 无条件首发）、`control/ControlChannel.java`（流式构造）、`control/Controller.java` 与 `control/DeviceMessageSender.java`（Throwable 兜底、有界 join 等健壮性改造）、`control/ControlMessageReader.java`（长度上限）、`AsyncProcessor.java`（终止回调带 `cause`）、`opengl/OpenGLRunner.java`（线程复位与有界 join）、`util/Ln.java`（仅 logcat）、`wrappers/` 与 `device/Device.java`（`AppContext`）；
  - 删除：`Server.java`、`FakeContext.java`、`Workarounds.java`、`device/DesktopConnection.java`，及死代码 `wrappers/ContentProvider.java`、`util/SettingsException.java`、`util/HandlerExecutor.java`、`video/VideoSource.java`；
- 其余目录（`video/`、`control/`、`display/`、`opengl/`、`model/`、`wrappers/` 大部分）与上游基本一致，可直接采用上游修复；
- `BuildConfig.VERSION_NAME` 必须跟随上游基线版本号（`Options.parse` 首参数版本校验依赖它）。

## 许可与署名

本项目基于 [Genymobile/scrcpy](https://github.com/Genymobile/scrcpy) 修改，上游版权归 Genymobile 及各位贡献者所有。本仓库保留上游 Apache-2.0 许可证全文（见 `LICENSE`），修改后的代码同样以 Apache-2.0 发布。
