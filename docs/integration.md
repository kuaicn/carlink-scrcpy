# ROM 集成指南

本库以 `android_library` 形式编入 Android 系统源码树（AOSP/LineageOS，目标基线 LineageOS 23.2 / Android 16），由平台签名的 privapp「互联服务」通过 `static_libs` 依赖，在其进程内运行。

## 1. 源码树放置

将本仓库放入源码树，建议路径：

```
vendor/carlink/scrcpy/
```

仓库根部的 `Android.bp` 已定义模块 `carlink_scrcpy`：

```bp
android_library {
    name: "carlink_scrcpy",
    srcs: ["src/**/*.java"],
    // sdk_version 缺省 = 直编树内 platform（含 hidden API），与 WindowManager-Shell 同款
}
```

`android_library` 缺省 `sdk_version` 即随树内 platform 编译，可直接引用 `@hide` 的内部 API（与 WindowManager-Shell 等树内模块同款）。本库大量依赖 `android.hardware.display.DisplayManagerGlobal`、`android.view.SurfaceControl` 等内部接口，全部经反射访问，树内编译不受 hidden API 限制。

在互联服务模块中声明依赖（树内实例为 `vendor/carlink/interconnect/Android.bp` 的 `CarLinkInterconnect`）：

```bp
android_app {
    name: "CarLinkInterconnect",
    srcs: ["src/**/*.java"],
    certificate: "platform",        // 平台签名（见第 4 节）
    privileged: true,               // 安装到 /system/priv-app
    platform_apis: true,            // 直编树内 platform（含 hidden API）
    static_libs: ["carlink_scrcpy"],
    // ...
}
```

## 2. privapp 所需 signature 权限

本库与互联服务用到的关键系统能力及对应权限（signature 及其变体保护级别，平台签名 + privapp 白名单后授予；与树内 `vendor/carlink/interconnect/AndroidManifest.xml`、`vendor/carlink/config/privapp-permissions-carlink.xml` 一致）：

| 权限 | 用途 | 说明 |
|---|---|---|
| `android.permission.INJECT_EVENTS` | 触控/按键注入 | `InputManager.injectInputEvent()` 硬性要求；车机触控事件注入虚拟屏依赖它 |
| `android.permission.INTERNAL_SYSTEM_WINDOW` | 跨屏启动 Activity | 互联服务通过 `ActivityOptions.setLaunchDisplayId()` 把车机桌面 Activity 启动到虚拟屏；非默认屏启动在 ActivityTaskManagerService 侧检查该权限 |
| `android.permission.ADD_TRUSTED_DISPLAY` | 创建 TRUSTED 虚拟屏 | `NewDisplayCapture` 创建 VirtualDisplay 时带 `VIRTUAL_DISPLAY_FLAG_TRUSTED`（及 OWN_FOCUS/OWN_DISPLAY_GROUP 等组合 flag，Android 13+），DisplayManagerService 检查该权限；无 TRUSTED 的虚拟屏无法显示锁屏/系统 UI 且焦点行为受限 |
| `android.permission.ADD_ALWAYS_UNLOCKED_DISPLAY` | 创建 ALWAYS_UNLOCKED 虚拟屏 | flag 组合含 `VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED`（Android 13+，使虚拟屏不受手机锁屏状态影响），DisplayManagerService 单独检查该权限；缺失会抛 `SecurityException: Requires ADD_ALWAYS_UNLOCKED_DISPLAY permission...`（真机已验证） |
| `android.permission.MANAGE_ACTIVITY_TASKS` | 监听虚拟屏属性变化 | 库内 `DisplayMonitor` 经 `WindowManagerService.registerDisplayWindowListener()` 监听虚拟屏尺寸/旋转等变化以重置采集（protectionLevel 为 signature&#124;recents，平台签名即可授予）；未授予时降级为不监听并记一次日志，不致命 |
| `android.permission.START_ACTIVITIES_FROM_BACKGROUND` | 后台启动车机桌面 | 互联服务为后台常驻 Service（无前台 Activity），Android 10+ 后台启动 Activity 限制需该权限豁免，否则 `onVirtualDisplayReady` 里启动车机桌面被系统拦截 |
| `android.permission.WRITE_SECURE_SETTINGS` | 会话期间禁止锁屏 | 互联服务在会话期将 `Settings.Secure"lockscreen.disabled"` 置 1（保存原值，结束时条件恢复，进程崩溃由下次启动对账恢复），防止息屏后锁屏介入打断车机端操作 |
| `android.permission.WRITE_SETTINGS` | 会话期间熄灭手机物理屏 | 互联服务会话期将亮度置 0（保存原值与亮度模式，结束时条件恢复）实现「息屏只在车机端显示」。**不用 `requestDisplayPower(STATE_OFF)`**：STATE_OFF 会让设备进入 doze，继而把虚拟屏强制拉灭——投屏画面冻结、虚拟屏输入视口失效、触控被 InputDispatcher 丢弃（真机已验证）；亮度 0 下设备保持唤醒，OLED 纯黑即视觉息屏。protectionLevel 为 signature&#124;appop，平台签名安装期授予，**无需进 privapp 白名单** |

