# Borescope — project memory

Ad-free Android viewer for USB (UVC) borescopes. Kotlin/Compose app on top of
stock upstream **libusb** + **libuvc** (git submodules, no forks, no patches),
bridged with JNI. See README.md for layout and how the native path works.

## Build & verify

```bash
git submodule update --init --depth 1        # libusb v1.0.30, libuvc v0.0.8
./gradlew assembleDebug testDebugUnitTest lintDebug   # what CI runs
./gradlew assembleRelease                    # R8-minified; signed only if SIGNING_* env set
```

- Toolchain: JDK 17+, Gradle 8.14.3 (wrapper), AGP 8.9.1, Kotlin 2.1.20,
  compileSdk/targetSdk 36, **NDK 28.0.13004108**, **CMake 3.22.1**.
  NDK/CMake versions are duplicated in `uvc/build.gradle.kts`,
  `.github/actions/android-setup/action.yml` and `.claude/hooks/session-start.sh`:
  change all three together.
- Claude Code on the web: `.claude/hooks/session-start.sh` installs the SDK to
  `~/android-sdk` and exports `ANDROID_HOME`.
- Maven Central sometimes answers HTTP 429 in the web sandbox. If a build fails
  with `status code 429`, rerun with `--max-workers=1`. It's a network problem, not a code problem.
- No hardware in CI or the sandbox: anything touching real USB is verified by
  the user on a phone (`adb logcat -s uvc-native`).

## CI/CD (.github/)

- `workflows/ci.yml`: every push and PR runs build + unit tests + lint and
  uploads the debug APK and reports as artifacts. Green pushes to `main`
  replace the `nightly` pre-release with the latest debug APK.
- `workflows/release.yml`: pushing tag `vX.Y.Z` builds, tests and lints the
  release, then publishes a GitHub Release. The APK is signed when the
  repository secrets exist; otherwise only the debug APK is attached.
- `actions/android-setup`: shared JDK/Gradle/NDK setup and versionCode (= commit count).
- `dependabot.yml`: weekly Gradle + Actions bumps. Submodules are bumped by hand
  (update `libuvc_config.h` version macros when libuvc moves).

### Release signing

Repository secrets: `SIGNING_KEYSTORE_BASE64` (`base64 -w0 release.jks`),
`SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`.
`app/build.gradle.kts` reads `SIGNING_KEYSTORE_PATH` + those passwords from the environment.
Never commit a keystore.

## Conventions

- JNI surface lives in `UvcNative.kt` ↔ `uvc_jni.cpp`; keep signatures and the
  `FormatType` enum ↔ `UvcFormat.Type` ordinals in sync (`UvcFormatTest` guards the ordinals).
- R8 keep rules for the library go in `uvc/consumer-rules.pro`, not the app.
- Native teardown order matters (see `nativeClose`). Don't reorder it without a hot-unplug test.
- Pure logic goes behind interfaces so it can be unit-tested without a device.
- Only `CameraController` touches `CameraDevice`, and only from its actor. Never call camera
  methods from the UI, and never make a `FrameListener` wait on the camera thread (stopping
  a stream joins the frame thread, so that deadlocks).
- JNI returns format records as flat ints `[type, fourcc, width, height, fps]`; the stride
  is `UvcFormat.STRIDE` and must match `nativeGetFormats`.

## Game plan (keep this section current)

**Phase 0: build infrastructure** — DONE
- [x] Gradle wrapper, `compilerOptions` migration, consumer R8 rules in `uvc`
- [x] Unit-test setup (`UvcFormatTest`), CI build/test/lint, nightly + tagged releases
- [x] Env-based signing/versioning, Dependabot, web session-start hook

**Phase 1: first picture** — DONE in code, **not yet verified on hardware** (assumed MJPEG)
- [x] Descriptor dump (`uvc_print_diag`) logged on open and shown in the in-app info panel
- [x] YUYV → Bitmap fallback (`frame/Yuyv.kt`) for scopes without MJPEG
- [x] FOURCC check: `VS_FORMAT_UNCOMPRESSED` is YUYV only for `YUY2`, `VS_FORMAT_FRAME_BASED`
      is H.264 only for `H264`; everything else is `OTHER` and never auto-selected
- [ ] Narrow `device_filter.xml` to the scope's VID/PID (decimal) once known

**Phase 2: lifecycle and hot-unplug** — DONE in code, **not yet verified on hardware**
- [x] `camera/CameraController`: every camera op serialized on one "camera" thread; public
      methods only enqueue, so the main thread never blocks
- [x] States Idle / NoDevice / Connecting / NeedsPermission / Streaming / Error; start/stop
      races, stop during the permission dialog, unplug, replug all covered by `CameraControllerTest`
- [x] No permission-dialog loops: prompt at most once per start after a decline; attach
      events never prompt; `retry()` re-asks
- [x] Stall watchdog: no frames for 5 s → close + Error (never a frozen "live" picture)
- [x] `CameraSource`/`CameraDevice` interfaces + fakes; 30 controller tests

### Hardware verification checklist (do this with the real scope)
1. `adb logcat -s uvc-native` on first plug-in: the descriptor dump should list an MJPEG
   format. Paste it into this file under a new "Our scope" heading (VID:PID, formats,
   iso vs bulk, extension units). If there is no MJPEG, YUYV should still show a picture.
2. First light: picture appears; info panel (ⓘ) highlights the chosen mode.
3. Background/foreground 10× quickly: no crash, no second permission prompt, picture returns.
4. Pull the cable mid-stream 5×: no crash or ANR, "Plug in a USB borescope" appears;
   re-plug → "Open with Borescope?" → picture returns.
5. Decline permission once: "Grant USB permission" button, no dialog loop on resume.
6. Photo in MJPEG mode: file in Pictures/Borescope is byte-identical to a frame (no re-encode).
7. Leave it streaming 10 min: no stall error, memory steady (`adb shell dumpsys meminfo dev.borescope.app`).

**Phase 3: video recording** — DONE in code, **not yet verified on hardware**
- [x] `capture/VideoRecorder`: MediaCodec H.264 via input Surface + `lockHardwareCanvas`,
      drain thread → MediaMuxer → MediaStore Movies/Borescope (IS_PENDING until finished,
      deleted if empty); `VideoSpec` fits ≤1080p, 16-aligned, ~0.15 bpp (unit-tested)
- [x] Orientation hint from UI rotation; record button + REC timer; recording stops
      automatically when the stream ends (unplug, background, stall)
- Hardware check: record 30 s, play it back in Photos; unplug mid-recording → file is still valid

**Phase 4: polish and performance**
- [ ] Format picker, mirror, pinch zoom
- [ ] Fix rotation clipping at 90°/270° (`graphicsLayer` rotates after layout)
- [ ] `inBitmap` reuse; pooled direct ByteBuffers instead of one `NewByteArray` per frame
- [ ] App icon (lint `MissingApplicationIcon`)

**Phase 5: camera controls**
- [ ] Standard UVC controls via libuvc `uvc_get/set_*`
- [ ] LED control through the vendor extension unit, if the scope has one
