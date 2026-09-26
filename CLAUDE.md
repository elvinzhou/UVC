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

## Game plan (keep this section current)

**Phase 0: build infrastructure** — DONE
- [x] Gradle wrapper, `compilerOptions` migration, consumer R8 rules in `uvc`
- [x] Unit-test setup (`UvcFormatTest`), CI build/test/lint, nightly + tagged releases
- [x] Env-based signing/versioning, Dependabot, web session-start hook

**Phase 1: first picture on real hardware** (blocked on the user's `lsusb -v` output and a device)
- [ ] Log all format/frame descriptors on open; add a debug screen that lists them
- [ ] YUYV → Bitmap fallback for scopes without MJPEG
- [ ] Check the GUID for `VS_FORMAT_UNCOMPRESSED` (it isn't always YUYV) and for
      `VS_FORMAT_FRAME_BASED` (it isn't always H.264) in `nativeGetFormats`
- [ ] Narrow `device_filter.xml` to the scope's VID/PID (decimal)

**Phase 2: lifecycle and hot-unplug** (most likely source of crashes)
- [ ] Run every camera operation on one dedicated single-thread dispatcher; nothing on the main thread
      (`nativeClose` joins threads, so it blocks the UI today)
- [ ] Explicit state machine Idle → Opening → Streaming → Closing; fixes:
      duplicate open from double `connect()`, a connect still in flight surviving `disconnect()`,
      and state still saying Streaming after `onStop`
- [ ] Cable pull mid-stream: make native teardown safe when the device is already gone
- [ ] Reconnect without re-prompting for permission
- [ ] `Camera` interface + fake for unit-testing the state machine

**Phase 3: video recording** (`capture/VideoRecorder.kt`)
- [ ] MediaCodec H.264 via input Surface + `lockHardwareCanvas`, MediaMuxer → MediaStore Movies/Borescope
- [ ] Orientation hint from UI rotation; record button

**Phase 4: polish and performance**
- [ ] Format picker, mirror, pinch zoom
- [ ] Fix rotation clipping at 90°/270° (`graphicsLayer` rotates after layout)
- [ ] `inBitmap` reuse; pooled direct ByteBuffers instead of one `NewByteArray` per frame
- [ ] App icon (lint `MissingApplicationIcon`)

**Phase 5: camera controls**
- [ ] Standard UVC controls via libuvc `uvc_get/set_*`
- [ ] LED control through the vendor extension unit, if the scope has one
