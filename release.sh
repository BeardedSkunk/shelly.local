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
KEYSTORE="${KEYSTORE:-$HOME/pearlnode.p12}"

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
SIGNED="$SCRIPT_DIR/shelly.local-v$NEW_NAME.apk"

read -rsp "Keystore password: " KS_PASS
echo ""
echo "Signing..."
JAVA_TOOL_OPTIONS="--enable-native-access=ALL-UNNAMED" \
"$APKSIGNER" sign \
    --ks "$KEYSTORE" \
    --ks-key-alias pearlnode \
    --ks-pass "pass:$KS_PASS" \
    --v1-signing-enabled false \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --alignment-preserved \
    --out "$SIGNED" \
    "$UNSIGNED"
unset KS_PASS

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
