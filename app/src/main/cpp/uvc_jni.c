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
// 需先于 libuvc.h：libuvc.h 中 uvc_wrap 声明以 #if LIBUSB_API_VERSION >= 0x01000107 守卫，
// 而 libuvc.h 自身不 include libusb（仅前向声明），先引入使该声明可见（vendored libusb 1.0.27 为 0x0100010A）
#include <libusb.h>
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
// v0.0.7 回调类型为 void(uvc_frame_t*, void*)（libuvc.h:493），无 devh 参数
static void frame_cb(uvc_frame_t *frame, void *user_ptr) {
  (void)user_ptr;
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
