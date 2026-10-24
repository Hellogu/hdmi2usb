# hdmi2usb libuvc 直读采集卡 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用 USB Host + 自集成 libuvc（MJPEG）+ BitmapFactory 解码替换已失败的 Camera2 路线，实现 PC HDMI → UVC 采集卡 → Android 平板副屏显示。

**Architecture:** UsbManager 授权 → UsbDeviceConnection fd → libuvc `uvc_wrap`（内部 libusb_wrap_sys_device 注入 fd）→ MJPEG 帧回调 JNI byte[] → 渲染线程 conflate（只留最新帧）→ BitmapFactory(inBitmap) 解码 → Canvas 全幅绘制到 SurfaceView。FIT/STRETCH 由 DisplayView 布局层承担。

**Tech Stack:** Kotlin（minSdk/targetSdk 36）、NDK r27 + CMake 3.22.1、vendored libuvc v0.0.7（JPEG 解码关闭）+ libusb v1.0.27（android/config.h + Linux usbfs 后端直编）、JVM JUnit 单测。

**设计文档:** [2026-10-24-hdmi2usb-libuvc-design.md](../specs/2026-10-24-hdmi2usb-libuvc-design.md)（含 13 项评审决议）

**关键环境事实（本机已验证）：**
- Windows PowerShell 5，**不支持 `&&` 链式命令**，逐条执行。
- `JAVA_HOME` 设为 `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot` 后再跑 gradle/sdkmanager。
- SDK 路径 `C:/Users/tianfeng/AppData/Local/Android/Sdk`，**当前无 NDK、无 CMake**（Task 1 安装）。
- git 提交统一用 `git -c user.name="tianfeng" -c user.email="tianfeng@local"`。
- 真机 2410CRP4CC 无线 ADB `192.168.123.240:39511`；**adb 命令常返回 exit code -1 但实际已执行，以输出内容为准，勿看退出码**。
- 构建验证命令：`.\gradlew.bat assembleDebug`（编译）与 `.\gradlew.bat testDebugUnitTest`（单测）。

**已验证的 vendor 库集成事实（写代码前已逐一核对上游 v0.0.7 / v1.0.27 源码）：**
1. libuvc v0.0.7 **自带公共 API `uvc_wrap(int sys_dev, uvc_context_t *context, uvc_device_handle_t **devh)`**（`src/device.c`，`LIBUSB_API_VERSION >= 0x01000107` 时编译；libusb 1.0.27 满足）。内部走 `libusb_wrap_sys_device`，fd 由 Kotlin 侧 `UsbDeviceConnection.fileDescriptor` 提供，`uvc_close` 不会关 fd（fd 归 Kotlin 的 connection 管）。**无需修改任何 vendor 源码。**
2. libuvc JPEG 解码关闭方式：不编 `src/frame-mjpeg.c`、不定义 `LIBUVC_HAS_JPEG`。`frame.c` 的 MJPEG 转换路径被 `#ifdef LIBUVC_HAS_JPEG` 包裹（已核对 frame.c:378），无 JPEG 时正常编译且回调交付**原始 MJPEG 字节**。`libuvc.h` 第 7 行 `#include <libuvc/libuvc_config.h>` → 必须用 CMake `configure_file` 从 vendored 模板生成该头（版本号宏来自它）。
3. libusb v1.0.27 **无 CMakeLists**（1.0.28 才引入），官方 Android 构建路径是仓库自带 `android/config.h`：直编 core/descriptor/hotplug/io/strerror/sync + os/{linux_usbfs,linux_netlink,events_posix,threads_posix}.c，加 `-DHAVE_CONFIG_H` 且 include 目录指向 vendored `android/`（`libusbi.h` 第 18 行 `#include <config.h>`）。netlink 热插拔（config.h 无 HAVE_LIBUDEV）。
4. 格式枚举走内部头（v0.0.7 无公共枚举 API）：`#include <libuvc/libuvc_internal.h>` 后遍历 `devh->info->stream_ifs` → `stream_if->format_descs`（`bDescriptorSubtype == UVC_VS_FORMAT_MJPEG(0x06)`）→ `frame->frame_descs` 链；帧率取最小 interval（100ns 单位）：离散型用零终止数组 `frame->intervals`（条数 `bFrameIntervalType`），连续型用 `dwMinFrameInterval`。
5. 取流 API：`uvc_get_stream_ctrl_format_size(devh, &ctrl, UVC_FRAME_FORMAT_MJPEG, w, h, fps)`（直接收 fps）→ `uvc_start_streaming(devh, &ctrl, cb, user_ptr, 0)`（flags=0，libuvc 自起线程回调）→ `uvc_stop_streaming(devh)` → `uvc_close(devh)` → `uvc_exit(ctx)`。
6. 关键结构体字段（已核对）：`uvc_frame_desc_t{wWidth, wHeight, dwMinFrameInterval, dwFrameIntervalStep, bFrameIntervalType, intervals}`、`uvc_format_desc_t{bDescriptorSubtype, frame_descs}`、`uvc_device_info_t{stream_ifs}`、`uvc_device_handle_t{info}`、`uvc_frame_t{data, data_bytes}`。

---

### Task 1: 环境准备——安装 NDK r27 与 CMake

**Files:** 无代码文件（只装工具链）。

- [ ] **Step 1: 定位 sdkmanager**

```powershell
Get-ChildItem "C:/Users/tianfeng/AppData/Local/Android/Sdk/cmdline-tools" -Directory | Select-Object -ExpandProperty Name
```

预期输出包含 `latest`（若还有旧版本号目录，用版本号目录路径）。

