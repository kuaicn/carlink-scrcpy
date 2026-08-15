# CarLink 投屏协议（车机端权威文档）

本文档是**车机端实现的唯一权威依据**，描述车机（Android 车机，client）与手机端「互联服务」（内嵌本库）之间的两条 TCP 通道：控制通道与视频通道。

## 上游文档适用性说明

本仓库 `docs/` 下保留的 5 篇文档（`control.md`、`video.md`、`device.md`、`virtual-display.md`、`develop.md`）是上游 scrcpy v4.1 的**用户向功能文档**，其中大量内容不适用于本项目：

- 它们描述的命令行参数、adb 隧道（`--tunnel-forward`）、音频（`--audio-*`）、摄像头（`--camera-*`）、UHID 键鼠（`--mouse=uhid`/`--keyboard=uhid`）、OTG 等特性在本项目中**已删除或不生效**；
- 它们相互之间的交叉链接（如 `keyboard.md`、`mouse.md`、`camera.md`、`audio.md`、`shortcuts.md`、`recording.md` 等）指向**未保留**的文档，链接失效属预期；
- 上游 client/server 之间经 adb 隧道承载 abstract LocalSocket，本项目已改为**纯 TCP**；
- 上游视频流开头的 64 字节设备名 meta、tunnel-forward 的 1 字节 dummy byte、session meta 包，本项目**一律不发送**（详见下文「视频通道」）；
- 其中对**控制消息/视频包 wire 格式**的描述仍可作为背景参考，但与本文档冲突时，**以本文档为准**（本文档与 `src/` 下代码逐一核对过）。

## 总览

```
 车机 (client)                                手机 (server, 互联服务进程)
      |  1. TCP connect  :27183 (控制通道)          |
      |------------------------------------------>|
      |  2. hello  (4B 长度 + JSON)                 |
      |------------------------------------------>|
      |  3. ready  (4B 长度 + JSON, 含 videoPort)   |
      |<------------------------------------------|
      |  4. TCP connect  :videoPort (视频通道)      |
      |------------------------------------------>|
      |  5. 视频流 (codec id + packet 序列)         |
      |<------------------------------------------|
      |  6. 控制消息 (触控/按键/...)  ←→  device 消息 |
      |<=========================================>|
```

- 两条连接都是**车机主动 TCP 连接手机**；
- 控制通道默认端口 **27183**（由手机端互联服务监听；握手也由互联服务完成，不属于本库职责，但协议格式在本文档定义）；
- 视频通道端口由手机在 `ready` 中下发（`videoPort`）；
- 握手完成后，控制通道立即切换为 **scrcpy 原版控制消息流**（二进制，见下文），此后该连接上不再出现 JSON。

## 一、握手（控制通道上，车机先发言）

每条握手消息 = **4 字节大端无符号长度**（后续 JSON 的字节数，不含这 4 字节）+ **UTF-8 JSON**。

### 1. 车机 → 手机：`hello`

```json
{"type":"hello","width":1920,"height":720,"dpi":160,"codecs":["h264","h265"]}
```

| 字段 | 含义 |
|---|---|
| `width` / `height` | 车机屏幕物理分辨率（像素）。手机以此创建 VirtualDisplay 并作为编码尺寸；仅当编码器要求更粗的尺寸对齐时，先**向下**对齐到其对齐倍数（对齐值 ≤16 时 1920x720 等常见尺寸不变），此时虚拟屏/视频尺寸以对齐后的值为准 |
| `dpi` | 车机屏幕密度 |
| `codecs` | 车机支持的视频 codec 列表，按偏好排序；取值 `"h264"`、`"h265"` |

### 2. 手机 → 车机：`ready`

```json
{"type":"ready","codec":"h264","videoPort":42319}
```

| 字段 | 含义 |
|---|---|
| `codec` | 手机从 `codecs` 中选定的 codec（`"h264"` 或 `"h265"`） |
| `videoPort` | 视频通道 TCP 端口。手机侧已绑定监听，车机应**立即连接** |

