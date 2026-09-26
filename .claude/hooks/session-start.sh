#!/bin/bash
# SessionStart hook for Claude Code on the web: installs the Android SDK/NDK,
# fetches the libusb/libuvc submodules and warms the Gradle cache so that
# ./gradlew builds, tests and lint work straight away. Idempotent.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

cd "$CLAUDE_PROJECT_DIR"

SDK="$HOME/android-sdk"
# Keep in sync with uvc/build.gradle.kts, app/build.gradle.kts and
# .github/actions/android-setup/action.yml.
PACKAGES=("platform-tools" "platforms;android-36" "build-tools;36.0.0" "ndk;28.0.13004108" "cmake;3.22.1")

if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "Installing Android command-line tools..." >&2
  mkdir -p "$SDK/cmdline-tools"
  tmp=$(mktemp -d)
  curl -sSL -o "$tmp/clt.zip" https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
  unzip -q "$tmp/clt.zip" -d "$tmp"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$tmp"
fi

missing=()
[ -d "$SDK/platforms/android-36" ] || missing+=("platforms;android-36")
[ -d "$SDK/build-tools/36.0.0" ] || missing+=("build-tools;36.0.0")
[ -d "$SDK/ndk/28.0.13004108" ] || missing+=("ndk;28.0.13004108")
[ -d "$SDK/cmake/3.22.1" ] || missing+=("cmake;3.22.1")
[ -d "$SDK/platform-tools" ] || missing+=("platform-tools")
if [ ${#missing[@]} -gt 0 ]; then
  echo "Installing SDK packages: ${missing[*]}" >&2
  yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null 2>&1 || true
  "$SDK/cmdline-tools/latest/bin/sdkmanager" "${PACKAGES[@]}" > /dev/null
fi

export ANDROID_HOME="$SDK"
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  echo "export ANDROID_HOME=\"$SDK\"" >> "$CLAUDE_ENV_FILE"
  echo "export ANDROID_SDK_ROOT=\"$SDK\"" >> "$CLAUDE_ENV_FILE"
fi

git submodule update --init --depth 1 >&2

# Warm the Gradle and Maven caches. Maven Central sometimes rate-limits this
# environment (HTTP 429), so retry with a single worker. A failure here must
# not block the session; the next ./gradlew call will retry the downloads.
for attempt in 1 2 3; do
  if ./gradlew --max-workers=1 --quiet testDebugUnitTest > /dev/null 2>&1; then
    echo "Gradle cache warm." >&2
    break
  fi
  echo "Gradle warm-up attempt $attempt failed; retrying..." >&2
  sleep $((attempt * 10))
done

exit 0
