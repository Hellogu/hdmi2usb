# Hdmi2Usb

把 PC、机顶盒、相机等 HDMI 信号源的画面，通过 UVC 采集卡实时显示在 Android 平板上，并支持一键录制。在 MS2130 采集卡 + 小米平板（HyperOS / Android 16）上开发与真机验证。

## 功能

- **低延迟取流**：USB Host + libuvc 直读采集卡（fd 注入，无需 root），MJPEG 逐帧回调，最高 1080p60
- **画质自适配**：按屏幕尺寸自动选择最佳档位；⚙ 菜单可手动锁定分辨率/帧率，失败自动回落
- **无信号常驻恢复**：HDMI 无信号不退出，每 2 秒自动重试等待信号源恢复；区分「无信号」与「设备被占用」两种提示
- **UAC 音频直通**：采集卡内置声卡的音频随视频自动从扬声器播出（RECORD_AUDIO 授权后生效）
- **一键录制**：MJPEG 直存 AVI（含 PCM 音轨，零转码），断流/拔卡/切画质/熄屏自动收尾保存；录制只写应用私有临时文件，收尾才入 MediaStore，崩溃不留损坏文件；保存至 `Movies/Hdmi2Usb/`
- **热插拔**：插卡自启、拔卡自动释放资源

## 硬件与系统要求

- Android 13+（API 33，`minSdk 33`；在 Android 16 平板与 Android 13 手机上验证）
- 支持 UVC MJPEG 的 USB 采集卡（MS2130 及兼容型号；其 HDMI 输入支持 4K，USB 输出最高 1080p60）
- 采集卡通过 OTG 连接平板（USB 口被采集卡占用时，调试请用无线 ADB）

## 构建

```powershell
.\gradlew.bat :app:assembleDebug
```

- 需要 Android Studio（内置 JBR 作为 JAVA_HOME）与 NDK + CMake（native 层编译）
- libuvc v0.0.7 与 libusb v1.0.27 以官方源码 vendored 进 `app/src/main/cpp/third_party/`，不依赖网络子模块

## 架构概要

| 层 | 实现 |
|----|------|
| 取流 | libuvc + libusb（JNI 包裹系统 USB fd，`NO_DEVICE_DISCOVERY` 绕过 SELinux 枚举限制） |
| 渲染 | 专用 HandlerThread：BitmapFactory（inBitmap 复用）+ Canvas → SurfaceView，conflate 背压保证恒定延迟 |
| 音频 | AudioRecord（USB 录音口，路由锁定）→ AudioTrack（内置扬声器，防回环）+ 可选 PCM 旁路 sink |
| 录制 | 视频/音频双有界队列（丢最旧，绝不反压采集）→ AviMuxer（占位头 + 实测帧率回填）→ MediaStore |

设计文档与决策记录见 [docs/](docs/)（ADR、设计规格、实现计划）。

## 许可证

- 本项目代码以 [MIT](LICENSE) 协议开源
- `third_party/` 内的 libuvc / libusb 保留其原始许可证（LGPL-2.1，见各目录 LICENSE 文件）