握手失败（JSON 非法、尺寸/dpi 非法、`codecs` 为空或无交集等）：手机直接关闭连接；也可以先发一帧 `error` 再关闭：

```json
{"type":"error","reason":"busy"}
```

`reason` 取值：`busy`（已有会话在进行）、`no_common_codec`（codec 无交集）、`invalid_display`（尺寸/dpi 非法）。车机端必须同时兼容两种拒绝方式（无帧直接关闭 / `error` 帧）。

### 3. 握手后

- 车机连接 `videoPort`（视频通道）；
- 手机侧视频通道接通后，会话正式开始：
  - 视频通道：手机 → 车机，持续推流（格式见下节）；
  - 控制通道：切换为 scrcpy 控制消息流，车机 → 手机发送控制消息，手机 → 车机发送 device 消息；
- 手机创建好 VirtualDisplay 后，互联服务在其上启动车机桌面 Activity（车机端无感知，画面自来）。

两条通道均为**低时延流**：手机侧已对两条 socket 设置 `TCP_NODELAY`，建议车机端同样设置。

## 二、视频通道（手机 → 车机）

### 与上游 scrcpy 协议的差异（重要）

| 上游 scrcpy | 本项目 |
|---|---|
| 流首 64 字节设备名 meta | **无** |
| tunnel-forward 模式下 1 字节 dummy byte | **无** |
| 编码器（重）启动时发送 session meta 包（bit63） | **无**（会话期间分辨率恒定） |
| 4 字节 codec id | **保留，即流的前 4 字节** |
| 每个 packet 12 字节头 + Annex-B 载荷 | 保留，格式相同 |

### 字节布局

```
+0  4B   codec id（大端）：h264 = 0x68323634，h265 = 0x68323635（即 ASCII "h264"/"h265"）
+4  ┌──────────── 之后为 packet 序列，每个 packet：───────────┐
    │  8B  pts_and_flags（大端 s64）：                       │
    │      bit 62 = config 包（codec config，如 SPS/PPS）    │
    │      bit 61 = keyframe                                │
    │      其余低位 = pts（微秒）；config 包时该值无意义      │
    │  4B  packet 长度 N（大端 u32，不含本 12 字节头）        │
    │  NB  裸 Annex-B 码流数据                               │
    └────────────────────────────────────────────────────────┘
```

解析要点：

- 首 4 字节即为 codec id，**没有任何前置元数据**，不要按上游格式跳过 64 或 65 字节；
- 逐 packet 读取：先读 12 字节头，取低 4 字节为载荷长度，再读对应字节数；
- `bit62=1` 的包是 codec 配置（h264 的 SPS/PPS、h265 的 VPS/SPS/PPS），编码器启动及每次 IDR 前都可能出现，解码器应将其送入解码器而不按帧渲染；
- 非 config 包的 pts 为相对时间戳（微秒），可用于音画同步/渲染调度；本项目不传输音频；
- **bit63 恒为 0**（本项目不发送 session meta）；分辨率以 `hello` 上报的 `width`×`height` 为准，会话期间不变。

## 三、控制消息（车机 → 手机，握手后的控制通道上）

二进制流，每条消息首字节为类型（u8），随后字段全部**大端**，与上游 scrcpy v4.1 server 完全一致。本项目支持的消息子集：