- [ ] **Step 2: 安装 NDK 27.2.12479018 与 CMake 3.22.1**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
& "C:/Users/tianfeng/AppData/Local/Android/Sdk/cmdline-tools/latest/bin/sdkmanager.bat" --install "ndk;27.2.12479018" "cmake;3.22.1"
```

预期：进度条下载约 2GB 后结束。若报 license 未接受，先执行下面这条并在提问时逐个输入 `y` 回车，然后重跑 Step 2：

```powershell
& "C:/Users/tianfeng/AppData/Local/Android/Sdk/cmdline-tools/latest/bin/sdkmanager.bat" --licenses
```

- [ ] **Step 3: 验证安装结果**

```powershell
Get-ChildItem "C:/Users/tianfeng/AppData/Local/Android/Sdk/ndk" | Select-Object -ExpandProperty Name
Get-ChildItem "C:/Users/tianfeng/AppData/Local/Android/Sdk/cmake" | Select-Object -ExpandProperty Name
```

预期：ndk 下有 `27.2.12479018`，cmake 下有 `3.22.1`。

- [ ] **Step 4: 无提交（纯环境操作）**

---

### Task 2: Vendor libuvc v0.0.7 与 libusb v1.0.27 源码

**Files:**
- Create: `app/src/main/cpp/third_party/libuvc/`（来自 libuvc v0.0.7 tarball）
- Create: `app/src/main/cpp/third_party/libusb/`（来自 libusb v1.0.27 tarball）

- [ ] **Step 1: 下载官方 release tarball 到临时目录**

```powershell
New-Item -ItemType Directory -Force -Path "$env:TEMP\vendor_dl"
curl.exe -L -o "$env:TEMP\vendor_dl\libuvc.tar.gz" https://github.com/libuvc/libuvc/archive/refs/tags/v0.0.7.tar.gz
curl.exe -L -o "$env:TEMP\vendor_dl\libusb.tar.bz2" https://github.com/libusb/libusb/releases/download/v1.0.27/libusb-1.0.27.tar.bz2
```

预期：两个文件下载成功（libuvc.tar.gz 约 200KB，libusb.tar.bz2 约 1MB）。

- [ ] **Step 2: 解压并就位**

```powershell
New-Item -ItemType Directory -Force -Path "app\src\main\cpp\third_party"
tar -xzf "$env:TEMP\vendor_dl\libuvc.tar.gz" -C "$env:TEMP\vendor_dl"
tar -xjf "$env:TEMP\vendor_dl\libusb.tar.bz2" -C "$env:TEMP\vendor_dl"
Move-Item "$env:TEMP\vendor_dl\libuvc-0.0.7" "app\src\main\cpp\third_party\libuvc"
Move-Item "$env:TEMP\vendor_dl\libusb-1.0.27" "app\src\main\cpp\third_party\libusb"
```

- [ ] **Step 3: 验证关键文件在位**

```powershell
Get-ChildItem app\src\main\cpp\third_party\libuvc\src\*.c | Select-Object -ExpandProperty Name
Get-ChildItem app\src\main\cpp\third_party\libusb\libusb\os\linux_*.c | Select-Object -ExpandProperty Name
Test-Path app\src\main\cpp\third_party\libusb\android\config.h
Test-Path app\src\main\cpp\third_party\libuvc\include\libuvc\libuvc_config.h.in
Test-Path app\src\main\cpp\third_party\libuvc\include\libuvc\utlist.h
```

预期：
- libuvc src 恰好 8 个 .c：`ctrl.c ctrl-gen.c device.c diag.c frame.c init.c stream.c misc.c`（注意**没有** `frame-mjpeg.c`——它在 tarball 里有，但我们不编它；列出来的是完整 tarball 内容，`frame-mjpeg.c` 若出现属正常，CMakeLists 不引用它即可）
- linux 系源文件含 `linux_netlink.c linux_usbfs.c`
- 三个 `Test-Path` 均输出 `True`

- [ ] **Step 4: Commit**

```powershell
git add app/src/main/cpp/third_party
git -c user.name="tianfeng" -c user.email="tianfeng@local" commit -m "feat: vendor libuvc v0.0.7 与 libusb v1.0.27 官方源码进仓库"
```

---

### Task 3: CMake + Gradle 接线（桩 uvc_jni.c 编译通过）

**Files:**
- Create: `app/src/main/cpp/CMakeLists.txt`
- Create: `app/src/main/cpp/uvc_jni.c`（本任务只放桩，Task 6 替换为完整实现）
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: 写 `app/src/main/cpp/CMakeLists.txt`**

```cmake
cmake_minimum_required(VERSION 3.22.1)
project(hdmi2usb LANGUAGES C)

# Vendor 来源与版本（third_party 下源码不做任何修改）：
# - libuvc  v0.0.7   https://github.com/libuvc/libuvc/archive/refs/tags/v0.0.7.tar.gz        BSD-2-Clause
# - libusb  v1.0.27  https://github.com/libusb/libusb/releases/download/v1.0.27/libusb-1.0.27.tar.bz2  LGPL-2.1
# MJPEG 解码交给平台 BitmapFactory：不编 frame-mjpeg.c、不定义 LIBUVC_HAS_JPEG，
# libuvc 帧回调交付原始 MJPEG 字节（frame.c 的转换路径由 #ifdef LIBUVC_HAS_JPEG 包裹）。

set(THIRD_PARTY ${CMAKE_CURRENT_SOURCE_DIR}/third_party)
set(LIBUVC_DIR ${THIRD_PARTY}/libuvc)
set(LIBUSB_DIR ${THIRD_PARTY}/libusb)

# ---- libusb 1.0.27（静态；Linux usbfs 后端 + netlink 热插拔；官方 android/config.h） ----
add_library(usb1 STATIC
  ${LIBUSB_DIR}/libusb/core.c
  ${LIBUSB_DIR}/libusb/descriptor.c
  ${LIBUSB_DIR}/libusb/hotplug.c
  ${LIBUSB_DIR}/libusb/io.c
  ${LIBUSB_DIR}/libusb/strerror.c
  ${LIBUSB_DIR}/libusb/sync.c
  ${LIBUSB_DIR}/libusb/os/linux_usbfs.c
  ${LIBUSB_DIR}/libusb/os/linux_netlink.c
  ${LIBUSB_DIR}/libusb/os/events_posix.c
  ${LIBUSB_DIR}/libusb/os/threads_posix.c)
target_include_directories(usb1
  PUBLIC ${LIBUSB_DIR}/libusb
  PRIVATE ${LIBUSB_DIR}/android)   # libusbi.h 无条件 #include <config.h> → 指向 vendored android/config.h
target_compile_definitions(usb1 PRIVATE HAVE_CONFIG_H)

# ---- libuvc 0.0.7（静态；JPEG 解码关闭） ----
# libuvc.h 无条件 #include <libuvc/libuvc_config.h> → 由 vendored 模板 configure_file 生成
set(LIBUVC_GEN ${CMAKE_CURRENT_BINARY_DIR}/gen)
configure_file(
  ${LIBUVC_DIR}/include/libuvc/libuvc_config.h.in
  ${LIBUVC_GEN}/libuvc/libuvc_config.h
  @ONLY)
add_library(uvc STATIC
  ${LIBUVC_DIR}/src/ctrl.c
  ${LIBUVC_DIR}/src/ctrl-gen.c
  ${LIBUVC_DIR}/src/device.c
  ${LIBUVC_DIR}/src/diag.c
  ${LIBUVC_DIR}/src/frame.c
  ${LIBUVC_DIR}/src/init.c
  ${LIBUVC_DIR}/src/stream.c
  ${LIBUVC_DIR}/src/misc.c)
target_include_directories(uvc
  PUBLIC ${LIBUVC_DIR}/include ${LIBUVC_GEN}
  PRIVATE ${LIBUSB_DIR}/libusb)
target_link_libraries(uvc PRIVATE usb1)

