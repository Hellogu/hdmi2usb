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
