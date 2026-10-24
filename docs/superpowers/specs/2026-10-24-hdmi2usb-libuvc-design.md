# hdmi2usb 设计：USB Host + libuvc 直读采集卡

日期：2026-10-24
状态：已获用户批准
前置文档：[2026-10-23-hdmi2usb-design.md](2026-10-23-hdmi2usb-design.md)

## 背景与动机

Camera2 路线在目标设备（小米平板 2410CRP4CC，HyperOS Android 16/API 36）真机验证失败，且不可修复：

1. MIUI 在 `getCameraIdList()` 对普通应用过滤外接相机（已用 ID 段 probe 绕过，提交 7e734b6）。
2. 高通 external HAL 对该 UVC 采集卡每次出流约 4ms 后报 `ERROR_CAMERA_DEVICE(4)`，重开相机同样失败。

设备上"USB摄像头"app（com.shenyaocn.android.usbcamera）可正常打开同一采集卡。对其 APK 的分析结论：

- 未使用 UVCCamera 开源库（无 libuvccamera.so / libusb100.so），而是自研 native 栈（libUSBVideo.so / libUSBAudio.so）。
- 流程：`UsbManager` 授权 → `UsbDeviceConnection.getFileDescriptor()` → JNI/libusb 直读 MJPEG，完全绕过 CameraService。

因此本项目改走同链路，但用现成 C 库而非从零自研。

## 已确认的关键决策

| 决策点 | 选择 | 理由 |
|---|---|---|
| 取流栈 | 自集成 libuvc + libusb（vendor 源码进仓库，CMake 构建，自写薄 JNI） | 与参考 app 同思路但站在成熟 C 库肩上；依赖最小、行为可控；UVCCamera 老旧臃肿，纯 Kotlin bulkTransfer 无先例保障 |
| MJPEG 解码 | 平台 `BitmapFactory`（内部 libjpeg-turbo，高通设备有硬件加速） | 零额外依赖、代码最少，1080p@30 轻松 |
| CameraController | 整体删除，由 `UvcController` 接管 | Camera2 路线已被证实不可用，保留只有维护成本 |
| CAMERA 运行时权限 | 删除 | USB Host 路线不需要 |
| 渲染 | 复用 SurfaceView + DisplayView(双击 FIT/STRETCH) + ScreenFit | 现有实现无变化 |

## 总体架构与数据流

```
采集卡 → Android USB Host（UsbManager 授权弹窗）
       → UsbDeviceConnection.fileDescriptor
       → libusb（libusb_wrap_sys_device）→ libuvc 取流（MJPEG）
       → JNI 帧回调 byte[] → Java 渲染线程
       → BitmapFactory.decodeByteArray（inBitmap 复用）→ Canvas → SurfaceView
```

## 组件设计

### Native 层（新增 `app/src/main/cpp/`）

Vendor 进仓库并锁定版本：libuvc v0.0.7（关闭其内部 JPEG 解码，回调只取原始 MJPEG 帧）、libusb v1.0.27（`libusb_wrap_sys_device` 支持 Android fd 注入）。版本与来源写在 CMakeLists 注释中。

`uvc_jni.c` 约 200 行，对外只暴露：

- `nativeGetStreamFormats(fd): IntArray` —— 枚举 MJPEG 帧描述符，返回扁平数组 [w0,h0,fps0,w1,h1,fps1,...]
- `nativeStart(fd, width, height, fps): Boolean` —— 按选中格式 probe/commit 并开流，内部起取流线程
- `nativeStop()` —— 停流、退出线程、关闭 libuvc 句柄
- 帧回调（native → Java）：`onFrame(byte[])`；每次回调分配一个 jbyteArray 并拷贝一次（简单优先，成本相对解码可忽略）

native 侧失败（枚举无 MJPEG 格式、probe/commit 失败、开流失败）以返回值/异常抛出，Java 层统一走 `onUnavailable`。

### Kotlin 层