另建议（非 signature|privileged 级，安装期自动授予，无需白名单）：

| 权限 | 用途 |
|---|---|
| `android.permission.QUERY_ALL_PACKAGES` | `start_app` 控制消息按名称搜索/列举已安装应用（`PackageManager.getInstalledApplications`）需要完整包可见性；仅需按包名启动时可省略（树内由车机桌面 `com.carlink.launcher` 声明并列入白名单） |
| `android.permission.INTERNET` | 控制/视频 TCP 通道 |

## 3. privapp-permissions 白名单

signature|privileged 权限除平台签名外，还需在 privapp 白名单中显式声明。树内实际文件为 `vendor/carlink/config/privapp-permissions-carlink.xml`（经 `vendor/carlink/carlink.mk` 的 `PRODUCT_COPY_FILES` 安装），内容如下：

```xml
<?xml version="1.0" encoding="utf-8"?>
<permissions>
    <privapp-permissions package="com.carlink.interconnect">
        <permission name="android.permission.INJECT_EVENTS"/>
        <permission name="android.permission.INTERNAL_SYSTEM_WINDOW"/>
        <permission name="android.permission.ADD_TRUSTED_DISPLAY"/>
        <permission name="android.permission.ADD_ALWAYS_UNLOCKED_DISPLAY"/>
        <!-- signature|recents 权限，platform 签名本身即可授予；列入白名单以保持与 Manifest 声明一致 -->
        <permission name="android.permission.MANAGE_ACTIVITY_TASKS"/>
        <permission name="android.permission.WRITE_SECURE_SETTINGS"/>
        <permission name="android.permission.START_ACTIVITIES_FROM_BACKGROUND"/>
        <permission name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE"/>
    </privapp-permissions>
    <privapp-permissions package="com.carlink.launcher">
        <permission name="android.permission.QUERY_ALL_PACKAGES"/>
    </privapp-permissions>
</permissions>
```

说明：

- `FOREGROUND_SERVICE_SPECIAL_USE` 为常驻前台服务（`foregroundServiceType="specialUse"`）所需；
- `WRITE_SETTINGS`（signature|appop）不在白名单中：平台签名安装期即授予，运行时经 `Settings.System.canWrite()` 探测、缺失时仅降级（不息屏）不影响会话；
- 移植时 `package` 替换为互联服务/车机桌面的实际包名；白名单 XML 必须与目标 App 装入**同分区**（两个 App 默认装入 `/system/priv-app`，故白名单拷到 `system/etc/permissions`）。

在产品 makefile 中安装（树内 `carlink.mk` 片段）：

```make
PRODUCT_COPY_FILES += \
    vendor/carlink/config/privapp-permissions-carlink.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/privapp-permissions-carlink.xml
```

并在互联服务 `AndroidManifest.xml` 中声明全部上述 `<uses-permission>`（树内实例见 `vendor/carlink/interconnect/AndroidManifest.xml`，另含 `INTERNET`/`WRITE_SETTINGS`/`RECEIVE_BOOT_COMPLETED`/`FOREGROUND_SERVICE` 等）。

## 4. 平台签名

互联服务必须使用**平台签名**（与系统 framework 相同的签名密钥）：

- 树内构建：`Android.bp` 中 `certificate: "platform"`；
- 若使用自定义平台密钥（LineageOS 常见），保持 `certificate: "platform"` 即可，Soong 会链接到产品配置的默认签名密钥（`PRODUCT_DEFAULT_DEV_CERTIFICATE`）。

只有平台签名的应用才能被授予上述 signature 权限；签名不匹配时 `PackageManager` 会拒绝授权，运行期表现为 `SecurityException`（注入失败 / createVirtualDisplay 抛异常）。

## 5. sepolicy 备注

本库对系统服务的访问**全部是 Binder 调用**（DisplayManager/WindowManager/InputManager/ActivityManager/PowerManager/StatusBarManager/ClipboardManager），运行在 privapp 进程内、走 `system_app`/`privapp` 既有域：

- **不需要新增 sepolicy 规则**；
- **不使用 `/dev/uhid`**（上游 UHID 键鼠模拟已整体删除，无字符设备访问）；
- 不创建 socket 类型 SELinux 客体之外的特殊资源：视频/控制通道是普通 TCP socket，由 untrusted/privapp 域常规放行（`internet` 能力）；若产品策略收紧了 privapp 的网络访问，需确认域规则允许 `tcp_socket`（`create`/`bind`/`accept`）——AOSP 默认策略下 privapp 具备网络能力；
- 本库不再 fork 独立进程、不执行 `app_process`，无 `exec` 类 denials。

## 6. 运行期自检

集成后可用以下日志快速定位问题（logcat TAG：`scrcpy`）：

```bash
adb logcat -s scrcpy
```

