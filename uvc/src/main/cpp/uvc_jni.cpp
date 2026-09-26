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

#include <cstdio>
#include <cstdlib>
#include <cstring>

#include <atomic>
#include <mutex>
#include <string>
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
  jmethodID on_frame = nullptr;   // void onFrame(byte[] data, int length, int w, int h, int type)

  // One Java byte[] reused for every frame (grown when a frame doesn't fit),
  // instead of allocating per frame. Safe because onFrame is synchronous:
  // Kotlin must copy anything it keeps. Guarded by listener_mutex.
  jbyteArray frame_buf = nullptr;
  jsize frame_cap = 0;

  // VideoControl interface number, for class-specific control requests.
  int ctrl_interface = -1;
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

// Packs a 4-character code little-endian, e.g. "YUY2" -> 'Y' | 'U' << 8 | ...
// Kotlin unpacks it in UvcFormat.fourccToString.
static jint PackFourcc(const uint8_t* c) {
  return static_cast<jint>(c[0] | (c[1] << 8) | (c[2] << 16) | (static_cast<uint32_t>(c[3]) << 24));
}

// A format descriptor's subtype alone doesn't say what the pixels are:
// "uncompressed" may be YUY2, NV12, UYVY, ... and "frame based" may be H.264,
// H.265, MJPEG, ... Only the GUID (whose first 4 bytes are a FOURCC) tells.
static jint TypeForDescriptor(const uvc_format_desc_t* fmt) {
  switch (fmt->bDescriptorSubtype) {
    case UVC_VS_FORMAT_MJPEG:
      return kMjpeg;
    case UVC_VS_FORMAT_UNCOMPRESSED:
      return memcmp(fmt->fourccFormat, "YUY2", 4) == 0 ? kYuyv : kOther;
    case UVC_VS_FORMAT_FRAME_BASED:
      return memcmp(fmt->fourccFormat, "H264", 4) == 0 ? kH264 : kOther;
    default:
      return kOther;
  }
}

static jint FourccForDescriptor(const uvc_format_desc_t* fmt) {
  static const uint8_t kMjpg[4] = {'M', 'J', 'P', 'G'};
  switch (fmt->bDescriptorSubtype) {
    case UVC_VS_FORMAT_UNCOMPRESSED:
    case UVC_VS_FORMAT_FRAME_BASED:
      return PackFourcc(fmt->fourccFormat);
    case UVC_VS_FORMAT_MJPEG:
      return PackFourcc(kMjpg);
    default:
      return 0;
  }
}

// libuvc's human-readable descriptor dump. Device strings can hold arbitrary
// bytes, and NewStringUTF aborts on invalid modified UTF-8, so keep it ASCII.
static std::string DiagnosticsFor(uvc_device_handle_t* devh) {
  char* buf = nullptr;
  size_t len = 0;
  FILE* f = open_memstream(&buf, &len);
  if (!f) return "open_memstream failed";
  uvc_print_diag(devh, f);
  fclose(f);
  std::string out(buf ? buf : "", len);
  free(buf);
  for (char& c : out) {
    auto u = static_cast<unsigned char>(c);
    if (u >= 0x80 || (u < 0x20 && c != '\n' && c != '\t')) c = '?';
  }
  return out;
}

static void LogLines(const std::string& text) {
  size_t start = 0;
  while (start < text.size()) {
    size_t end = text.find('\n', start);
    if (end == std::string::npos) end = text.size();
    LOGI("%.*s", static_cast<int>(end - start), text.c_str() + start);
    start = end + 1;
  }
}

// The VideoControl interface (class 14, subclass 1) from the active configuration.
static int FindVideoControlInterface(uvc_device_handle_t* devh) {
  libusb_device* dev = libusb_get_device(uvc_get_libusb_handle(devh));
  libusb_config_descriptor* config = nullptr;
  if (libusb_get_active_config_descriptor(dev, &config) != LIBUSB_SUCCESS) return -1;
  int found = -1;
  for (int i = 0; i < config->bNumInterfaces && found < 0; ++i) {
    const libusb_interface& itf = config->interface[i];
    for (int a = 0; a < itf.num_altsetting; ++a) {
      const libusb_interface_descriptor& alt = itf.altsetting[a];
      if (alt.bInterfaceClass == LIBUSB_CLASS_VIDEO && alt.bInterfaceSubClass == 1) {
        found = alt.bInterfaceNumber;
        break;
      }
    }
  }
  libusb_free_config_descriptor(config);
  return found;
}

// A UVC class request to a unit's control. Same wire format as libuvc's
// uvc_get_ctrl/uvc_set_ctrl, but with a timeout: theirs waits forever, so a
// camera that never answers would hang the camera thread.
static int ControlRequest(Session* s, bool get, int unit, int selector, uint8_t* data, int len, int req) {
  if (s->ctrl_interface < 0) return UVC_ERROR_NOT_SUPPORTED;
  constexpr unsigned kTimeoutMs = 1000;
  return libusb_control_transfer(
      uvc_get_libusb_handle(s->devh),
      get ? 0xA1 : 0x21,   // class request to interface, device-to-host / host-to-device
      static_cast<uint8_t>(get ? req : UVC_SET_CUR),
      static_cast<uint16_t>(selector << 8),
      static_cast<uint16_t>((unit << 8) | s->ctrl_interface),
      data, static_cast<uint16_t>(len), kTimeoutMs);
}

