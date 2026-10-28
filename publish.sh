#!/usr/bin/env bash
# 시드 없는(공개 안전) 릴리스 빌드 → 검증 → GitHub Release 게시. 사용: ./publish.sh
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-/home/box/sdk/jdk-17.0.21+9}"
REPO=onefeking-byte/tesla-garmin-companion
./gradlew --no-daemon clean assembleRelease verifySeedless -PseedFromKeys=false
VN=$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)
VC=$(grep -oP 'versionCode = \K[0-9]+' app/build.gradle.kts)
BC=$(find app/build/generated/source/buildConfig/release -name BuildConfig.java)
if grep -E 'SEED_' "$BC" | grep -vqE 'SEED_[A-Z_0-9]+ = (""|0L|false);'; then echo "ABORT: SEED_* 비어있지 않음"; exit 1; fi
APK=TeslaGarmin-companion-$VN.apk
DIST=dist; rm -rf "$DIST"; mkdir -p "$DIST"
cp app/build/outputs/apk/release/app-release.apk "$DIST/$APK"
# APK 내 비밀 흔적 검사 (값은 출력하지 않음)
TMP=$(mktemp -d); unzip -q -o "$DIST/$APK" -d "$TMP"
PAT='eyJ[A-Za-z0-9_-]{20,}|BEGIN (EC )?PRIVATE KEY|(5YJ|7SA|LRW|XP7)[A-HJ-NPR-Z0-9]{14}'
for f in $(find "$TMP" -name '*.dex'); do if strings "$f" | grep -qE "$PAT"; then echo "ABORT: APK에 비밀 의심 문자열"; rm -rf "$TMP"; exit 1; fi; done
for k in ~/tesla-keys/*refresh* ~/tesla-keys/*tokens*.json ~/tesla-keys/vin.txt; do [ -f "$k" ] || continue
  python3 - "$k" "$TMP" <<'PY' || { rm -rf "$TMP"; echo "ABORT: 키 파일 값이 APK에 존재"; exit 1; }
import sys,json,os,glob
p,d=sys.argv[1],sys.argv[2]; t=open(p).read()
try: j=json.loads(t); vals=[str(j.get(x,"")) for x in ("refresh_token","access_token")]
except Exception: vals=[t.strip()]
vals=[v for v in vals if len(v)>8]
blob=b"".join(open(f,"rb").read() for f in glob.glob(d+"/**/*",recursive=True) if os.path.isfile(f))
sys.exit(1 if any(v.encode() in blob for v in vals) else 0)
PY
done; rm -rf "$TMP"
SHA=$(sha256sum "$DIST/$APK" | cut -d' ' -f1)
URL="https://github.com/$REPO/releases/download/v$VN/$APK"
printf '{"versionCode":%s,"versionName":"%s","apk":"%s","sha256":"%s"}\n' "$VC" "$VN" "$URL" "$SHA" > "$DIST/version.json"
gh release create "v$VN" "$DIST/$APK" "$DIST/version.json" -R "$REPO" -t "v$VN" -n "Tesla Garmin companion $VN (seedless, versionCode $VC)"
echo "released v$VN"
