// uvc_jni.c —— 桩：仅验证 NDK/CMake 接线，后续任务替换为完整实现
#include <jni.h>

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
  (void)vm; (void)reserved;
  return JNI_VERSION_1_6;
}
