#!/bin/bash
set -e

SKIP_BUMP=false
for arg in "$@"; do
    case "$arg" in
        --skip-bump) SKIP_BUMP=true ;;
        *) echo "Unknown option: $arg"; exit 1 ;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PKCS11_CFG="$SCRIPT_DIR/pkcs11.cfg"

# Read current version from build.gradle.kts
CURRENT_CODE=$(grep 'versionCode' "$SCRIPT_DIR/app/build.gradle.kts" | grep -v '//' | grep -oP '\d+')
CURRENT_NAME=$(grep 'versionName' "$SCRIPT_DIR/app/build.gradle.kts" | grep -oP '"[^"]+"' | tr -d '"')

echo "Current version: $CURRENT_NAME (code $CURRENT_CODE)"
echo ""

if $SKIP_BUMP; then
    NEW_NAME="$CURRENT_NAME"
    NEW_CODE="$CURRENT_CODE"
else
    read -rp "New versionName (e.g. 1.1): " NEW_NAME
    NEW_CODE=$((CURRENT_CODE + 1))
    echo "New versionCode: $NEW_CODE"
    echo ""

    CHANGELOG="$SCRIPT_DIR/fastlane/metadata/android/en-US/changelogs/$NEW_CODE.txt"
    if [ ! -f "$CHANGELOG" ]; then
        read -rp "Changelog (one line): " CHANGELOG_TEXT
        echo "$CHANGELOG_TEXT" > "$CHANGELOG"
    fi

    # Bump version in build.gradle.kts
    sed -i "s/versionCode = $CURRENT_CODE/versionCode = $NEW_CODE/" "$SCRIPT_DIR/app/build.gradle.kts"
    sed -i "s/versionName = \"$CURRENT_NAME\"/versionName = \"$NEW_NAME\"/" "$SCRIPT_DIR/app/build.gradle.kts"
fi

APKSIGNER="${APKSIGNER:-$HOME/Android/Sdk/build-tools/36.0.0/apksigner}"
if [ ! -f "$APKSIGNER" ]; then
    echo "Error: apksigner not found at $APKSIGNER"
    echo "Install Android SDK build-tools 36 and set APKSIGNER= if needed."
    exit 1
fi

echo "Building release APK..."
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
cd "$SCRIPT_DIR"
./gradlew assembleRelease

UNSIGNED="$SCRIPT_DIR/app/build/outputs/apk/release/app-release-unsigned.apk"
SIGNED="$SCRIPT_DIR/pearlnode-v$NEW_NAME.apk"

read -rsp "YubiKey PIN: " YUBIKEY_PIN
echo ""
echo "Signing..."
JAVA_TOOL_OPTIONS="--add-exports=jdk.crypto.cryptoki/sun.security.pkcs11=ALL-UNNAMED --enable-native-access=ALL-UNNAMED" \
"$APKSIGNER" sign \
    --provider-class sun.security.pkcs11.SunPKCS11 \
    --provider-arg "$PKCS11_CFG" \
    --ks NONE --ks-type PKCS11 \
    --ks-pass "pass:$YUBIKEY_PIN" \
    --ks-key-alias "X.509 Certificate for Digital Signature" \
    --out "$SIGNED" \
    "$UNSIGNED"
unset YUBIKEY_PIN

if ! $SKIP_BUMP; then
    echo "Committing and tagging..."
    git add app/build.gradle.kts fastlane/metadata/android/en-US/changelogs/
    git commit -m "Release v$NEW_NAME"
    git tag "v$NEW_NAME"
    git push origin main --tags
fi

echo ""
echo "Done. Now create a GitHub release for v$NEW_NAME and attach:"
echo "  $SIGNED"
