#!/usr/bin/env bash
# 小蚕哨兵 APK 构建脚本: aapt2 + javac + d8 + apksigner (无需 Gradle/AGP)
# H5 界面由 xc-uniapp (uni-app vite) 提供: 先在 xc-uniapp 里 `npm run build:h5`
# 注意: 必须绕过沙箱运行 (沙箱文件视图会造成 javac/aapt2 读写错层)
set -e
cd "$(dirname "$0")"

export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
BT=$ANDROID_HOME/build-tools/34.0.0
PLATFORM=$ANDROID_HOME/platforms/android-34/android.jar
OUT=build
H5_PROJECT="${H5_PROJECT:-../xc-uniapp}"
H5_SRC="$H5_PROJECT/dist/build/h5"
mkdir -p $OUT/classes $OUT/deps $OUT/assets

AFLAG=""
if [ -f "$H5_SRC/index.html" ]; then
  echo "== [0/6] 打包旧版 H5 资源 (可选) =="
  rm -rf "$OUT/assets/h5"
  mkdir -p "$OUT/assets/h5"
  cp -R "$H5_SRC/." "$OUT/assets/h5/"
  AFLAG="-A $OUT/assets"
else
  echo "== [0/6] 无 H5 资源, 跳过 (纯原生模式) =="
fi

echo "== [1/6] 检查依赖 jar =="
DEP() {
  if [ ! -f "$OUT/deps/$1" ]; then
    curl -fsSL --max-time 120 -o "$OUT/deps/$1" "$2" || \
    curl -fsSL --max-time 120 -x http://127.0.0.1:7897 -o "$OUT/deps/$1" "$2"
  fi
}
DEP okhttp-4.12.0.jar        "https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar"
DEP okio-jvm-3.6.0.jar       "https://repo1.maven.org/maven2/com/squareup/okio/okio-jvm/3.6.0/okio-jvm-3.6.0.jar"
DEP kotlin-stdlib-1.8.21.jar "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/1.8.21/kotlin-stdlib-1.8.21.jar"
DEP annotations-13.0.jar     "https://repo1.maven.org/maven2/org/jetbrains/annotations/13.0/annotations-13.0.jar"
CP="$OUT/deps/okhttp-4.12.0.jar:$OUT/deps/okio-jvm-3.6.0.jar:$OUT/deps/kotlin-stdlib-1.8.21.jar:$OUT/deps/annotations-13.0.jar"

echo "== [2/6] aapt2 编译资源 =="
$BT/aapt2 compile --dir res -o $OUT/res.zip

echo "== [3/6] aapt2 链接 (含 H5 assets) =="
$BT/aapt2 link -o $OUT/base.apk -I "$PLATFORM" --manifest AndroidManifest.xml -R $OUT/res.zip $AFLAG --java $OUT/gen --auto-add-overlay --min-sdk-version 26 --target-sdk-version 34 --min-sdk-version 26 --target-sdk-version 34

echo "== [4/6] javac 编译 =="
javac --release 17 -encoding UTF-8 -nowarn -cp "$PLATFORM:$CP" -d $OUT/classes \
  $OUT/gen/com/xiaocan/sentinel/R.java \
  src/com/xiaocan/sentinel/*.java

echo "== [5/6] d8 dex 化 =="
$BT/d8 --release --lib "$PLATFORM" --min-api 26 \
  --output $OUT \
  $(find $OUT/classes -name '*.class') \
  $OUT/deps/okhttp-4.12.0.jar $OUT/deps/okio-jvm-3.6.0.jar $OUT/deps/kotlin-stdlib-1.8.21.jar

echo "== [6/6] 打包 + 对齐 + 签名 =="
cp $OUT/base.apk $OUT/unsigned.apk
cd $OUT && zip -qj unsigned.apk classes.dex && cd ..
if [ ! -f $OUT/debug.keystore ]; then
  keytool -genkeypair -keystore $OUT/debug.keystore -storepass sentinel -keypass sentinel \
    -alias xc -dname "CN=xc-sentinel" -keyalg RSA -validity 10000 >/dev/null 2>&1
fi
$BT/zipalign -f 4 $OUT/unsigned.apk $OUT/aligned.apk
$BT/apksigner sign --ks $OUT/debug.keystore --ks-pass pass:sentinel \
  --out $OUT/xc-sentinel.apk $OUT/aligned.apk
$BT/apksigner verify $OUT/xc-sentinel.apk && echo "✅ 构建成功: $OUT/xc-sentinel.apk ($(ls -la $OUT/xc-sentinel.apk | awk '{print $5}') bytes)"