# ---- JNI 薄封装 ----
add_library(hdmi2usb_uvc SHARED uvc_jni.c)
target_link_libraries(hdmi2usb_uvc PRIVATE uvc usb1 log)
```

- [ ] **Step 2: 写桩 `app/src/main/cpp/uvc_jni.c`**

```c
// uvc_jni.c —— 桩：仅验证 NDK/CMake 接线，Task 6 替换为完整实现
#include <jni.h>

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
  (void)vm; (void)reserved;
  return JNI_VERSION_1_6;
}
```

- [ ] **Step 3: 修改 `app/build.gradle.kts`**

在 `android { }` 块中，`compileSdk = 36` 之后新增 ndkVersion，`defaultConfig` 内新增 ndk abiFilters，android 块内新增 externalNativeBuild。改后完整内容：

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hdmi2usb.monitor"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.hdmi2usb.monitor"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            abiFilters += "arm64-v8a"   // 目标设备 arm64
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
```

- [ ] **Step 4: 构建验证**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
.\gradlew.bat assembleDebug
```

预期：`BUILD SUCCESSFUL`。首次会跑 CMake 配置与编译（libuvc/libusb 约 10 个 C 文件）。若 CMake 报错缺文件，核对 Task 2 Step 3 的文件清单与 third_party 目录名拼写。

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/cpp/CMakeLists.txt app/src/main/cpp/uvc_jni.c app/build.gradle.kts
git -c user.name="tianfeng" -c user.email="tianfeng@local" commit -m "feat: NDK/CMake 构建接线，libuvc+libusb 静态编译通过（桩 JNI）"
```

---

### Task 4: UsbDevices.isVideo 共享设备判定（TDD）

**Files:**
- Create: `app/src/main/java/com/hdmi2usb/monitor/UsbDevices.kt`
- Create: `app/src/test/java/com/hdmi2usb/monitor/UsbDevicesTest.kt`
- Modify: `app/src/main/java/com/hdmi2usb/monitor/PlugMonitor.kt`

- [ ] **Step 1: 写失败测试 `app/src/test/java/com/hdmi2usb/monitor/UsbDevicesTest.kt`**

```kotlin
package com.hdmi2usb.monitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbDevicesTest {

    @Test
    fun `设备级 class 0x0E 判定为视频`() {
        assertTrue(UsbDevices.isVideo(0x0E, 0, emptyList()))
    }

    @Test
    fun `IAD 封装 0xEF 子类 2 判定为视频`() {
        assertTrue(UsbDevices.isVideo(0xEF, 2, emptyList()))
    }

    @Test
    fun `IAD 子类非 2 不判定为视频`() {
        assertFalse(UsbDevices.isVideo(0xEF, 3, emptyList()))
    }

    @Test
    fun `任一 interface 为 0x0E 判定为视频`() {
        assertTrue(UsbDevices.isVideo(0x00, 0, listOf(3, 0x0E)))
    }

    @Test
    fun `全无视频类不判定为视频`() {
        assertFalse(UsbDevices.isVideo(0x00, 0, listOf(3, 8)))
    }

    @Test
    fun `设备级非视频但接口为视频时判定为视频`() {
        assertTrue(UsbDevices.isVideo(0x00, 0, listOf(0x0E)))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
.\gradlew.bat testDebugUnitTest
```

预期：编译失败（`Unresolved reference: UsbDevices`）。

- [ ] **Step 3: 实现 `app/src/main/java/com/hdmi2usb/monitor/UsbDevices.kt`**

```kotlin
package com.hdmi2usb.monitor

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice

/** USB 视频设备判定：UvcController.findDevice 枚举与 PlugMonitor 拔卡广播共用，避免两处判定漂移。 */
object UsbDevices {

    fun isVideo(device: UsbDevice): Boolean =
        isVideo(
            device.deviceClass,
            device.deviceSubclass,
            List(device.interfaceCount) { device.getInterface(it).interfaceClass },
        )

    /** 纯函数判定（便于 JVM 单测）：设备 class 0x0E，或 IAD 封装（0xEF / subclass 2），或任一 interface 为 0x0E。 */
    fun isVideo(deviceClass: Int, deviceSubclass: Int, interfaceClasses: List<Int>): Boolean {
        if (deviceClass == UsbConstants.USB_CLASS_VIDEO) return true
        if (deviceClass == 0xEF && deviceSubclass == 2) return true
        return interfaceClasses.any { it == UsbConstants.USB_CLASS_VIDEO }
    }
}
```

- [ ] **Step 4: 重构 PlugMonitor 使用共享判定**

`app/src/main/java/com/hdmi2usb/monitor/PlugMonitor.kt` 整体替换为：

```kotlin
package com.hdmi2usb.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

/** 监听采集卡拔出；插入场景由 manifest 的 USB_DEVICE_ATTACHED intent-filter 承担。 */
class PlugMonitor(
    private val context: Context,
    private val onDetached: () -> Unit,
) {
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            val device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            if (device != null && UsbDevices.isVideo(device)) onDetached()
        }
    }

    private val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)

    fun register() {
        if (registered) return
        context.registerReceiver(receiver, filter)
        registered = true
    }

    fun unregister() {
        if (!registered) return
        context.unregisterReceiver(receiver)
        registered = false
    }
}
```

（删除了原文件里的私有扩展函数 `isVideoDevice()` 与 `UsbConstants` import，判定移入 UsbDevices。）

- [ ] **Step 5: 运行测试确认全绿**

```powershell
.\gradlew.bat testDebugUnitTest
```

预期：`BUILD SUCCESSFUL`，原有 11 个测试 + 新增 6 个全部通过。

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/hdmi2usb/monitor/UsbDevices.kt app/src/main/java/com/hdmi2usb/monitor/PlugMonitor.kt app/src/test/java/com/hdmi2usb/monitor/UsbDevicesTest.kt
git -c user.name="tianfeng" -c user.email="tianfeng@local" commit -m "feat: 提取 UsbDevices.isVideo 共享视频设备判定，PlugMonitor 改用之（TDD）"
```

---

### Task 5: UvcFormats 格式映射 + fps cap（TDD）

**Files:**
- Create: `app/src/main/java/com/hdmi2usb/monitor/UvcFormats.kt`
- Create: `app/src/test/java/com/hdmi2usb/monitor/UvcFormatsTest.kt`

- [ ] **Step 1: 写失败测试 `app/src/test/java/com/hdmi2usb/monitor/UvcFormatsTest.kt`**

```kotlin
package com.hdmi2usb.monitor

