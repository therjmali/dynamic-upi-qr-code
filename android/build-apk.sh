#!/usr/bin/env bash
# Builds a release of the Dynamic UPI QR Code Android app, signs it, and writes downloads/latest.json
# (the file installed apps read to offer "Update available").
# Needs: JDK 17+, aapt, zipalign, apksigner, dx (dalvik-exchange), android-23.jar
# Usage: VNAME=5.5 VCODE=55 KS=path/to/keystore KSPASS=password ./build-apk.sh
set -euo pipefail
cd "$(dirname "$0")"
VNAME="${VNAME:-5.5}"; VCODE="${VCODE:-55}"
NOTES="${NOTES:-Fixes the startup crash on Android 14 and newer|Safer start-up with on-screen crash details|Share a QR from Gallery, in-app updates, online and offline}"
SITE=".."; OUT="build"; JAR="${ANDROID_JAR:-/usr/share/java/com.android.android-23.jar}"
DX="${DX_JAR:-/usr/share/java/com.android.dx.jar}"
BASE="https://therjmali.github.io/dynamic-upi-qr-code/downloads"
rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/assets/www/fonts"
for f in index.html qrcode.js jsqr.js logos.js banks.js favicon.svg icon-180.png icon-192.png icon-512.png icon-maskable-512.png manifest.webmanifest NOTICE.txt; do cp "$SITE/$f" "$OUT/assets/www/"; done
cp "$SITE"/fonts/*.woff2 "$OUT/assets/www/fonts/"
javac -nowarn -source 8 -target 8 -bootclasspath "$JAR" -d "$OUT/classes" $(find src -name '*.java')
java -cp "$DX" com.android.dx.command.Main --dex --output="$OUT/classes.dex" "$OUT/classes"
aapt package -f -M AndroidManifest.xml -S res -A "$OUT/assets" -I "$JAR" -F "$OUT/app-unsigned.apk" \
  --min-sdk-version 29 --target-sdk-version 34 --version-code "$VCODE" --version-name "$VNAME"
( cd "$OUT" && aapt add app-unsigned.apk classes.dex >/dev/null )
zipalign -p -f 4 "$OUT/app-unsigned.apk" "$OUT/app-aligned.apk"
APK="$OUT/Dynamic-UPI-QR-Code-v$VNAME.apk"
apksigner sign --min-sdk-version 24 --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true --ks "$KS" --ks-pass "pass:$KSPASS" --out "$APK" "$OUT/app-aligned.apk"
apksigner verify -v "$APK"
mkdir -p "$SITE/downloads"
cp "$APK" "$SITE/downloads/Dynamic-UPI-QR-Code-v$VNAME.apk"; cp "$APK" "$SITE/downloads/Dynamic-UPI-QR-Code-latest.apk"
python3 - "$APK" "$VNAME" "$VCODE" "$BASE" "$NOTES" "$SITE/downloads/latest.json" <<'PY'
import sys,json,hashlib,os,datetime
apk,vn,vc,base,notes,out=sys.argv[1:7]
d=open(apk,"rb").read()
json.dump({"versionName":vn,"versionCode":int(vc),"url":"%s/Dynamic-UPI-QR-Code-v%s.apk"%(base,vn),"sha256":hashlib.sha256(d).hexdigest(),"size":len(d),"minAndroid":10,"notes":[n for n in notes.split("|") if n],"published":datetime.date.today().isoformat()},open(out,"w"),indent=2)
print("latest.json written for v"+vn)
PY
echo "Built: $APK"
