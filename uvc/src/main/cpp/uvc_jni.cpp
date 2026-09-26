// JNI bridge: Kotlin <-> libuvc/libusb.
//
// Lifecycle per camera:
//   nativeOpen(fd)  -> libusb context (no device discovery) + uvc_wrap(fd) + event thread
//   nativeStart(..) -> negotiate stream, frames delivered to FrameListener.onFrame
//   nativeStop()    -> stop streaming
//   nativeClose()   -> close device, stop event thread, free contexts
//
// Because we pass our own libusb context to uvc_init(), libuvc does NOT start its
// own event-handler thread. We run libusb_handle_events on our own thread instead.

#include <jni.h>
#include <android/log.h>
#include <pthread.h>

#include <atomic>
#include <mutex>
#include <thread>
#include <vector>

#include <libusb.h>
#include <libuvc/libuvc.h>

#define TAG "uvc-native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Must match UvcFormat.Type ordinals in Kotlin.
enum FormatType : jint { kMjpeg = 0, kYuyv = 1, kH264 = 2, kOther = 3 };

static JavaVM* g_vm = nullptr;
static pthread_key_t g_env_key;

// Frame callbacks arrive on libuvc's worker thread. Attach it to the JVM once and
// detach automatically when the thread exits (pthread key destructor).
static void DetachThread(void*) { if (g_vm) g_vm->DetachCurrentThread(); }

static JNIEnv* EnvForThisThread() {
  JNIEnv* env = nullptr;
  if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) return env;
  if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
  pthread_setspecific(g_env_key, env);
  return env;
}

struct Session {
  libusb_context* usb = nullptr;
  uvc_context_t* uvc = nullptr;
  uvc_device_handle_t* devh = nullptr;

  std::thread event_thread;
  std::atomic<bool> events_running{false};
  std::atomic<bool> streaming{false};

  std::mutex listener_mutex;
  jobject listener = nullptr;     // global ref to FrameListener
  jmethodID on_frame = nullptr;   // void onFrame(byte[] data, int w, int h, int type)
};

static Session* FromHandle(jlong h) { return reinterpret_cast<Session*>(h); }

static jint TypeFor(uvc_frame_format f) {
  switch (f) {
    case UVC_FRAME_FORMAT_MJPEG: return kMjpeg;
    case UVC_FRAME_FORMAT_YUYV:  return kYuyv;
    case UVC_FRAME_FORMAT_H264:  return kH264;
    default:                     return kOther;
  }
}

static void FrameCallback(uvc_frame_t* frame, void* user) {
  auto* s = static_cast<Session*>(user);
  if (!s->streaming.load() || frame->data_bytes == 0) return;

  JNIEnv* env = EnvForThisThread();
  if (!env) return;

  std::lock_guard<std::mutex> lock(s->listener_mutex);
  if (!s->listener) return;

  // TODO(perf): allocation per frame is fine for a first milestone at 30 fps.
  // Later: a small pool of direct ByteBuffers handed to Kotlin and returned after use.
  jbyteArray arr = env->NewByteArray(static_cast<jsize>(frame->data_bytes));
  if (!arr) { env->ExceptionClear(); return; }
  env->SetByteArrayRegion(arr, 0, static_cast<jsize>(frame->data_bytes),
                          static_cast<const jbyte*>(frame->data));
  env->CallVoidMethod(s->listener, s->on_frame, arr,
                      static_cast<jint>(frame->width), static_cast<jint>(frame->height),
                      TypeFor(frame->frame_format));
  if (env->ExceptionCheck()) {
    LOGE("Exception thrown from FrameListener.onFrame");
    env->ExceptionDescribe();
    env->ExceptionClear();
  }
  env->DeleteLocalRef(arr);
}

static void ClearListener(JNIEnv* env, Session* s) {
  std::lock_guard<std::mutex> lock(s->listener_mutex);
  if (s->listener) {
    env->DeleteGlobalRef(s->listener);
    s->listener = nullptr;
  }
}

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
  g_vm = vm;
  pthread_key_create(&g_env_key, DetachThread);
  return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL
Java_dev_borescope_uvc_UvcNative_nativeOpen(JNIEnv*, jclass, jint fd) {
  auto* s = new Session();

  // Android apps can't enumerate /dev/bus/usb, so disable discovery and wrap the
  // fd we got from UsbDeviceConnection instead.
  libusb_init_option opt{};
  opt.option = LIBUSB_OPTION_NO_DEVICE_DISCOVERY;
  int rc = libusb_init_context(&s->usb, &opt, 1);
  if (rc != LIBUSB_SUCCESS) {
    LOGE("libusb_init_context: %s", libusb_error_name(rc));
    delete s;
    return 0;
  }

  uvc_error_t err = uvc_init(&s->uvc, s->usb);
  if (err != UVC_SUCCESS) {
    LOGE("uvc_init: %s", uvc_strerror(err));
    libusb_exit(s->usb);
    delete s;
    return 0;
  }

  err = uvc_wrap(fd, s->uvc, &s->devh);
  if (err != UVC_SUCCESS) {
    LOGE("uvc_wrap: %s", uvc_strerror(err));
    uvc_exit(s->uvc);
    libusb_exit(s->usb);
    delete s;
    return 0;
  }

  s->events_running = true;
  s->event_thread = std::thread([s] {
    while (s->events_running.load()) {
      timeval tv{0, 100 * 1000};  // 100 ms, so shutdown is prompt
      libusb_handle_events_timeout_completed(s->usb, &tv, nullptr);
    }
  });

  LOGI("Opened UVC device on fd %d", fd);
  return reinterpret_cast<jlong>(s);
}