import android.graphics.ImageFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class UvcFormatsTest {

    @Test
    fun `空数组返回空列表`() {
        assertEquals(emptyList<StreamOption>(), UvcFormats.toStreamOptions(IntArray(0)))
    }

    @Test
    fun `长度非3倍数返回空列表`() {
        assertEquals(emptyList<StreamOption>(), UvcFormats.toStreamOptions(intArrayOf(1920, 1080)))
    }

    @Test
    fun `扁平数组按三元组映射为JPEG格式`() {
        val options = UvcFormats.toStreamOptions(intArrayOf(1920, 1080, 30, 1280, 720, 60))
        assertEquals(2, options.size)
        assertEquals(StreamOption(ImageFormat.JPEG, 1920, 1080, 30, 30), options[0])
        assertEquals(StreamOption(ImageFormat.JPEG, 1280, 720, 30, 30), options[1])
    }

    @Test
    fun `帧率默认cap到30`() {
        val options = UvcFormats.toStreamOptions(intArrayOf(1280, 720, 60))
        assertEquals(30, options[0].minFps)
        assertEquals(30, options[0].maxFps)
    }

    @Test
    fun `低于cap的帧率保持原值`() {
        val options = UvcFormats.toStreamOptions(intArrayOf(640, 480, 15))
        assertEquals(15, options[0].maxFps)
    }

    @Test
    fun `非法条目被过滤`() {
        val options = UvcFormats.toStreamOptions(
            intArrayOf(0, 1080, 30, 1280, 720, 0, 640, 480, 30),
        )
        assertEquals(1, options.size)
        assertEquals(640, options[0].width)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```powershell
.\gradlew.bat testDebugUnitTest
```

预期：编译失败（`Unresolved reference: UvcFormats`）。

- [ ] **Step 3: 实现 `app/src/main/java/com/hdmi2usb/monitor/UvcFormats.kt`**

```kotlin
package com.hdmi2usb.monitor

import android.graphics.ImageFormat

/**
 * UVC 格式描述符扁平数组 [w0,h0,fps0,w1,h1,fps1,...] → 候选流列表。
 * format 统一填 ImageFormat.JPEG（使 StreamSelector 的压缩优先排序生效）；
 * fps 以描述符最高帧率填 min=max 并 cap 到 [capFps]（USB 2.0 下 1080p60 有丢帧/花屏风险，稳定优先）。
 * 排序与超屏过滤交给 StreamSelector。
 */
object UvcFormats {

    const val FPS_CAP = 30

    fun toStreamOptions(flat: IntArray, capFps: Int = FPS_CAP): List<StreamOption> {
        if (flat.size % 3 != 0) return emptyList()
        val options = mutableListOf<StreamOption>()
        var i = 0
        while (i < flat.size) {
            val w = flat[i]
            val h = flat[i + 1]
            val fps = flat[i + 2]
            if (w > 0 && h > 0 && fps > 0) {
                val capped = if (fps > capFps) capFps else fps
                options += StreamOption(ImageFormat.JPEG, w, h, capped, capped)
            }
            i += 3
        }
        return options
    }
}
```

- [ ] **Step 4: 运行测试确认全绿**

```powershell
.\gradlew.bat testDebugUnitTest
```

预期：`BUILD SUCCESSFUL`，23 个测试全部通过。

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/hdmi2usb/monitor/UvcFormats.kt app/src/test/java/com/hdmi2usb/monitor/UvcFormatsTest.kt
git -c user.name="tianfeng" -c user.email="tianfeng@local" commit -m "feat: UVC 格式扁平数组映射 StreamOption 并 cap 帧率 30（TDD）"
```

---

### Task 6: uvc_jni.c 完整实现

**Files:**
- Modify: `app/src/main/cpp/uvc_jni.c`（整体替换 Task 3 的桩）

- [ ] **Step 1: 写完整实现**

```c
// uvc_jni.c —— USB 采集卡 MJPEG 取流 JNI 薄封装
//
// 依赖 vendored libuvc v0.0.7（JPEG 解码关闭，帧回调交付原始 MJPEG 字节）与 libusb v1.0.27。
// fd 注入：Kotlin 侧 UsbDeviceConnection.fileDescriptor → uvc_wrap（内部 libusb_wrap_sys_device）。
// 线程模型：
//   - uvc_start_streaming 由 libuvc 自起取流线程，帧回调在其线程上触发；
//   - 另起一个看门狗线程监测帧超时（USB 异常兜底，10 秒无帧回调 onStreamError）。
// 生命周期：nativeGetStreamFormats/nativeStart 内部惰性 uvc_init+uvc_wrap；
//          nativeStop 统一 uvc_stop_streaming → uvc_close → uvc_exit（fd 不在此关闭，归 Kotlin）。
#include <jni.h>
#include <android/log.h>
#include <libuvc/libuvc.h>
#include <libuvc/libuvc_internal.h>   // devh->info->stream_ifs / format_descs（v0.0.7 无公共枚举 API）
#include <pthread.h>
#include <time.h>
#include <unistd.h>
#include <string.h>

#define TAG "Hdmi2Usb"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define WATCHDOG_TIMEOUT_MS 10000   // 连续 10 秒无帧视为流异常
#define MAX_FORMATS 256             // 扁平数组容量上限（w,h,fps 三元组）

static JavaVM *g_vm = NULL;
static uvc_context_t *g_ctx = NULL;
static uvc_device_handle_t *g_devh = NULL;
static jobject g_thiz = NULL;                  // UvcController 实例的全局引用
static jmethodID g_mid_on_frame = NULL;        // onFrame([B)V
static jmethodID g_mid_on_stream_error = NULL; // onStreamError()V
static pthread_t g_watchdog;
static volatile int g_streaming = 0;
static volatile long g_last_frame_ms = 0;

static long now_ms(void) {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
  (void)reserved;
  g_vm = vm;
  return JNI_VERSION_1_6;
}

// ---- 帧回调（libuvc 取流线程） ----
static void frame_cb(uvc_device_handle_t *devh, uvc_frame_t *frame, void *user_ptr) {
  (void)devh; (void)user_ptr;
  JNIEnv *env;
  if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK) return;
  g_last_frame_ms = now_ms();
  if (frame == NULL || frame->data == NULL || frame->data_bytes == 0) return;
  // 每帧分配一个 jbyteArray 并拷贝一次（成本相对 BitmapFactory 解码可忽略）
  jbyteArray arr = (*env)->NewByteArray(env, (jsize)frame->data_bytes);
  if (arr == NULL) return;
  (*env)->SetByteArrayRegion(env, arr, 0, (jsize)frame->data_bytes, (const jbyte *)frame->data);
  (*env)->CallVoidMethod(env, g_thiz, g_mid_on_frame, arr);
  (*env)->DeleteLocalRef(env, arr);
}

// ---- 看门狗线程：帧超时 → onStreamError ----
static void *watchdog_main(void *arg) {
  (void)arg;
  JNIEnv *env;
  while (g_streaming) {
    usleep(1000 * 1000);   // 1 秒
    if (!g_streaming) break;
    long last = g_last_frame_ms;
    if (last > 0 && now_ms() - last > WATCHDOG_TIMEOUT_MS) {
      LOGW("stream watchdog: no frame for over %d ms", WATCHDOG_TIMEOUT_MS);
      if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) == JNI_OK) {
        (*env)->CallVoidMethod(env, g_thiz, g_mid_on_stream_error);
      }
      break;
    }
  }
  return NULL;
}