| type | 名称 | 载荷格式 | 说明 |
|---|---|---|---|
| 0 | INJECT_KEYCODE | u8 action, s32 keycode, s32 repeat, s32 metaState | 注入按键到虚拟屏 |
| 1 | INJECT_TEXT | u32 len + UTF-8 | 文本输入（经 KeyCharacterMap 转为按键序列） |
| 2 | INJECT_TOUCH_EVENT | u8 action, s64 pointerId, s32 x, s32 y, u16 screenW, u16 screenH, u16 pressure, s32 actionButton, s32 buttons | **车机触控主消息**。坐标系为视频像素（`screenW`×`screenH` 必须等于服务端**当前视频尺寸**——即解码器上报的可见尺寸；无对齐裁剪时等于 hello 上报值，不等于时服务端静默丢弃该事件）；`pointerId`：单指触控可用任意稳定 id（多点时每指一个 id）；`pressure` 为 u16 定点（0xFFFF=1.0，UP 事件为 0） |
| 3 | INJECT_SCROLL_EVENT | s32 x, s32 y, u16 screenW, u16 screenH, s16 hScroll, s16 vScroll, s32 buttons | 滚轮/滚动；滚动量为 i16 定点（满量程 ±16） |
| 4 | BACK_OR_SCREEN_ON | u8 action | 虚拟屏亮着→注入 BACK；否则注入 POWER 点亮 |
| 5 | EXPAND_NOTIFICATION_PANEL | 无 | 展开通知栏 |
| 6 | EXPAND_SETTINGS_PANEL | 无 | 展开快捷设置 |
| 7 | COLLAPSE_PANELS | 无 | 收起面板 |
| 8 | GET_CLIPBOARD | u8 copyKey（0=none/1=copy/2=cut） | 请求手机剪贴板（回 CLIPBOARD device 消息） |
| 9 | SET_CLIPBOARD | s64 sequence, u8 paste, u32 len + UTF-8 | 设置手机剪贴板；sequence≠0 时回 ACK_CLIPBOARD |
| 10 | SET_DISPLAY_POWER | u8 on | 物理屏电源开关（不影响虚拟屏投屏） |
| 11 | ROTATE_DEVICE | 无 | 旋转目标屏 |
| 15 | OPEN_HARD_KEYBOARD_SETTINGS | 无 | 打开硬键盘设置 |
| 16 | START_APP | u8 len + UTF-8 name | 在虚拟屏上启动应用；name 前缀：`+` 先 force-stop，`?` 按应用名搜索（否则按包名） |
| 17 | RESET_VIDEO | 无 | 请求重建编码会话（下一帧为 IDR） |
| 22 | SCAN_FILE | u32 len + UTF-8 path | 触发 MediaStore 扫描 |

**不支持的消息（车机端不得发送）**：type 18/19/20（CAMERA_*，本项目无摄像头）、type 21（RESIZE_DISPLAY，仅上游 flex display 模式支持，本项目会话为固定尺寸虚拟屏）。这些**已定义但不受支持**的消息会被手机端解析后丢弃（仅记录日志，会话继续）；只有**未定义的消息类型或非法长度字段**（协议错误）才会导致手机端结束会话。带长度前缀的字段（INJECT_TEXT、SET_CLIPBOARD、SCAN_FILE 等）长度上限为 **256 KiB**（服务端 `MESSAGE_MAX_SIZE`），超限即协议错误。

触控注入目标是手机按 hello 参数创建的 VirtualDisplay，不是手机物理屏；坐标无需车机端做额外缩放（虚拟屏尺寸 = 视频尺寸，通常等于车机屏幕尺寸，差异仅来自上述编码器对齐的向下取整）。

## 四、device 消息（手机 → 车机，控制通道反方向）

| type | 名称 | 载荷格式 | 说明 |
|---|---|---|---|
| 0 | CLIPBOARD | u32 len + UTF-8 | 手机剪贴板内容（剪贴板变化自动同步，或响应 GET_CLIPBOARD） |
| 1 | ACK_CLIPBOARD | s64 sequence | 对 SET_CLIPBOARD 的确认（回显其 sequence） |

## 五、断线与错误语义

- **任一通道断开（任一侧）→ 整个会话终止**：手机端停止编码、销毁 VirtualDisplay、关闭两条连接；车机端应销毁解码器并回到待连接状态；
- 手机端会话完全结束后才能开始新会话（同一时刻只允许一个会话）；
- 车机主动结束会话的方式：关闭控制通道或视频通道任一 socket 即可，手机侧会完成全部清理；
- 会话期间手机侧异常（编码器失败等）同样表现为通道关闭，车机端按断线处理。
