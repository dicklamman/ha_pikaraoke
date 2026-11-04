#!/bin/sh
# Build the PiKaraoke player for Android.
#
# The APK is signed with the keystore in this directory, so that every build
# can be installed over an earlier one: Android refuses an update whose
# signature differs from the installed app's.
#
# Needs a JDK (17 or newer) and the Android SDK. Point ANDROID_HOME at the SDK,
# or set ANDROID_SDK_ROOT, and the build will find it.

set -eu

cd "$(dirname "$0")"

# The SDK location is written to local.properties by the Android tooling, and
# that file is machine specific, so it is written here rather than committed.
if [ -n "${ANDROID_HOME:-}" ]; then
    sdk_dir="${ANDROID_HOME}"
elif [ -n "${ANDROID_SDK_ROOT:-}" ]; then
    sdk_dir="${ANDROID_SDK_ROOT}"
else
    sdk_dir="${HOME}/Android/Sdk"
fi

if [ ! -d "${sdk_dir}" ]; then
    echo "The Android SDK was not found." >&2
    echo "Set ANDROID_HOME to the SDK directory and try again." >&2
    exit 1
fi

# Gradle wants forward slashes, and on Windows the SDK path comes with
# backslashes from the environment, which it reads as escape characters.
sdk_dir=$(printf '%s' "${sdk_dir}" | tr '\\' '/')
printf 'sdk.dir=%s\n' "${sdk_dir}" > local.properties

./gradlew --no-daemon :app:assembleRelease

apk="app/build/outputs/apk/release/app-release.apk"
if [ ! -f "${apk}" ]; then
    echo "The build did not produce an APK." >&2
    exit 1
fi

mkdir -p apk
cp "${apk}" "apk/piKaraoke.apk"

echo "Built apk/piKaraoke.apk"
