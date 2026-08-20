#!/bin/bash
# ============================================================
# Resilient Release Build Script
# Runs via nohup — survives agent disconnections
# ============================================================
set -e

LOG="/workspace/anti/project/Untrusted-Translations-Android/build-release.log"
exec > >(tee -a "$LOG") 2>&1

echo "=========================================="
echo "BUILD STARTED: $(date)"
echo "=========================================="

export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME=/workspace/android-sdk
export ANDROID_SDK_ROOT=/workspace/android-sdk

PROJECT="/workspace/anti/project/Untrusted-Translations-Android"
DOWNLOAD="/sdcard/Download"

# --- Step 1: Ensure local.properties ---
echo "[1/7] Setting local.properties..."
echo "sdk.dir=$ANDROID_HOME" > "$PROJECT/local.properties"

# --- Step 2: Download and install CMake 3.31.6 if missing ---
if [ ! -d "$ANDROID_HOME/cmake/3.31.6/bin" ]; then
    echo "[2/7] Downloading CMake 3.31.6..."
    wget -q "https://dl.google.com/android/repository/cmake-3.31.6-linux.zip" -O /workspace/cmake.zip
    mkdir -p "$ANDROID_HOME/cmake/3.31.6"
    cd "$ANDROID_HOME/cmake/3.31.6"
    unzip -q -o /workspace/cmake.zip
    rm -f /workspace/cmake.zip
    echo "CMake installed"
else
    echo "[2/7] CMake already installed"
fi

# --- Step 3: Download and install NDK 29 if missing ---
if [ ! -d "$ANDROID_HOME/ndk/29.0.13113456" ]; then
    echo "[3/7] Downloading NDK r29 (~1.5GB, please wait)..."
    wget -c -q "https://dl.google.com/android/repository/android-ndk-r29-beta1-linux.zip" -O /workspace/ndk-r29.zip
    echo "NDK downloaded: $(ls -lh /workspace/ndk-r29.zip)"
    echo "Extracting NDK..."
    mkdir -p "$ANDROID_HOME/ndk"
    cd "$ANDROID_HOME/ndk"
    unzip -q -o /workspace/ndk-r29.zip
    mv android-ndk-r29-beta1 29.0.13113456 2>/dev/null || true
    rm -f /workspace/ndk-r29.zip
    echo "NDK installed"
else
    echo "[3/7] NDK already installed"
fi

# --- Step 4: Ensure build-tools package.xml ---
if [ ! -f "$ANDROID_HOME/build-tools/36.0.0/package.xml" ]; then
    echo "[4/7] Creating build-tools package.xml..."
    cat > "$ANDROID_HOME/build-tools/36.0.0/package.xml" << 'BTXML'
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<ns2:repository xmlns:ns2="http://schemas.android.com/repository/android/common/02" xmlns:ns3="http://schemas.android.com/repository/android/generic/02">
    <localPackage path="build-tools;36.0.0" obsolete="false">
        <type-details xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:type="ns3:genericDetailsType"/>
        <revision><major>36</major><minor>0</minor><micro>0</micro></revision>
        <display-name>Android SDK Build-Tools 36</display-name>
    </localPackage>
</ns2:repository>
BTXML
else
    echo "[4/7] Build-tools package.xml exists"
fi

# --- Step 5: Ensure platform package.xml ---
if [ ! -f "$ANDROID_HOME/platforms/android-36/package.xml" ]; then
    echo "[5/7] Creating platform package.xml..."
    cat > "$ANDROID_HOME/platforms/android-36/package.xml" << 'PXML'
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<ns2:repository xmlns:ns2="http://schemas.android.com/repository/android/common/02" xmlns:ns5="http://schemas.android.com/sdk/android/repo/repository2/03">
    <localPackage path="platforms;android-36" obsolete="false">
        <type-details xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:type="ns5:platformDetailsType"><api-level>36</api-level><codename></codename><layoutlib api="15"/></type-details>
        <revision><major>2</major></revision>
        <display-name>Android SDK Platform 36</display-name>
    </localPackage>
</ns2:repository>
PXML
else
    echo "[5/7] Platform package.xml exists"
fi

# --- Step 6: Build all 4 release APKs ---
echo "[6/7] Building all 4 release APKs..."
echo "  This will take a while (native compilation + Kotlin + packaging)..."
cd "$PROJECT"
chmod +x gradlew

./gradlew assembleFullRelease assembleFossRelease \
    --no-daemon \
    -Dorg.gradle.jvmargs="-Xmx2g" \
    2>&1

echo "Gradle exit code: $?"

# --- Step 7: Copy APKs to Downloads ---
echo "[7/7] Copying APKs to Downloads..."

find "$PROJECT/app/build/outputs/apk" -name "*release*.apk" -type f | while read apk; do
    filename=$(basename "$apk")
    echo "  Copying: $filename -> $DOWNLOAD/$filename"
    cp "$apk" "$DOWNLOAD/$filename"
done

echo ""
echo "=========================================="
echo "BUILD COMPLETE: $(date)"
echo "=========================================="
echo ""
echo "APKs in Downloads:"
ls -lh "$DOWNLOAD"/*release*.apk 2>/dev/null || echo "  (none found)"
echo ""

# Write a done marker
touch "$PROJECT/BUILD_DONE"
echo "DONE" > "$PROJECT/BUILD_DONE"