static void FrameCallback(uvc_frame_t* frame, void* user) {
  auto* s = static_cast<Session*>(user);
  if (!s->streaming.load() || frame->data_bytes == 0) return;

  JNIEnv* env = EnvForThisThread();
  if (!env) return;

  std::lock_guard<std::mutex> lock(s->listener_mutex);
  if (!s->listener) return;

  auto length = static_cast<jsize>(frame->data_bytes);
  if (length > s->frame_cap) {
    if (s->frame_buf) env->DeleteGlobalRef(s->frame_buf);
    s->frame_buf = nullptr;
    s->frame_cap = 0;
    jsize capacity = length + length / 4;   // headroom: MJPEG frame sizes vary
    jbyteArray local = env->NewByteArray(capacity);
    if (!local) { env->ExceptionClear(); return; }
    s->frame_buf = static_cast<jbyteArray>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    if (!s->frame_buf) return;
    s->frame_cap = capacity;
  }
  env->SetByteArrayRegion(s->frame_buf, 0, length, static_cast<const jbyte*>(frame->data));
  env->CallVoidMethod(s->listener, s->on_frame, s->frame_buf, length,
                      static_cast<jint>(frame->width), static_cast<jint>(frame->height),
                      TypeFor(frame->frame_format));
  if (env->ExceptionCheck()) {
    LOGE("Exception thrown from FrameListener.onFrame");
    env->ExceptionDescribe();
    env->ExceptionClear();
  }
}