- `Could not create display` → 检查 `ADD_TRUSTED_DISPLAY`、`ADD_ALWAYS_UNLOCKED_DISPLAY` 白名单与平台签名（真机实例：缺后者时底层抛 `SecurityException: Requires ADD_ALWAYS_UNLOCKED_DISPLAY permission to create an always unlocked virtual display.`）；
- 注入失败且报 `INJECT_EVENTS permission` → 检查 `INJECT_EVENTS` 授权；
- `Could not register display window listener (MANAGE_ACTIVITY_TASKS not granted)` → 白名单补 `MANAGE_ACTIVITY_TASKS`（缺失仅降级：虚拟屏属性变化不再被监听，不致命）；
- 车机桌面未出现在虚拟屏 → 检查 `START_ACTIVITIES_FROM_BACKGROUND` 与 `INTERNAL_SYSTEM_WINDOW` 授权；
- 会话秒断 → 检查两条 TCP 通道（控制通道握手是否完成、videoPort 是否可达）；会话在约 30–50s 后被判死回收（日志 `CarLink session stalled`）→ 车机端异常断电/断网所致，属看门狗正常回收，重新握手即可。

### 权限授予真机验证清单

第 6 节前面的症状条目指向某条权限时，用以下命令确认其运行时授予状态（树内包名：`com.carlink.interconnect` 互联服务、`com.carlink.launcher` 车机桌面、`com.carlink.headunit` 车机端 App）。

1. **声明权限的授予总览**（`dumpsys package` 的 `requested permissions` 与 `install permissions` 两段，每条出现两次属正常）：

```bash
adb shell dumpsys package com.carlink.interconnect | grep -E \
  'android.permission.(INTERNET|INJECT_EVENTS|INTERNAL_SYSTEM_WINDOW|ADD_TRUSTED_DISPLAY|ADD_ALWAYS_UNLOCKED_DISPLAY|MANAGE_ACTIVITY_TASKS|WRITE_SECURE_SETTINGS|WRITE_SETTINGS|START_ACTIVITIES_FROM_BACKGROUND|RECEIVE_BOOT_COMPLETED|FOREGROUND_SERVICE|FOREGROUND_SERVICE_SPECIAL_USE): granted=true'
```

期望 12 条声明权限全部 `granted=true`；任何一条 `granted=false` 即为故障点。

2. **白名单实际生效核对**（框架解析 privapp-permissions XML 后的结果）：

```bash
adb shell pm get-privapp-permissions com.carlink.interconnect   # 期望列出白名单 8 条
adb shell pm get-privapp-deny-permissions com.carlink.interconnect   # 期望为空
adb shell pm get-privapp-permissions com.carlink.launcher       # 期望 QUERY_ALL_PACKAGES 1 条
```

注意：该命令反映的是白名单声明，不代表这些权限都必须走白名单。本树中仅 `WRITE_SECURE_SETTINGS`、`START_ACTIVITIES_FROM_BACKGROUND` 带 privileged flag（白名单对授权起决定作用）；其余条目平台签名已可授予，白名单为双保险。若列表为空，通常是白名单 XML 与 priv-app 未装入同分区（两者都应在 system 分区）或 XML 解析失败（开机 logcat 过滤 `SystemConfig`）。

3. **appop 类权限**（授予与否不体现在 `granted=` 标志，要查对应 op）：

```bash
adb shell appops get com.carlink.interconnect android:write_settings                  # 期望 allow
adb shell appops get com.carlink.interconnect android:foreground_service_special_use  # 期望 allow
```

`WRITE_SETTINGS` 对应 op 为 `ignore` 时服务仅降级不息屏（日志 `WRITE_SETTINGS not granted, skip dimming phone screen`），会话不受影响；`FOREGROUND_SERVICE_SPECIAL_USE` 为 `ignore` 时 `startForeground` 会失败，服务启动即自停（日志 `startForeground failed, stopping self`）。

4. **自定义签名权限 `com.carlink.permission.MANAGE_TASK_VIEW`**（车机桌面 ↔ SystemUI 闸门）：

```bash
adb shell dumpsys package com.carlink.launcher | grep -B1 -A3 'MANAGE_TASK_VIEW'
```

期望同时看到：权限定义（`protectionLevel=signature`，由 `com.carlink.launcher` 定义）与 `install permissions` 中 `granted=true`。反向验证闸门有效性：任一非平台签名 App 绑定 `com.carlink.taskview.action.BIND_TASK_VIEW_SERVICE` 应被拒（`bindService` 抛 `SecurityException`，或调用时 SystemUI 侧 `ensureManageTaskViewPermission` 抛异常，logcat 过滤 `CarLinkTaskViewService`）。

5. **车机端 App**（全部为 normal 权限，安装期授予，无运行时申请流程）：

```bash
adb shell dumpsys package com.carlink.headunit | grep -E \
  'android.permission.(INTERNET|ACCESS_NETWORK_STATE|ACCESS_WIFI_STATE): granted=true'
```

6. **`pm grant` 不适用**：以上权限均非 runtime（dangerous）权限，`adb shell pm grant <pkg> <perm>` 会报 `Permission ... is not a changeable permission type`，属预期，不能用 `pm grant` 修复签名/白名单类授权问题。正确修复路径：核对平台签名（`adb shell dumpsys package <pkg>` 输出中的签名信息（`signatures`/`signingDetails` 行）与 `dumpsys package android` 的比对一致）与白名单分区位置，再核对本清单第 1–3 条。