// Returns a flat IntArray: [type, width, height, fps] per frame descriptor.
JNIEXPORT jintArray JNICALL
Java_dev_borescope_uvc_UvcNative_nativeGetFormats(JNIEnv* env, jclass, jlong handle) {
  Session* s = FromHandle(handle);
  std::vector<jint> out;
  for (const uvc_format_desc_t* fmt = uvc_get_format_descs(s->devh); fmt; fmt = fmt->next) {
    jint type;
    switch (fmt->bDescriptorSubtype) {
      case UVC_VS_FORMAT_MJPEG:        type = kMjpeg; break;
      case UVC_VS_FORMAT_UNCOMPRESSED: type = kYuyv;  break;  // TODO: check GUID for non-YUYV raw
      case UVC_VS_FORMAT_FRAME_BASED:  type = kH264;  break;  // TODO: check GUID for H.264
      default:                         type = kOther; break;
    }
    for (const uvc_frame_desc_t* fr = fmt->frame_descs; fr; fr = fr->next) {
      uint32_t interval = fr->dwDefaultFrameInterval;
      jint fps = interval ? static_cast<jint>(10000000 / interval) : 0;
      out.insert(out.end(), {type, fr->wWidth, fr->wHeight, fps});
    }
  }
  jintArray arr = env->NewIntArray(static_cast<jsize>(out.size()));
  env->SetIntArrayRegion(arr, 0, static_cast<jsize>(out.size()), out.data());
  return arr;
}

// Returns 0 on success, a negative uvc_error_t otherwise.
JNIEXPORT jint JNICALL
Java_dev_borescope_uvc_UvcNative_nativeStart(JNIEnv* env, jclass, jlong handle, jint type,
                                             jint width, jint height, jint fps,
                                             jobject listener) {
  Session* s = FromHandle(handle);
  if (s->streaming.load()) return UVC_ERROR_BUSY;

  uvc_frame_format format;
  switch (type) {
    case kMjpeg: format = UVC_FRAME_FORMAT_MJPEG; break;
    case kYuyv:  format = UVC_FRAME_FORMAT_YUYV;  break;
    case kH264:  format = UVC_FRAME_FORMAT_H264;  break;
    default:     return UVC_ERROR_INVALID_PARAM;
  }

  uvc_stream_ctrl_t ctrl;
  uvc_error_t err = uvc_get_stream_ctrl_format_size(s->devh, &ctrl, format, width, height, fps);
  if (err != UVC_SUCCESS) {
    LOGE("uvc_get_stream_ctrl_format_size(%dx%d@%d): %s", width, height, fps, uvc_strerror(err));
    return err;
  }

  {
    std::lock_guard<std::mutex> lock(s->listener_mutex);
    s->listener = env->NewGlobalRef(listener);
    jclass cls = env->GetObjectClass(listener);
    s->on_frame = env->GetMethodID(cls, "onFrame", "([BIII)V");
    env->DeleteLocalRef(cls);
  }

  s->streaming = true;
  err = uvc_start_streaming(s->devh, &ctrl, FrameCallback, s, 0);
  if (err != UVC_SUCCESS) {
    LOGE("uvc_start_streaming: %s", uvc_strerror(err));
    s->streaming = false;
    ClearListener(env, s);
    return err;
  }
  LOGI("Streaming %dx%d@%d type=%d", width, height, fps, type);
  return 0;
}

JNIEXPORT void JNICALL
Java_dev_borescope_uvc_UvcNative_nativeStop(JNIEnv* env, jclass, jlong handle) {
  Session* s = FromHandle(handle);
  if (!s->streaming.exchange(false)) return;
  // Needs the event thread alive: cancelled transfers complete via libusb events.
  uvc_stop_streaming(s->devh);
  ClearListener(env, s);
}

JNIEXPORT void JNICALL
Java_dev_borescope_uvc_UvcNative_nativeClose(JNIEnv* env, jclass, jlong handle) {
  Session* s = FromHandle(handle);
  if (!s) return;
  // Teardown order matters (hot-unplug crashes live here):
  // 1) stop stream + close device while events still flow, 2) stop event thread,
  // 3) free libuvc context, 4) free libusb context.
  Java_dev_borescope_uvc_UvcNative_nativeStop(env, nullptr, handle);
  if (s->devh) uvc_close(s->devh);
  s->events_running = false;
  if (s->event_thread.joinable()) s->event_thread.join();
  if (s->uvc) uvc_exit(s->uvc);   // does not libusb_exit() a context it doesn't own
  if (s->usb) libusb_exit(s->usb);
  ClearListener(env, s);
  delete s;
  LOGI("Closed UVC device");
}

// TODO: standard controls (brightness, contrast, ...) via uvc_get/set_* in ctrl-gen.c
// TODO: vendor extension units (e.g. LED control) via uvc_get_ctrl / uvc_set_ctrl

}  // extern "C"
