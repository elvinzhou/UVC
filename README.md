# Borescope

Ad-free Android viewer for USB (UVC) borescopes, built on stock upstream
**libusb** and **libuvc**. No forks, no patches.

## Layout

```
borescope/
├── app/                          # Compose UI app
│   └── src/main/
│       ├── AndroidManifest.xml   # USB_DEVICE_ATTACHED auto-launch
│       ├── res/xml/device_filter.xml
│       └── java/dev/borescope/app/
│           ├── MainActivity.kt
│           ├── ViewerViewModel.kt  # connect, frames -> Bitmap, snapshot
│           ├── ui/ViewerScreen.kt
│           └── capture/
│               ├── PhotoSaver.kt     # raw MJPEG frame -> MediaStore, lossless
│               └── VideoRecorder.kt  # TODO: MediaCodec H.264 + MediaMuxer
├── uvc/                          # Reusable UVC library module
│   └── src/main/
│       ├── java/dev/borescope/uvc/
│       │   ├── UsbCameraManager.kt  # discovery, permission, detach events
│       │   ├── UvcCamera.kt         # open / formats / start / stop / close
│       │   ├── UvcFormat.kt         # stream modes + FrameListener
│       │   └── UvcNative.kt         # JNI declarations
│       └── cpp/
│           ├── CMakeLists.txt       # builds libusb + libuvc from submodules
│           ├── uvc_jni.cpp          # JNI bridge (~250 lines)
│           └── config/libuvc/libuvc_config.h
└── third_party/                  # git submodules, pinned
    ├── libusb   @ v1.0.30
    └── libuvc   @ v0.0.8
```

## Setup

```bash
git submodule update --init --depth 1
```

Open the root folder in Android Studio, install **NDK 28.0.13004108** and
**CMake 3.22.1** from the SDK Manager, then run `app` on a phone with the
borescope attached through a USB-C OTG adapter. From the command line:

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug
```

## CI/CD

GitHub Actions builds, unit-tests and lints every push and PR, and uploads the
debug APK as an artifact. Each green push to `main` refreshes the **nightly**
pre-release. Pushing a `vX.Y.Z` tag publishes a GitHub Release (signed if the
`SIGNING_*` repository secrets are set, see `CLAUDE.md`).

Native logs: `adb logcat -s uvc-native`.

## How it works

1. `UsbCameraManager` finds a device with a Video-class interface and gets permission.
2. `UvcCamera.open` passes the `UsbDeviceConnection` file descriptor to native code.
3. Native code creates a libusb context with `LIBUSB_OPTION_NO_DEVICE_DISCOVERY`
   (apps can't enumerate `/dev/bus/usb`), wraps the fd with `uvc_wrap()`, and runs
   its own libusb event thread (libuvc only starts one when it owns the context).
4. Frames are delivered to `FrameListener.onFrame`. For MJPEG, each frame is a
   complete JPEG, decoded with `BitmapFactory` and saved verbatim for photos.

libuvc handles both isochronous and bulk cameras, so this path works either way.

## Before coding: inspect your scope

On a Mac/Linux machine: `lsusb -v -d VID:PID` (or System Information → USB on macOS). Note:
- **Formats**: `VS_FORMAT_MJPEG`, `VS_FORMAT_UNCOMPRESSED`, `VS_FORMAT_FRAME_BASED` (H.264)
- **Transfer type** of the video streaming endpoint: Isochronous or Bulk
- **Extension units** (`VC_EXTENSION_UNIT`): candidates for software LED control

Then add the vendor/product IDs (decimal) to `device_filter.xml`.

## Milestones

- [x] 0. Scaffold: native build, JNI bridge, Compose viewer, lossless snapshots;
      Gradle wrapper, unit tests, CI/CD
- [ ] 1. First light on a real device; fix whatever the scope's descriptors throw at us
- [ ] 2. Robust hot-unplug (teardown while streaming), reconnect without re-prompting
- [ ] 3. Video recording (`capture/VideoRecorder.kt`)
- [ ] 4. Format picker, mirror, zoom, `inBitmap` reuse for less GC
- [ ] 5. UVC controls; vendor extension unit for LEDs if applicable

## Known sharp edges

- `ViewerViewModel.connect()/disconnect()` aren't serialized yet; rapid
  background/foreground can race. Move camera ops onto a single-thread dispatcher.
- Pulling the cable mid-stream is the classic crash path. Test it early.
- Android's `jni.h` differs slightly from OpenJDK's (e.g. `AttachCurrentThread`
  takes `JNIEnv**`), so build the native code with the NDK, not a desktop JDK.
