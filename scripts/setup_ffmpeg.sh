#!/bin/bash
# ============================================================
# KanDaloo FFmpeg Decoder Build Script
# ============================================================
# Run this ONCE to compile the FFmpeg native libraries with
# AC3, EAC3, AAC, MP3, Vorbis, Opus, and FLAC decoders.
#
# Prerequisites:
#   - Android NDK installed (via Android Studio SDK Manager)
#   - git, cmake, make available in PATH
#   - Linux or macOS (or WSL/Git Bash on Windows)
#
# Usage:
#   bash scripts/setup_ffmpeg.sh [NDK_PATH]
#
# Example:
#   bash scripts/setup_ffmpeg.sh ~/Android/Sdk/ndk/26.1.10909125
# ============================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
FFMPEG_MODULE_DIR="$PROJECT_ROOT/ffmpeg-decoder"
JNI_DIR="$FFMPEG_MODULE_DIR/src/main/jni"
FFMPEG_SRC_DIR="$JNI_DIR/ffmpeg"
MEDIA3_TEMP="$SCRIPT_DIR/.media3-temp"

# ─── Determine NDK Path ──────────────────────────────────────
if [ -n "$1" ]; then
    NDK_PATH="$1"
elif [ -n "$ANDROID_NDK_HOME" ]; then
    NDK_PATH="$ANDROID_NDK_HOME"
elif [ -n "$ANDROID_NDK" ]; then
    NDK_PATH="$ANDROID_NDK"
else
    # Try common locations
    for candidate in \
        "$HOME/Android/Sdk/ndk/"* \
        "$HOME/Library/Android/sdk/ndk/"* \
        "$LOCALAPPDATA/Android/Sdk/ndk/"* \
        "/usr/local/lib/android/sdk/ndk/"*; do
        if [ -d "$candidate" ]; then
            NDK_PATH="$candidate"
            break
        fi
    done
fi

if [ -z "$NDK_PATH" ] || [ ! -d "$NDK_PATH" ]; then
    echo "❌ Android NDK not found!"
    echo "   Install it via Android Studio > SDK Manager > SDK Tools > NDK"
    echo "   Then run: bash scripts/setup_ffmpeg.sh /path/to/ndk"
    exit 1
fi

echo "✅ NDK: $NDK_PATH"

# ─── Determine host platform ─────────────────────────────────
case "$(uname -s)" in
    Linux*)  HOST_PLATFORM="linux-x86_64";;
    Darwin*) HOST_PLATFORM="darwin-x86_64";;
    MINGW*|MSYS*|CYGWIN*) HOST_PLATFORM="linux-x86_64";;  # WSL/Git Bash
    *) echo "❌ Unsupported platform"; exit 1;;
esac
echo "✅ Host: $HOST_PLATFORM"

# ─── Minimum Android API level ───────────────────────────────
ANDROID_ABI=29  # Matches the app's minSdk

# ─── Decoders to enable ──────────────────────────────────────
# AC3 + EAC3 are the KEY ones for the reported issue
# Also including common audio codecs for future-proofing
ENABLED_DECODERS=(hevc h264 ac3 eac3 aac aac_latm mp3 vorbis opus flac pcm_mulaw pcm_alaw)

echo "✅ Decoders: ${ENABLED_DECODERS[*]}"

# ─── Step 1: Clone Media3 for build scripts and JNI wrapper ──
echo ""
echo "📦 Step 1/4: Fetching Media3 FFmpeg build files..."

if [ ! -d "$MEDIA3_TEMP" ]; then
    # Pin to 1.5.1 to match the project's media3 dependency version
    git clone --depth=1 --branch 1.5.1 --filter=blob:none --sparse \
        https://github.com/androidx/media.git "$MEDIA3_TEMP"
    cd "$MEDIA3_TEMP"
    git sparse-checkout set libraries/decoder_ffmpeg
    cd "$PROJECT_ROOT"
else
    echo "   (Already cloned, skipping)"
fi

# ─── Step 2: Set up our local module structure ────────────────
echo ""
echo "📂 Step 2/4: Setting up ffmpeg-decoder module..."

mkdir -p "$JNI_DIR"
mkdir -p "$FFMPEG_MODULE_DIR/src/main/java"

# Copy JNI wrapper files from media3
MEDIA3_FFM="$MEDIA3_TEMP/libraries/decoder_ffmpeg/src/main"

if [ -f "$MEDIA3_FFM/jni/ffmpeg_jni.cc" ]; then
    cp "$MEDIA3_FFM/jni/ffmpeg_jni.cc" "$JNI_DIR/"
    echo "   ✅ Copied ffmpeg_jni.cc"
fi

if [ -f "$MEDIA3_FFM/jni/build_ffmpeg.sh" ]; then
    cp "$MEDIA3_FFM/jni/build_ffmpeg.sh" "$JNI_DIR/"
    chmod +x "$JNI_DIR/build_ffmpeg.sh"
    echo "   ✅ Copied build_ffmpeg.sh"
fi

if [ -f "$MEDIA3_FFM/jni/CMakeLists.txt" ]; then
    cp "$MEDIA3_FFM/jni/CMakeLists.txt" "$JNI_DIR/"
    echo "   ✅ Copied CMakeLists.txt"
fi

# Copy Java sources (FfmpegAudioRenderer, FfmpegLibrary, etc.)
if [ -d "$MEDIA3_FFM/java" ]; then
    cp -r "$MEDIA3_FFM/java/"* "$FFMPEG_MODULE_DIR/src/main/java/"
    echo "   ✅ Copied Java sources"
fi

# ─── Step 3: Fetch and build FFmpeg ───────────────────────────
echo ""
echo "🔨 Step 3/4: Building FFmpeg native libraries..."
echo "   (This may take 5-15 minutes)"

cd "$JNI_DIR"

if [ ! -d "$FFMPEG_SRC_DIR" ]; then
    echo "   Cloning FFmpeg 6.0..."
    git clone git://source.ffmpeg.org/ffmpeg --branch=release/6.0 --depth=1
else
    echo "   (FFmpeg already cloned, skipping)"
fi

# Run the build script
./build_ffmpeg.sh \
    "$FFMPEG_MODULE_DIR/src/main" \
    "$NDK_PATH" \
    "$HOST_PLATFORM" \
    "$ANDROID_ABI" \
    "${ENABLED_DECODERS[@]}"

echo "   ✅ FFmpeg native libraries built"

# ─── Step 4: Cleanup ─────────────────────────────────────────
echo ""
echo "🧹 Step 4/4: Cleanup..."

# Remove the temp media3 clone (we've copied what we need)
rm -rf "$MEDIA3_TEMP"

# Note: We must KEEP $FFMPEG_SRC_DIR because CMake (during APK build)
# reads the prebuilt static libraries (.a) from $FFMPEG_SRC_DIR/android-libs/

echo ""
echo "🎉 Done! FFmpeg decoder module is ready."
echo "   The ffmpeg-decoder/ module now contains prebuilt native libraries."
echo "   You can now build the APK normally in Android Studio."
echo ""
echo "   Supported codecs: ${ENABLED_DECODERS[*]}"
