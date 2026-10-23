# Hdmi2Usb 设计文档

日期：2026-10-23
状态：已与用户逐节确认

## 1. 概述

一个 Android 应用，把平板变成 PC 的扩展副屏。视频链路：

```
PC HDMI 输出 → UVC 视频采集卡 → USB（OTG）→ 安卓平板
```

App 的职责是一个**全屏低延迟 UVC 显示器**。PC 端不需要安装任何软件。

## 2. 需求

- 目标设备：Android 16+（API 36）平板。Android 16 起系统原生支持 UVC 插件摄像头，采集卡被识别为标准摄像头设备。
- 显示：全屏显示采集卡画面。
  - 默认等比缩放留黑边（FIT）；双击切换为拉伸铺满（STRETCH）；再双击切回。缩放状态不持久化，每次启动重置为 FIT。
- 附加行为：
  - 显示期间屏幕保持常亮（`FLAG_KEEP_SCREEN_ON`）。
  - 插入采集卡自动启动 app 并全屏显示；拔出采集卡自动关闭退出。
- 明确不做（Out of Scope）：音频（UAC）、触摸/键鼠回传、录制、截图、分辨率切换设置界面、WiFi 传输。

## 3. 技术选型

| 项 | 决策 | 理由 |
|---|---|---|
| 渲染 API | 裸 Camera2（方案 B） | 延迟最低；流格式/帧率可控；填充方式自由实现；无第三方依赖 |
| 渲染目标 | `SurfaceView` | 最低延迟合成路径；黑底 + 按宽高比定尺寸实现 FIT，`match_parent` 实现 STRETCH，无需矩阵变换 |
| 语言/构建 | Kotlin，Gradle Kotlin DSL | 项目标准 |
| minSdk / targetSdk | 36 / 36 | UVC 原生支持最低版本即目标平台 |
| 依赖 | 无第三方库 | YAGNI |

备选方案（已否决）：CameraX 快速路线（延迟与流控制妥协）；自研 UVC 协议栈/libuvc（Android 15 及以下才需要，此处多余）。

## 4. 架构

单模块 app，4 个组件：

```
MainActivity（唯一 Activity：全屏沉浸式、KEEP_SCREEN_ON、权限申请入口）
├── DisplayView      — SurfaceView 容器（黑底），持有当前缩放模式，
│                      依据流分辨率计算自身尺寸（FIT）或铺满（STRETCH）
├── CameraController — Camera2 封装
│     枚举 CameraManager → 过滤 LENS_FACING_EXTERNAL
│     → StreamSelector 选最优流配置 → 打开设备
│     → CaptureSession → Repeating Request 输出到 Surface
├── PlugMonitor      — USB 插拔监听
│     ATTACHED：manifest intent-filter（USB_DEVICE_ATTACHED + UVC 类别）拉起 Activity
│     DETACHED：运行时广播 → 关闭相机 → finish()
└── ScreenFit（纯函数）— 给定流分辨率与容器尺寸，输出 SurfaceView 布局参数
```

数据流：USB 插入 → Activity 启动 → CAMERA 权限 → CameraController 枚举并开流 → 帧直达 SurfaceView → 拔出广播 → 关闭 → finish()。

## 5. 关键设计决策

1. **流选择策略（StreamSelector，纯函数）**：数据驱动，从 `StreamConfigurationMap` 中选择：
   - 优先 MJPEG 流（采集卡 60fps 档位通常仅 MJPEG 可用）；
   - 分辨率不超过屏幕分辨率的前提下取最大；同分辨率取最高帧率；
   - 全部候选均超屏时不过滤，按超屏程度升序取最接近屏幕的档位。
2. **权限**：UVC 设备走相机栈，需要 `android.permission.CAMERA` 运行时权限，首次启动申请一次。
3. **方向**：不锁定方向，内容按比例适配当前窗口；副屏场景平板通常横放。
4. **缩放切换**：双击手势在 FIT ↔ STRETCH 间切换，实现为 DisplayView 重新布局，不重建相机会话。

## 6. 错误处理

| 场景 | 处理 |
|---|---|
| 拒绝 CAMERA 权限 | 显示说明页 + "重新授权"按钮，不进主界面 |
| 枚举不到 EXTERNAL 摄像头 | 提示"未检测到采集卡"，2 秒间隔自动重试，上限 3 次后退出 |
| 相机被占用（CameraAccessException / in use） | 提示后直接退出 |
| 会话中断（onDisconnected / onError） | onDisconnected 重开相机 1 次，再失败则退出；onError 直接退出 |
| 流协商失败 | 按兜底序列逐级降级重试 |

所有退出路径先同步关闭 CameraDevice/CaptureSession，再 finish()。

**已知限制**：屏灭/退后台后相机不停止：surfaceDestroyed 时未关闭相机（CameraController.close() 为终态，无法软停止）。若 HAL 对已 abandon 的 surface 报错，可能级联到 onUnavailable 导致后台 finish。后续如需支持后台常驻，应给 CameraController 增加 stop()/restart 能力后改为 surfaceDestroyed→stop、surfaceCreated→start。

## 7. 测试策略

硬件强绑定（需要真实采集卡），采用纯逻辑单测 + 人工验收：

- **单元测试（JUnit）**
  - `ScreenFit`：FIT/STRETCH 布局计算（横竖屏、不同宽高比边界）
  - `StreamSelector`：喂伪造的流配置数据，验证 MJPEG 优先、分辨率/帧率取舍、降级序列
- **人工验收清单**
  1. 插入采集卡 → app 自动启动并显示画面
  2. 双击 → FIT/STRETCH 切换正确、画面不崩溃
  3. 显示期间屏幕不熄灭
  4. 拔出采集卡 → 相机释放、app 退出、无崩溃
  5. 首次启动权限拒绝 → 说明页 → 重新授权 → 正常显示
  6. 断流/中断（拔 PC 端 HDMI）→ 自动恢复或干净退出
