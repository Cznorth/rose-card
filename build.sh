#!/bin/bash
# 无 Gradle 构建 LSPosed 模块：javac(含桩) → d8(只打模块类) → aapt2 → zipalign → apksigner
set -e
cd "$(dirname "$0")"
SDK='C:\Users\cznorth\AppData\Local\Android\Sdk'
BT="$SDK\\build-tools\\36.1.0"
JAR="$SDK\\platforms\\android-35\\android.jar"
w() { cmd.exe /c "$@" 2>&1 | tr -d '\r'; }
rm -rf build && mkdir -p build/stubs build/classes build/dex
javac.exe -encoding UTF-8 --release 17 -cp "$JAR" -d build\\stubs $(cd stubs && find . -name '*.java' | sed 's|^\./|stubs\\|; s|/|\\|g')
javac.exe -encoding UTF-8 --release 17 -cp "$JAR;build\\stubs" -d build\\classes $(cd src && find . -name '*.java' | sed 's|^\./|src\\|; s|/|\\|g')
w "$BT\\d8.bat" --min-api 33 --lib "$JAR" --classpath build\\stubs --output build\\dex $(cd build/classes && find . -name '*.class' | sed 's|^\./|build\\classes\\|; s|/|\\|g')
"$(wslpath "$BT")/aapt2.exe" compile --dir res -o build\\res.zip
"$(wslpath "$BT")/aapt2.exe" link -I "$JAR" --manifest AndroidManifest.xml -A assets -o build\\unsigned.apk build\\res.zip
python3 -I -c 'import zipfile,sys; zipfile.ZipFile(sys.argv[1],"a",zipfile.ZIP_DEFLATED).write(sys.argv[2],"classes.dex")' build/unsigned.apk build/dex/classes.dex
"$(wslpath "$BT")/zipalign.exe" -f 4 build\\unsigned.apk build\\aligned.apk
w "$BT\\apksigner.bat" sign --ks 'C:\Users\cznorth\.android\debug.keystore' --ks-pass pass:android --out build\\rose-card.apk build\\aligned.apk
ls -la build/rose-card.apk