static uvc_error_t ensure_open(int fd) {
  if (g_devh != NULL) return UVC_SUCCESS;
  uvc_error_t r = uvc_init(&g_ctx, NULL);
  if (r != UVC_SUCCESS) {
    LOGE("uvc_init fail %d", r);
    return r;
  }
  r = uvc_wrap(fd, g_ctx, &g_devh);
  if (r != UVC_SUCCESS) {
    LOGE("uvc_wrap fail %d (fd=%d)", r, fd);
    uvc_exit(g_ctx);
    g_ctx = NULL;
    return r;
  }
  LOGD("uvc_wrap ok (fd=%d)", fd);
  return UVC_SUCCESS;
}

JNIEXPORT jintArray JNICALL
Java_com_hdmi2usb_monitor_UvcController_nativeGetStreamFormats(
    JNIEnv *env, jobject thiz, jint fd) {
  (void)thiz;
  uvc_error_t r = ensure_open(fd);
  if (r != UVC_SUCCESS) return NULL;

  jint buf[MAX_FORMATS];
  int n = 0;
  // 仅枚举 MJPEG 格式的帧描述符：[w0,h0,fps0,w1,h1,fps1,...]
  uvc_streaming_interface_t *stream_if;
  DL_FOREACH(g_devh->info->stream_ifs, stream_if) {
    uvc_format_desc_t *fmt;
    DL_FOREACH(stream_if->format_descs, fmt) {
      if (fmt->bDescriptorSubtype != UVC_VS_FORMAT_MJPEG) continue;
      uvc_frame_desc_t *frame;
      DL_FOREACH(fmt->frame_descs, frame) {
        // 帧率取最小 frame interval（100ns 单位）→ fps = 10^7 / interval
        uint32_t interval = 0;
        if (frame->bFrameIntervalType > 0) {
          // 离散型：intervals 为零终止数组，取最小值
          for (int i = 0; i < frame->bFrameIntervalType; i++) {
            if (frame->intervals[i] > 0 &&
                (interval == 0 || frame->intervals[i] < interval)) {
              interval = frame->intervals[i];
            }
          }
        } else if (frame->dwMinFrameInterval > 0) {
          // 连续型
          interval = frame->dwMinFrameInterval;
        }
        if (interval == 0 || n + 3 > MAX_FORMATS) continue;
        int fps = (int)(10000000u / interval);
        if (fps <= 0) continue;
        buf[n++] = (jint)frame->wWidth;
        buf[n++] = (jint)frame->wHeight;
        buf[n++] = fps;
      }
    }
  }

  LOGD("mjpeg formats: %d triples", n / 3);
  jintArray out = (*env)->NewIntArray(env, n);
  if (out == NULL) return NULL;
  if (n > 0) (*env)->SetIntArrayRegion(env, out, 0, n, buf);
  return out;
}

