# ROM 集成指南

本库以 `java_library` 形式编入 Android 系统源码树（AOSP/LineageOS，目标基线 LineageOS 23.2 / Android 16），由平台签名的 privapp「互联服务」通过 `static_libs` 依赖，在其进程内运行。

## 1. 源码树放置

将本仓库放入源码树，建议路径：

```
vendor/carlink/scrcpy/
```

仓库根部的 `Android.bp` 已定义模块 `carlink_scrcpy`：

```bp
java_library {
    name: "carlink_scrcpy",
    srcs: ["src/**/*.java"],
    // 树内编译，直接使用 framework（含 hidden API），不走 SDK
    sdk_version: "none",
    libs: ["framework"],
}
```

`sdk_version: "none"` + `libs: ["framework"]` 使模块可以使用 `@hide` 的内部 API（本库大量依赖 `android.hardware.display.DisplayManagerGlobal`、`android.view.SurfaceControl` 等内部接口，全部经反射访问，树内编译不受 hidden API 限制）。

在互联服务模块中声明依赖：

```bp
android_app {
    name: "CarLinkService",
    srcs: ["src/**/*.java"],
    certificate: "platform",        // 平台签名（见第 3 节）
    privileged: true,               // 安装到 /system/priv-app
    sdk_version: "system",          // 或 "none"
    static_libs: ["carlink_scrcpy"],
    // ...
}
```

## 2. privapp 所需 signature 权限

本库用到的关键系统能力及对应权限（全部为 signature 级，平台签名 + privapp 白名单后授予）：

| 权限 | 用途 | 说明 |
|---|---|---|
| `android.permission.INJECT_EVENTS` | 触控/按键注入 | `InputManager.injectInputEvent()` 硬性要求；车机触控事件注入虚拟屏依赖它 |
| `android.permission.INTERNAL_SYSTEM_WINDOW` | 跨屏启动 Activity | 互联服务通过 `ActivityOptions.setLaunchDisplayId()` 把车机桌面 Activity 启动到虚拟屏；非默认屏启动在 ActivityTaskManagerService 侧检查该权限 |
| `android.permission.ADD_TRUSTED_DISPLAY` | 创建 TRUSTED 虚拟屏 | `NewDisplayCapture` 创建 VirtualDisplay 时带 `VIRTUAL_DISPLAY_FLAG_TRUSTED`（及 OWN_FOCUS/OWN_DISPLAY_GROUP 等组合 flag，Android 13+），DisplayManagerService 检查该权限；无 TRUSTED 的虚拟屏无法显示锁屏/系统 UI 且焦点行为受限 |
| `android.permission.ADD_ALWAYS_UNLOCKED_DISPLAY` | 创建 ALWAYS_UNLOCKED 虚拟屏 | flag 组合含 `VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED`（Android 13+，使虚拟屏不受手机锁屏状态影响），DisplayManagerService 单独检查该权限；缺失会抛 `SecurityException: Requires ADD_ALWAYS_UNLOCKED_DISPLAY permission...`（真机已验证） |

另建议（非 signature 级，安装期自动授予）：

| 权限 | 用途 |
|---|---|
| `android.permission.QUERY_ALL_PACKAGES` | `start_app` 控制消息按名称搜索/列举已安装应用（`PackageManager.getInstalledApplications`）需要完整包可见性；仅需按包名启动时可省略 |
| `android.permission.INTERNET` | 控制/视频 TCP 通道 |

## 3. privapp-permissions 白名单

signature 权限除平台签名外，还需在 privapp 白名单中显式声明。新增（或合并到现有）XML，例如 `vendor/carlink/permissions/privapp-permissions-carlink.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<permissions>
    <privapp-permissions package="com.example.carlink">
        <permission name="android.permission.INJECT_EVENTS"/>
        <permission name="android.permission.INTERNAL_SYSTEM_WINDOW"/>
        <permission name="android.permission.ADD_TRUSTED_DISPLAY"/>
        <permission name="android.permission.ADD_ALWAYS_UNLOCKED_DISPLAY"/>
    </privapp-permissions>
</permissions>
```

（`package` 替换为互联服务的实际包名。）

在产品 makefile 中安装：

```make
PRODUCT_COPY_FILES += \
    vendor/carlink/permissions/privapp-permissions-carlink.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/privapp-permissions-carlink.xml
```

并在互联服务 `AndroidManifest.xml` 中声明全部上述 `<uses-permission>`。

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
- 会话秒断 → 检查两条 TCP 通道（控制通道握手是否完成、videoPort 是否可达）。
