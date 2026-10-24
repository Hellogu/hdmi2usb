# ADR-0001: 放弃 Camera2，改用 USB Host + libuvc 直读采集卡

日期：2026-10-24
状态：已接受

## 背景

hdmi2usb 目标是把 PC HDMI 画面经 UVC 采集卡显示在 Android 平板上（副屏）。初版采用标准 Camera2 API + external camera HAL 路线，真机（小米平板 2410CRP4CC，HyperOS Android 16/API 36）验证失败且不可修复：

1. MIUI 在 `getCameraIdList()` 对普通应用过滤外接相机（可用 ID 段探测绕过，提交 7e734b6）。
2. 高通 external HAL 对该采集卡每次开流约 4ms 后报 `ERROR_CAMERA_DEVICE(4)`，重开相机同样失败——HAL 层问题，应用层无解。

同设备上"USB摄像头"app（com.shenyaocn.android.usbcamera）可正常打开同一采集卡。APK 分析：它未用任何现成 UVC 库，而是自研 native 栈（libUSBVideo.so/libUSBAudio.so），走 `UsbManager` 授权 → `UsbDeviceConnection.getFileDescriptor()` → JNI/libusb 直读 MJPEG，完全绕过 CameraService。该链路已被真机证明可行。

## 决策

采用 USB Host + libuvc 直读路线：

- `UsbManager` 授权 + `openDevice` 取 fd，`libusb_wrap_sys_device` 注入 libusb，libuvc 完成 UVC 枚举/probe-commit/取流。
- vendor libuvc v0.0.7 + libusb v1.0.27 源码进仓库，CMake 构建，自写薄 JNI（约 200 行）。
- 删除 `CameraController` 与 CAMERA 运行时权限，新增 `UvcController`，监听接口更名 `StreamListener`。
- MJPEG 解码用平台 `BitmapFactory`，渲染复用现有 SurfaceView/DisplayView/ScreenFit。

## 备选方案

1. **继续 Camera2 + 重试** —— 已证 HAL 层必挂，重试无法修复，放弃。
2. **UVCCamera 库（saki4510t）** —— 成熟但 2019 年起停止维护，体量大（含大量用不上的预览/音频功能），AGP 8/JDK 17/API 36 适配成本高。
3. **纯 Kotlin `bulkTransfer` 直读** —— 无 NDK 依赖，但 UVC 描述符解析、probe/commit、帧边界同步全部自写，1080p 分块读取性能无先例保障，风险最高。
4. **自研 native 栈（参考 app 方式）** —— 可行但重复造轮子；libuvc 已覆盖同样能力。

## 后果

正面：

- 绕过 CameraService/HAL，走参考 app 已验证的链路，不再受 ROM 相机过滤与 HAL 兼容性影响。
- 不需要 CAMERA 运行时权限，首次启动少一道授权。
- 依赖面最小：两个 C 库 + 一个薄 JNI。

负面：

- 引入 NDK/CMake 构建链与第三方 C 依赖（vendor 进仓库，版本锁定）。
- 初期仅支持 MJPEG、fps cap 30（USB 2.0 带宽风险，稳定优先）。
- USB 授权弹窗流程需自行管理（系统不提供"永久授权"语义，缓存仅限同设备）。