JNIEXPORT jboolean JNICALL
Java_com_hdmi2usb_monitor_UvcController_nativeStart(
    JNIEnv *env, jobject thiz, jint fd, jint width, jint height, jint fps) {
  uvc_error_t r = ensure_open(fd);
  if (r != UVC_SUCCESS) return JNI_FALSE;

  // 缓存回调目标实例与方法（JNI 可访问 Kotlin private 方法）
  if (g_thiz != NULL) {
    (*env)->DeleteGlobalRef(env, g_thiz);
    g_thiz = NULL;
  }
  jclass cls = (*env)->GetObjectClass(env, thiz);
  g_mid_on_frame = (*env)->GetMethodID(env, cls, "onFrame", "([B)V");
  g_mid_on_stream_error = (*env)->GetMethodID(env, cls, "onStreamError", "()V");
  g_thiz = (*env)->NewGlobalRef(env, thiz);
  (*env)->DeleteLocalRef(env, cls);
  if (g_mid_on_frame == NULL || g_mid_on_stream_error == NULL) {
    LOGE("callback method not found");
    return JNI_FALSE;
  }

  uvc_stream_ctrl_t ctrl;
  r = uvc_get_stream_ctrl_format_size(g_devh, &ctrl, UVC_FRAME_FORMAT_MJPEG,
                                      width, height, fps);
  if (r != UVC_SUCCESS) {
    LOGE("get_stream_ctrl fail %d (%dx%d@%d)", r, width, height, fps);
    return JNI_FALSE;
  }

  g_last_frame_ms = now_ms();
  g_streaming = 1;
  r = uvc_start_streaming(g_devh, &ctrl, frame_cb, NULL, 0);
  if (r != UVC_SUCCESS) {
    LOGE("start_streaming fail %d", r);
    g_streaming = 0;
    return JNI_FALSE;
  }
  pthread_create(&g_watchdog, NULL, watchdog_main, NULL);
  LOGD("streaming started %dx%d@%d", width, height, fps);
  return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_hdmi2usb_monitor_UvcController_nativeStop(JNIEnv *env, jobject thiz) {
  (void)thiz;
  g_streaming = 0;
  if (g_devh != NULL) {
    uvc_stop_streaming(g_devh);
    uvc_close(g_devh);
    g_devh = NULL;
  }
  if (g_ctx != NULL) {
    uvc_exit(g_ctx);
    g_ctx = NULL;
  }
  if (g_thiz != NULL) {
    (*env)->DeleteGlobalRef(env, g_thiz);
    g_thiz = NULL;
  }
  LOGD("native stopped");
}
```

- [ ] **Step 2: 构建验证**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
.\gradlew.bat assembleDebug
```

预期：`BUILD SUCCESSFUL`（Kotlin 侧 UvcController 尚不存在，JNI 符号在运行期才解析，编译不受影响）。

- [ ] **Step 3: Commit**

```powershell
git add app/src/main/cpp/uvc_jni.c
git -c user.name="tianfeng" -c user.email="tianfeng@local" commit -m "feat: uvc_jni 完整实现（fd 注入、MJPEG 枚举、取流、帧回调、看门狗）"
```

---

### Task 7: UvcController + StreamListener + FrameRenderer

**Files:**
- Create: `app/src/main/java/com/hdmi2usb/monitor/UvcController.kt`
- Create: `app/src/main/java/com/hdmi2usb/monitor/FrameRenderer.kt`

- [ ] **Step 1: 写 `app/src/main/java/com/hdmi2usb/monitor/FrameRenderer.kt`**

```kotlin
package com.hdmi2usb.monitor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.SurfaceHolder

/**
 * 渲染线程：conflate 背压（native 帧回调只更新最新帧槽，渲染慢时旧帧直接丢弃，延迟恒定）
 * + BitmapFactory(inBitmap 复用，尺寸变化时重建) 解码 + Canvas 全幅绘制。
 * FIT/STRETCH 完全由 DisplayView 布局层承担，这里一律画满当前 surface。
 */
class FrameRenderer(private val holder: SurfaceHolder) {

    private val thread = HandlerThread("RenderThread").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile private var pending: ByteArray? = null
    @Volatile private var stopped = false
    private var renderQueued = false
    private var bitmap: Bitmap? = null

    /** 帧提交（native 回调线程调用）：更新最新帧槽并确保渲染任务已排队。 */
    fun submit(frame: ByteArray) {
        if (stopped) return
        pending = frame
        synchronized(this) {
            if (renderQueued) return
            renderQueued = true
        }
        handler.post { render() }
    }

    private fun render() {
        synchronized(this) { renderQueued = false }
        val frame = pending
        pending = null
        if (stopped || frame == null) return
        val bmp = decode(frame) ?: return
        // surface 销毁期间 lockCanvas 返回 null：跳过绘制，Bitmap 保留供复用
        val canvas = holder.lockCanvas() ?: return
        try {
            canvas.drawBitmap(
                bmp,
                null,
                RectF(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat()),
                null,
            )
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun decode(frame: ByteArray): Bitmap? {
        val prev = bitmap
        if (prev != null && !prev.isRecycled) {
            try {
                val opts = BitmapFactory.Options().apply { inBitmap = prev }
                val reused = BitmapFactory.decodeByteArray(frame, 0, frame.size, opts)
                if (reused != null) return reused
            } catch (_: IllegalArgumentException) {
                // inBitmap 尺寸/配置不匹配（降级换档后）→ 走全新解码
            }
        }
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888  // lockCanvas 是软件画布，禁用 HARDWARE 位图
            inMutable = true                             // inBitmap 复用要求可变位图
        }
        val fresh = BitmapFactory.decodeByteArray(frame, 0, frame.size, opts)
        if (fresh != null && fresh !== prev) {
            prev?.recycle()
            bitmap = fresh
        }
        return fresh
    }

    fun stop() {
        stopped = true
        thread.quitSafely()
        bitmap?.recycle()
        bitmap = null
    }
}
```

- [ ] **Step 2: 写 `app/src/main/java/com/hdmi2usb/monitor/UvcController.kt`**

```kotlin
package com.hdmi2usb.monitor

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder

/** 流状态回调（主线程）。由 CameraListener 语义归位改名而来。 */
interface StreamListener {
    /** 流已就绪并开始渲染（主线程回调）。 */
    fun onStreamReady(width: Int, height: Int)
    /** 流不可用，应提示并退出（主线程回调）。 */
    fun onUnavailable()
}

class UvcController(context: Context, private val listener: StreamListener) {

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val workThread = HandlerThread("UvcThread").apply { start() }
    private val workHandler = Handler(workThread.looper)

    private var renderer: FrameRenderer? = null
    private var connection: UsbDeviceConnection? = null

    @Volatile private var released = false
    @Volatile private var streamStarted = false

    private var permissionRegistered = false
    private var onPermissionResult: ((Boolean) -> Unit)? = null

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            Log.d(TAG, "usb permission granted=$granted")
            onPermissionResult?.invoke(granted)
            onPermissionResult = null
        }
    }

    companion object {
        private const val TAG = "Hdmi2Usb"
        private const val ACTION_USB_PERMISSION = "com.hdmi2usb.monitor.USB_PERMISSION"

        init {
            System.loadLibrary("hdmi2usb_uvc")
        }
    }

    /** 遍历 USB 设备列表找视频设备（采集卡）。 */
    fun findDevice(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull { UsbDevices.isVideo(it) }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    /** 请求 USB 授权；结果一次性回调到主线程。等待期间不设超时，由调用方持续展示面板。 */
    @SuppressLint("WrongConstant")
    fun requestPermission(device: UsbDevice, onResult: (Boolean) -> Unit) {
        onPermissionResult = onResult
        if (!permissionRegistered) {
            appContext.registerReceiver(
                permissionReceiver,
                IntentFilter(ACTION_USB_PERMISSION),
                Context.RECEIVER_NOT_EXPORTED,
            )
            permissionRegistered = true
        }
        val pi = PendingIntent.getBroadcast(
            appContext,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName),
            PendingIntent.FLAG_IMMUTABLE,
        )
        usbManager.requestPermission(device, pi)
    }

    /**
     * 启动取流（前提：已获得 USB 授权）。
     * 枚举 MJPEG 格式 → StreamSelector 排序 → 逐候选 nativeStart 降级尝试，全失败才 onUnavailable。
     */
    fun start(device: UsbDevice, holder: SurfaceHolder) {
        workHandler.post { startInternal(device, holder) }
    }

    private fun startInternal(device: UsbDevice, holder: SurfaceHolder) {
        if (released || streamStarted) return
        val conn = usbManager.openDevice(device)
        if (conn == null) {
            Log.d(TAG, "openDevice null")
            postUnavailable()
            return
        }
        connection = conn
        val fd = conn.fileDescriptor

        val formats = nativeGetStreamFormats(fd)
        Log.d(TAG, "formats=${formats?.size?.div(3)} mjpeg triples")
        if (formats == null || formats.isEmpty()) {
            postUnavailable()
            return
        }

        val frame = holder.surfaceFrame
        val candidates = StreamSelector.select(
            UvcFormats.toStreamOptions(formats),
            frame.width(),
            frame.height(),
        )
        Log.d(TAG, "candidates=${candidates.size} surface=${frame.width()}x${frame.height()}")
        if (candidates.isEmpty()) {
            postUnavailable()
            return
        }

        for (candidate in candidates) {
            Log.d(TAG, "try ${candidate.width}x${candidate.height}@${candidate.maxFps}")
            if (nativeStart(fd, candidate.width, candidate.height, candidate.maxFps)) {
                streamStarted = true
                renderer = FrameRenderer(holder).also { it.start() }
                mainHandler.post {
                    listener.onStreamReady(candidate.width, candidate.height)
                }
                return
            }
        }
        postUnavailable()
    }

    private fun postUnavailable() {
        mainHandler.post { listener.onUnavailable() }
    }

    // ==== 以下两个方法由 native 回调（libuvc 取流线程 / 看门狗线程），勿改名 ====

    private fun onFrame(frame: ByteArray) {
        renderer?.submit(frame)
    }

    private fun onStreamError() {
        mainHandler.post { listener.onUnavailable() }
    }

    /** 唯一生命周期收口：停流 → 关连接 → 退渲染线程 → 状态复位。 */
    fun close() {
        if (released) return
        released = true
        try {
            if (streamStarted) nativeStop()
        } catch (_: Exception) {
        }
        streamStarted = false
        renderer?.stop()
        renderer = null
        try {
            connection?.close()   // uvc_close 不关 fd，fd 归这里管
        } catch (_: Exception) {
        }
        connection = null
        if (permissionRegistered) {
            try {
                appContext.unregisterReceiver(permissionReceiver)
            } catch (_: Exception) {
            }
            permissionRegistered = false
        }
        workThread.quitSafely()
    }

    private external fun nativeGetStreamFormats(fd: Int): IntArray?
    private external fun nativeStart(fd: Int, width: Int, height: Int, fps: Int): Boolean
    private external fun nativeStop()
}
```

- [ ] **Step 3: 构建验证**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
.\gradlew.bat assembleDebug
```

预期：`BUILD SUCCESSFUL`（MainActivity 仍引用旧 CameraController，本任务不动它）。

- [ ] **Step 4: Commit**

```powershell
git add app/src/main/java/com/hdmi2usb/monitor/UvcController.kt app/src/main/java/com/hdmi2usb/monitor/FrameRenderer.kt
git -c user.name="tianfeng" -c user.email="tianfeng@local" commit -m "feat: UvcController（授权/取流/逐候选降级）与 conflate 渲染线程"
```

---

### Task 8: MainActivity 改造 + Manifest + strings + device_filter + 删 CameraController

**Files:**
- Modify: `app/src/main/java/com/hdmi2usb/monitor/MainActivity.kt`（整体替换）
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/xml/device_filter.xml`
- Delete: `app/src/main/java/com/hdmi2usb/monitor/CameraController.kt`

- [ ] **Step 1: 整体替换 `MainActivity.kt`**

```kotlin
package com.hdmi2usb.monitor

import android.app.Activity
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity(), SurfaceHolder.Callback, StreamListener {

    private lateinit var displayView: DisplayView
    private lateinit var statusText: TextView
    private lateinit var permissionPanel: View
    private lateinit var permissionText: TextView
    private lateinit var permissionButton: Button

    private var controller: UvcController? = null
    private var plugMonitor: PlugMonitor? = null
    private var surfaceReady = false
    private var retryCount = 0
    private var streaming = false
    private var starting = false
    private var waitingPermission = false
    private var pendingDevice: UsbDevice? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val reopenRunnable = Runnable { tryOpenStream() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        // insetsController 依赖 DecorView，必须在 setContentView 之后获取
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        displayView = findViewById(R.id.display)
        statusText = findViewById(R.id.status_text)
        permissionPanel = findViewById(R.id.permission_panel)
        permissionText = findViewById(R.id.permission_text)
        permissionButton = findViewById(R.id.btn_grant)
        displayView.surfaceView.holder.addCallback(this)
        permissionButton.setOnClickListener { requestUsbPermission() }
        // 冷启动入口：可能是 launcher 直接打开，也可能带 EXTRA_DEVICE 的 attach intent
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask：app 存活时再次插入采集卡，重置重试并重新打开
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED && surfaceReady) {
            retryCount = 0
            handleIntent(intent)
        }
    }

    /** attach 直连：优先取 intent 的 EXTRA_DEVICE（仍经 isVideo 验证），没有才回退设备枚举。 */
    private fun handleIntent(intent: Intent?) {
        val extra = intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        if (extra != null && UsbDevices.isVideo(extra)) {
            pendingDevice = extra
        }
        startPipeline()
    }

    private fun startPipeline() {
        if (plugMonitor == null) plugMonitor = PlugMonitor(this) { onCaptureCardDetached() }
        plugMonitor?.register()
        if (controller == null) controller = UvcController(this, this)
        if (surfaceReady) tryOpenStream()
    }

    private fun tryOpenStream() {
        // streaming/starting/waitingPermission 三重守卫，防止重试回调/重复插卡/面板等待期重复 start
        if (streaming || starting || waitingPermission) return
        val c = controller ?: return
        val device = pendingDevice ?: c.findDevice()
        pendingDevice = device
        if (device == null) {
            if (retryCount < MAX_RETRIES) {
                retryCount++
                statusText.setText(R.string.no_capture_card)
                statusText.visibility = View.VISIBLE
                mainHandler.postDelayed(reopenRunnable, RETRY_INTERVAL_MS)
            } else {
                Toast.makeText(this, R.string.no_capture_card_exit, Toast.LENGTH_SHORT).show()
                finish()
            }
        } else {
            statusText.visibility = View.GONE
            mainHandler.removeCallbacks(reopenRunnable)
            if (c.hasPermission(device)) {
                starting = true
                c.start(device, displayView.surfaceView.holder)
            } else {
                requestUsbPermission()
            }
        }
    }

    /** 请求 USB 授权；拒绝时面板提示，不自动重弹，只等用户点按钮再请求。 */
    private fun requestUsbPermission() {
        if (waitingPermission) return
        val c = controller ?: return
        val device = pendingDevice ?: c.findDevice() ?: return
        waitingPermission = true
        permissionText.setText(R.string.permission_rationale)
        permissionButton.setText(R.string.retry_grant)
        permissionPanel.visibility = View.VISIBLE
        c.requestPermission(device) { granted ->
            waitingPermission = false
            if (granted) {
                permissionPanel.visibility = View.GONE
                if (streaming || starting) return@requestPermission
                starting = true
                c.start(device, displayView.surfaceView.holder)
            } else {
                permissionText.setText(R.string.permission_denied)
                permissionPanel.visibility = View.VISIBLE
            }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        if (controller != null) tryOpenStream()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // 旋转/缩放只改变 surface 尺寸，渲染层下一帧自然画到新尺寸，无需重启流
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
    }

    override fun onStreamReady(width: Int, height: Int) {
        starting = false
        streaming = true
        displayView.onStreamConfigured(width, height)
    }

    override fun onUnavailable() {
        starting = false
        streaming = false
        Toast.makeText(this, R.string.stream_unavailable, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun onCaptureCardDetached() {
        starting = false
        streaming = false
        waitingPermission = false
        controller?.close()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(reopenRunnable)
        plugMonitor?.unregister()
        controller?.close()
    }

    private companion object {
        const val MAX_RETRIES = 3
        const val RETRY_INTERVAL_MS = 2_000L
    }
}
```

- [ ] **Step 2: 替换 `app/src/main/AndroidManifest.xml`**

删除 CAMERA 权限与 camera uses-feature；新增 usb.host uses-feature；其余不动：

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-feature android:name="android.hardware.usb.host" android:required="true" />

    <application
        android:allowBackup="false"
        android:label="@string/app_name"
        android:theme="@style/Theme.Hdmi2Usb">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTask"
            android:configChanges="orientation|screenSize|screenLayout|keyboardHidden|smallestScreenSize|density">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.USB_DEVICE_ATTACHED" />
            </intent-filter>
            <meta-data
                android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
                android:resource="@xml/device_filter" />
        </activity>
    </application>
</manifest>
```

- [ ] **Step 3: 替换 `app/src/main/res/values/strings.xml`**

相机文案全部替换为 USB/流文案：

```xml
<resources>
    <string name="app_name">Hdmi2Usb</string>
    <string name="no_capture_card">未检测到采集卡…</string>
    <string name="no_capture_card_exit">未检测到采集卡，即将退出</string>
    <string name="stream_unavailable">视频流不可用（可能被占用）</string>
    <string name="permission_rationale">需要 USB 权限才能读取采集卡画面</string>
    <string name="permission_denied">USB 授权被拒绝，无法显示画面</string>
    <string name="retry_grant">重新授权</string>
</resources>
```

- [ ] **Step 4: 替换 `app/src/main/res/xml/device_filter.xml`**

补充 interface 级 UVC 匹配（常见廉价采集卡 device class 为 0x00、interface class 0x0E，仅靠设备级 class 匹配会导致插卡不自启）：

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- 设备级：UVC 视频控制类（class 0x0E）与 IAD 封装摄像头（0xEF / subclass 2） -->
    <usb-device class="14" />
    <usb-device class="239" subclass="2" />
    <!-- 接口级：device class 0x00、interface class 0x0E 的 UVC 设备 -->
    <usb-device>
        <usb-interface class="14" />
    </usb-device>
</resources>
```

- [ ] **Step 5: 删除 CameraController.kt**

```powershell
git rm app/src/main/java/com/hdmi2usb/monitor/CameraController.kt
```

（`CameraListener` 接口定义在该文件内，已由 StreamListener 取代，随文件一并消失。）

- [ ] **Step 6: 构建与单测**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
```

预期：两次均 `BUILD SUCCESSFUL`；无残留对 CameraController/CAMERA 的引用（若编译报 unresolved，检查 Step 1 是否整体替换到位）。

- [ ] **Step 7: Commit**

```powershell
git add app/src/main/java/com/hdmi2usb/monitor/MainActivity.kt app/src/main/AndroidManifest.xml app/src/main/res/values/strings.xml app/src/main/res/xml/device_filter.xml
git -c user.name="tianfeng" -c user.email="tianfeng@local" commit -m "feat: MainActivity 切换 UvcController 直连与 USB 授权流，删除 Camera2 残留"
```

---

### Task 9: 全量构建与单测验证

**Files:** 无新文件（验证任务）。

- [ ] **Step 1: 全量清理构建**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
.\gradlew.bat clean assembleDebug testDebugUnitTest
```

预期：`BUILD SUCCESSFUL`；单测 23 个全部通过（11 个旧 + 6 个 UsbDevices + 6 个 UvcFormats）。

- [ ] **Step 2: 确认 APK 产物**

```powershell
Get-ChildItem app\build\outputs\apk\debug\*.apk | Select-Object Name, Length
```

预期：存在 `app-debug.apk`。

- [ ] **Step 3: 检查工作区干净**

```powershell
git status
```

预期：无未提交改动（若有遗漏文件，补充提交）。

---

### Task 10: 真机验收（无线 ADB）

**Files:** 无新文件（验收任务）。

- [ ] **Step 1: 连接设备并安装**

```powershell
adb connect 192.168.123.240:39511
adb devices
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

注意：adb 命令可能报 exit code -1 但实际成功，**以输出文本为准**（`connected to ...` / `Success`）。

- [ ] **Step 2: 抓取应用日志**

```powershell
adb logcat -s Hdmi2Usb libuvc
```

保持该窗口运行，然后在平板上操作验收。PC 侧确认 HDMI 源在输出（采集卡有信号）。

- [ ] **Step 3: 逐项验收（对照设计文档验收清单）**

1. 首次打开 app → 出现 USB 授权弹窗 → 允许后出流（logcat：`uvc_wrap ok` → `streaming started WxH@30`）
2. 画面正常无花屏；双击切换 FIT/STRETCH（只重布局，不重启流——logcat 无重新 start）
3. 拔卡 → app 退出（logcat：`native stopped`）；重插 → 自启出流，且不重复弹授权窗
4. 拒绝授权 → 面板显示"USB 授权被拒绝"且不自动重弹；点"重新授权"按钮可再次请求
5. 采集卡未接时打开 app → "未检测到采集卡…"重试 3 次 → Toast 后退出
6. 退出 app 后重新进入可再次取流（logcat 无 fd 泄漏/占用报错）
7. 观察流畅度：1080p cap30 无卡顿花屏（若 60fps 实际可行，后续单独放开——不在本计划范围）

- [ ] **Step 4: 若验收有问题，按 logcat 定位修复后重跑 Task 9 + Task 10**

常见问题与对策：
- `uvc_wrap fail -3`（ACCESS）：确认 `startInternal` 里 `openDevice` 成功且 fd 有效；确认授权弹窗已允许。
- `get_stream_ctrl fail`：候选降级会自动尝试下一档；全部失败则核对采集卡是否只支持 YUV（设计仅 MJPEG）。
- 画面花屏：多为带宽不足导致丢帧，确认 cap 30 生效（logcat fps）。

---

## 计划自审记录

1. **Spec 覆盖**：设计文档 13 项决议逐条落位——自集成 vendor（Task 2）、uvc_wrap fd 注入（Task 6）、仅 MJPEG（Task 6 枚举过滤）、fps cap 30（Task 5）、StreamListener 改名（Task 7）、UsbDevices 共享判定（Task 4）、EXTRA_DEVICE 直连（Task 8 handleIntent）、BitmapFactory+inBitmap+conflate（Task 7 FrameRenderer）、DisplayView 布局层承担 FIT/STRETCH（Task 7 全幅绘制）、授权面板不自动重弹（Task 8 requestUsbPermission）、close 单一收口（Task 7 UvcController.close）、逐候选降级（Task 7 startInternal for 循环）、manifest/strings/device_filter（Task 8）、真机验收 7 项（Task 10）。
2. **占位符扫描**：所有代码步骤均给出完整代码与确切命令/预期输出；无 TBD/TODO。
3. **类型一致性**：`UvcFormats.toStreamOptions(IntArray, capFps)` 与 Task 7 调用一致；`UsbDevices.isVideo(UsbDevice)` 与 `isVideo(Int, Int, List<Int>)` 两态一致；JNI 方法名 `Java_com_hdmi2usb_monitor_UvcController_native*` 与 Kotlin `private external fun` 签名一一对应；回调方法名 `onFrame([B)V`/`onStreamError()V` 与 UvcController 私有方法一致；`stream_unavailable` 字符串已加入 strings.xml。
