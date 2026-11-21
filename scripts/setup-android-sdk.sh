#!/usr/bin/env bash
# Android Studio 없이 CLI 로 빌드하기 위한 도구 설치 (사용자 홈 아래에만 설치, sudo 불필요)
#   - JDK 17        → ~/.local/jdk
#   - Android SDK   → ~/android-sdk  (platform 35, build-tools 35.0.0, platform-tools)
#   - Gradle 8.11.1 → ~/.local/gradle-8.11.1  (gradlew 생성용)
# 실행 중 Android SDK 라이선스에 동의하게 됩니다.
set -euo pipefail

JDK_DIR="$HOME/.local/jdk"
SDK_DIR="$HOME/android-sdk"
GRADLE_VER=8.11.1

# unzip 이 없는 환경도 있어 python 으로 압축 해제
unzip_to() { python3 -m zipfile -e "$1" "$2"; }

mkdir -p "$JDK_DIR" "$SDK_DIR/cmdline-tools"

if ! ls -d "$JDK_DIR"/jdk-17* >/dev/null 2>&1; then
  echo "[1/4] JDK 17 다운로드"
  curl -fsSL -o "$JDK_DIR/jdk.tgz" \
    "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
  tar xzf "$JDK_DIR/jdk.tgz" -C "$JDK_DIR" && rm "$JDK_DIR/jdk.tgz"
fi
export JAVA_HOME="$(ls -d "$JDK_DIR"/jdk-17* | head -1)"

if [ ! -x "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "[2/4] Android command-line tools 다운로드"
  curl -fsSL -o /tmp/ct.zip "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  unzip_to /tmp/ct.zip "$SDK_DIR/cmdline-tools" && rm /tmp/ct.zip
  mv "$SDK_DIR/cmdline-tools/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
  chmod +x "$SDK_DIR/cmdline-tools/latest/bin/"*
fi

echo "[3/4] SDK 라이선스 동의 + 패키지 설치"
(yes || true) | "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK_DIR" --licenses >/dev/null
"$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK_DIR" \
  "platforms;android-35" "build-tools;35.0.0" "platform-tools"

if [ ! -x "$HOME/.local/gradle-$GRADLE_VER/bin/gradle" ]; then
  echo "[4/4] Gradle $GRADLE_VER 다운로드"
  curl -fsSL -o /tmp/gradle.zip "https://services.gradle.org/distributions/gradle-$GRADLE_VER-bin.zip"
  unzip_to /tmp/gradle.zip "$HOME/.local" && rm /tmp/gradle.zip
  chmod +x "$HOME/.local/gradle-$GRADLE_VER/bin/gradle"
fi

echo
echo "완료. 아래를 ~/.bashrc 에 추가하면 편합니다:"
echo "  export JAVA_HOME=$JAVA_HOME"
echo "  export ANDROID_HOME=$SDK_DIR"
echo "  export PATH=\$JAVA_HOME/bin:\$ANDROID_HOME/platform-tools:\$PATH"
