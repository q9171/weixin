#!/usr/bin/env bash
set -e
# d8/apksigner 的 .bat 依赖 JAVA_HOME，修正为有效 JDK 17
export JAVA_HOME="C:\\Program Files\\Java\\jdk-17.0.18"

# 项目根目录（脚本所在目录）
SRC="$(cd "$(dirname "$0")" && pwd)"
# 转为 Windows 路径（反斜杠），确保中文路径被正确传递给原生工具
SRC="$(cygpath -w "$SRC")"

# Android 构建工具(aapt2/zipalign/apksigner)对中文路径支持差，
# 因此先在 ASCII 临时目录里构建，最后把 APK 拷回项目目录。
TMP_RAW="C:/Users/21769/AppData/Local/Temp/roomchat_build_$(date +%s%N)"
mkdir -p "$TMP_RAW"
TMP="$(cygpath -w "$TMP_RAW")"
# robocopy 能正确处理中文源路径；成功返回码为 1，用 || true 防止 set -e 误中断
robocopy "$SRC" "$TMP" /E /COPY:DAT /R:1 /W:1 /NFL /NDL >/dev/null || true

ROOT="$TMP"
BT="D:/Android/sdk/build-tools/34.0.0"
PLAT="D:/Android/sdk/platforms/android-34"
SDK_JAR="$PLAT/android.jar"
APP="$ROOT/app"
SRCJ="$APP/src/main/java/com/workbuddy/roomchat/*.java"
RES="$APP/src/main/res"
MAN="$APP/src/main/AndroidManifest.xml"
ASSETS="$APP/src/main/assets"
BUILD="$ROOT/build"
GEN="$BUILD/gen"
CLS="$BUILD/classes"
DEXDIR="$BUILD/dex"
mkdir -p "$BUILD" "$GEN" "$CLS" "$DEXDIR"

# 版本号（发新版时改这里：VC=2 VN=1.0.1 bash build_apk.sh）
VC="${VC:-1}"
VN="${VN:-1.0.0}"

echo "[1/6] compile resources..."
"$BT/aapt2.exe" compile --dir "$RES" -o "$BUILD/res.zip"

echo "[2/6] link -> unsigned apk + R.java..."
"$BT/aapt2.exe" link -I "$SDK_JAR" --java "$GEN" \
  --min-sdk-version 21 --target-sdk-version 34 --version-code "$VC" --version-name "$VN" \
  --manifest "$MAN" -A "$ASSETS" -o "$BUILD/app-unsigned.apk" "$BUILD/res.zip"

echo "[3/6] javac compile..."
javac -encoding UTF-8 -d "$CLS" -cp "$SDK_JAR" "$GEN/com/workbuddy/roomchat/R.java" "$SRCJ"

echo "[4/6] d8 -> classes.dex..."
"$JAVA_HOME/bin/jar.exe" cf "$BUILD/classes.jar" -C "$CLS" .
"$BT/d8.bat" --lib "$SDK_JAR" --output "$DEXDIR" "$BUILD/classes.jar"

echo "[5/6] inject classes.dex into apk..."
"$ROOT/inject_dex.py" "$BUILD/app-unsigned.apk" "$DEXDIR/classes.dex"

echo "[6/6] zipalign + sign..."
"$BT/zipalign.exe" -f -p 4 "$BUILD/app-unsigned.apk" "$BUILD/app-aligned.apk"
"$BT/apksigner.bat" sign --ks "$ROOT/debug.keystore" --ks-key-alias androiddebugkey \
  --ks-pass pass:android --key-pass pass:android --out "$ROOT/app-debug.apk" "$BUILD/app-aligned.apk"

# 把成品 APK 拷回项目目录（robocopy 支持中文目标路径）
robocopy "$ROOT" "$SRC" app-debug.apk /COPY:DAT /R:1 /W:1 /NFL /NDL >/dev/null || true

echo "DONE -> $SRC/app-debug.apk"
ls -la "$SRC/app-debug.apk"