static void ClearListener(JNIEnv* env, Session* s) {
  std::lock_guard<std::mutex> lock(s->listener_mutex);
  if (s->listener) {
    env->DeleteGlobalRef(s->listener);
    s->listener = nullptr;
  }
  if (s->frame_buf) {
    env->DeleteGlobalRef(s->frame_buf);
    s->frame_buf = nullptr;
    s->frame_cap = 0;
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

  s->ctrl_interface = FindVideoControlInterface(s->devh);
  LOGI("Opened UVC device on fd %d (VideoControl interface %d)", fd, s->ctrl_interface);
  LogLines(DiagnosticsFor(s->devh));   // Phase 1: every descriptor, in logcat
  return reinterpret_cast<jlong>(s);
}

// Returns a flat IntArray: [type, fourcc, width, height, fps] per frame descriptor.
// Keep the stride in sync with UvcFormat.fromFlat.
JNIEXPORT jintArray JNICALL
Java_dev_borescope_uvc_UvcNative_nativeGetFormats(JNIEnv* env, jclass, jlong handle) {
  Session* s = FromHandle(handle);
  std::vector<jint> out;
  for (const uvc_format_desc_t* fmt = uvc_get_format_descs(s->devh); fmt; fmt = fmt->next) {
    jint type = TypeForDescriptor(fmt);
    jint fourcc = FourccForDescriptor(fmt);
    for (const uvc_frame_desc_t* fr = fmt->frame_descs; fr; fr = fr->next) {
      uint32_t interval = fr->dwDefaultFrameInterval;
      jint fps = interval ? static_cast<jint>(10000000 / interval) : 0;
      out.insert(out.end(), {type, fourcc, fr->wWidth, fr->wHeight, fps});
    }
  }
  jintArray arr = env->NewIntArray(static_cast<jsize>(out.size()));
  env->SetIntArrayRegion(arr, 0, static_cast<jsize>(out.size()), out.data());
  return arr;
}

JNIEXPORT jstring JNICALL
Java_dev_borescope_uvc_UvcNative_nativeGetDiagnostics(JNIEnv* env, jclass, jlong handle) {
  return env->NewStringUTF(DiagnosticsFor(FromHandle(handle)->devh).c_str());
}

// Processing units as flat longs: [unitId, bmControls] per unit.
JNIEXPORT jlongArray JNICALL
Java_dev_borescope_uvc_UvcNative_nativeGetProcessingUnits(JNIEnv* env, jclass, jlong handle) {
  std::vector<jlong> out;
  for (const uvc_processing_unit_t* pu = uvc_get_processing_units(FromHandle(handle)->devh); pu; pu = pu->next) {
    out.push_back(pu->bUnitID);
    out.push_back(static_cast<jlong>(pu->bmControls));
  }
  jlongArray arr = env->NewLongArray(static_cast<jsize>(out.size()));
  env->SetLongArrayRegion(arr, 0, static_cast<jsize>(out.size()), out.data());
  return arr;
}

// Extension units as 25-byte records: unitId, bmControls (8 bytes LE), GUID (16 bytes as in the descriptor).
JNIEXPORT jbyteArray JNICALL
Java_dev_borescope_uvc_UvcNative_nativeGetExtensionUnits(JNIEnv* env, jclass, jlong handle) {
  std::vector<jbyte> out;
  for (const uvc_extension_unit_t* xu = uvc_get_extension_units(FromHandle(handle)->devh); xu; xu = xu->next) {
    out.push_back(static_cast<jbyte>(xu->bUnitID));
    for (int i = 0; i < 8; ++i) out.push_back(static_cast<jbyte>((xu->bmControls >> (8 * i)) & 0xFF));
    for (int i = 0; i < 16; ++i) out.push_back(static_cast<jbyte>(xu->guidExtensionCode[i]));
  }
  jbyteArray arr = env->NewByteArray(static_cast<jsize>(out.size()));
  env->SetByteArrayRegion(arr, 0, static_cast<jsize>(out.size()), out.data());
  return arr;
}

// GET_* request for a 1-4 byte control. Returns the raw little-endian value as
// an unsigned number, or a negative libusb/uvc error.
JNIEXPORT jlong JNICALL
Java_dev_borescope_uvc_UvcNative_nativeGetCtrl(JNIEnv*, jclass, jlong handle, jint unit, jint selector,
                                               jint len, jint req) {
  if (len < 1 || len > 4) return UVC_ERROR_INVALID_PARAM;
  uint8_t buf[4] = {0, 0, 0, 0};
  int rc = ControlRequest(FromHandle(handle), true, unit, selector, buf, len, req);
  if (rc < 0) return rc;
  if (rc != len) return UVC_ERROR_OTHER;
  uint32_t value = 0;
  for (int i = 0; i < len; ++i) value |= static_cast<uint32_t>(buf[i]) << (8 * i);
  return static_cast<jlong>(value);
}

// SET_CUR for a 1-4 byte control. Returns 0 or a negative libusb/uvc error.
JNIEXPORT jint JNICALL
Java_dev_borescope_uvc_UvcNative_nativeSetCtrl(JNIEnv*, jclass, jlong handle, jint unit, jint selector,
                                               jint len, jint value) {
  if (len < 1 || len > 4) return UVC_ERROR_INVALID_PARAM;
  uint8_t buf[4];
  for (int i = 0; i < 4; ++i) buf[i] = static_cast<uint8_t>((static_cast<uint32_t>(value) >> (8 * i)) & 0xFF);
  int rc = ControlRequest(FromHandle(handle), false, unit, selector, buf, len, UVC_SET_CUR);
  return rc < 0 ? rc : 0;
}

// Raw GET_* for any control (e.g. vendor extension units). Length comes from GET_LEN.
// Returns null on error.
JNIEXPORT jbyteArray JNICALL
Java_dev_borescope_uvc_UvcNative_nativeGetCtrlBytes(JNIEnv* env, jclass, jlong handle, jint unit,
                                                    jint selector, jint req) {
  Session* s = FromHandle(handle);
  uint8_t len_buf[2] = {0, 0};
  if (ControlRequest(s, true, unit, selector, len_buf, 2, UVC_GET_LEN) != 2) return nullptr;
  int len = len_buf[0] | (len_buf[1] << 8);
  if (len <= 0) return nullptr;
  std::vector<uint8_t> data(static_cast<size_t>(len));
  int rc = ControlRequest(s, true, unit, selector, data.data(), len, req);
  if (rc < 0) return nullptr;
  jbyteArray arr = env->NewByteArray(rc);
  env->SetByteArrayRegion(arr, 0, rc, reinterpret_cast<const jbyte*>(data.data()));
  return arr;
}

// Raw SET_CUR for any control. Returns 0 or a negative libusb/uvc error.
JNIEXPORT jint JNICALL
Java_dev_borescope_uvc_UvcNative_nativeSetCtrlBytes(JNIEnv* env, jclass, jlong handle, jint unit,
                                                    jint selector, jbyteArray data) {
  jsize len = env->GetArrayLength(data);
  std::vector<uint8_t> buf(static_cast<size_t>(len));
  env->GetByteArrayRegion(data, 0, len, reinterpret_cast<jbyte*>(buf.data()));
  int rc = ControlRequest(FromHandle(handle), false, unit, selector, buf.data(), len, UVC_SET_CUR);
  return rc < 0 ? rc : 0;
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
    s->on_frame = env->GetMethodID(cls, "onFrame", "([BIIII)V");
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
  //
  // After a cable pull this is still safe: libusb sees POLLERR on the fd, completes
  // every in-flight transfer with LIBUSB_TRANSFER_NO_DEVICE on our event thread,
  // and libuvc frees them, so uvc_stop_streaming's wait returns. libuvc's status
  // (interrupt) transfer is not cancelled by uvc_close; libusb_close drops it from
  // its in-flight list under the event lock, and the fd is closed afterwards by
  // UsbDeviceConnection.close(), so it is never reaped.
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


}  // extern "C"
