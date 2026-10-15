#!/usr/bin/env bash
# Use the checked-in Gradle wrapper and the SDK/JDK configured on this machine.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
if [ -z "${JAVA_HOME:-}" ]; then
  if [ -d "/opt/homebrew/opt/openjdk@17" ]; then
    export JAVA_HOME="/opt/homebrew/opt/openjdk@17"
  elif [ -d "/opt/homebrew/opt/openjdk" ]; then
    export JAVA_HOME="/opt/homebrew/opt/openjdk"
  elif command -v /usr/libexec/java_home >/dev/null 2>&1 && /usr/libexec/java_home >/dev/null 2>&1; then
    export JAVA_HOME="$(/usr/libexec/java_home)"
  fi
fi

if [ -n "${JAVA_HOME:-}" ]; then
  export PATH="${JAVA_HOME}/bin:${PATH}"
fi

if [ -z "${ANDROID_HOME:-}" ] && [ -d "$HOME/Library/Android/sdk" ]; then
  export ANDROID_HOME="$HOME/Library/Android/sdk"
fi

./gradlew :app:assembleDebug "$@"
printf 'APK: app/build/outputs/apk/debug/app-debug.apk\n'