- `UvcController(context, listener)`，构造接口沿用现有 `CameraListener`（onStreamReady/onUnavailable），MainActivity 改动最小：
  - `findDevice(): UsbDevice?` —— 按 USB 类/子类枚举视频设备（`device_filter.xml` 已配置视频类 0x0E/0xEF:2），替代 `findExternalCamera()`
  - `start(device, holder)` —— 请求 USB 授权 → `UsbManager.openDevice` → 取 fd → `nativeGetStreamFormats` → 转 `StreamOption` 交 `StreamSelector.select` → `nativeStart`
  - `close()`
- 格式选择复用现有 `StreamSelector` 纯函数与 11 个单测：新增"UVC 格式描述符数组 → `List<StreamOption>`"的映射纯函数（放 `StreamOption` 所在文件或同级新文件），fps 以最高帧率填 min=max。
- USB 授权流程（与参考 app 相同）：`UsbManager.requestPermission(device, pi)` + `ACTION_USB_PERMISSION` 广播接收（registerReceiver 带 RECEIVER_NOT_EXPORTED）。用户拒绝/未授权 → 复用现有 `permissionPanel` UI（文案改为 USB 授权，`btn_grant` 触发重新请求）。
- 渲染：独立 `HandlerThread("RenderThread")`。帧到达 → `BitmapFactory.decodeByteArray`（`Options.inBitmap` 复用同一 Bitmap，防 GC 抖动；尺寸变化时重建）→ `holder.lockCanvas(null)` 按 surface 尺寸绘制 → `unlockCanvasAndPost`。

### Manifest 变更

- 删除 `android.permission.CAMERA` 及 MainActivity 中 CAMERA 运行时权限代码。
- 保留 `USB_DEVICE_ATTACHED` intent-filter 与 `device_filter.xml`。
- 增加 `<uses-feature android:name="android.hardware.usb.host" android:required="true"/>`。

### 构建配置

- 安装 NDK（r27 稳定版）与配套 CMake（sdkmanager，约 2GB 下载）。
- `app/build.gradle.kts` 增加 `externalNativeBuild.cmake`；`ndk.abiFilters` 仅 `arm64-v8a`（目标设备 arm64）。
- minSdk/targetSdk 36 不变；`libusb_wrap_sys_device` 要求 API 29+，无兼容问题。

## 生命周期与错误处理

- 插卡自启：现有 `onNewIntent`（ACTION_USB_DEVICE_ATTACHED）+ `retryCount`/`RETRY_INTERVAL_MS` 重试逻辑保持。
- 拔卡：现有 `PlugMonitor` DETACHED 广播 → `onCaptureCardDetached` → `close()`（含 `nativeStop`）。
- native 流中断/错误 → JNI 上报 → 主线程 `onUnavailable` → 现有重试与退出提示逻辑生效。
- Activity 销毁：`onDestroy` → `close()`，确保取流线程 join、句柄释放、`UsbDeviceConnection` 关闭。
- 守卫沿用：`streaming`/`starting` 双标志防重复启动。

## 测试与验收

- 单元测试（JVM）：`StreamSelector`、`ScreenFit` 保留；新增 UVC 格式数组 → StreamOption 映射的单测（含空数组、超屏过滤、降序）。
- 真机验收（无线 ADB 192.168.123.240:39511，采集卡占用 OTG 口）：
  1. 首次打开出现 USB 授权弹窗，允许后出流
  2. 画面正常、无花屏，双击 FIT/STRETCH 切换
  3. 拔卡 → 退出；重插 → 自启
  4. 拒绝授权 → 面板提示，可重试
  5. 采集卡未接 → 重试提示后退出
  6. 退出 app 后重新进入可再次取流（fd/句柄无泄漏）

## 明确不做（YAGNI）

4K@30、音频采集、MediaCodec 硬解、UVC 控制面板（亮度/对比度等）、多设备选择。1080p 为目标场景。

## 风险

- libuvc v0.0.7 对该采集卡的描述符兼容性 —— 参考 app 全自研栈可跑，libuvc 更通用，风险低；若 probe/commit 失败则走重试降级。
- BitmapFactory 解码 1080p@30 的实际帧率 —— 平台 libjpeg-turbo 足够；若实测不达标再评估硬解（不在本设计范围）。
- NDK 下载受网络影响 —— 一次性环境成本。
