# ============================================================
# KanDaloo ProGuard / R8 Rules
# ============================================================

# ─── FFmpeg Extension Renderers ──────────────────────────────
# DefaultRenderersFactory loads these by full class name via Class.forName().
# R8 MUST NOT rename or remove them or FFmpeg decoding silently fails.
-keep class androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer { *; }
-keep class androidx.media3.decoder.ffmpeg.FfmpegVideoRenderer { *; }
-keep class androidx.media3.decoder.ffmpeg.ExperimentalFfmpegVideoRenderer { *; }
-keep class androidx.media3.decoder.ffmpeg.FfmpegLibrary { *; }
-keepclassmembers class androidx.media3.decoder.ffmpeg.** { *; }

# ─── Google Identity / Credential Manager ────────────────────
-keep class androidx.credentials.** { *; }
-keep class com.google.android.libraries.identity.** { *; }

# ─── Kotlin / General Attributes ─────────────────────────────
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes SourceFile,LineNumberTable

# ─── JNI ─────────────────────────────────────────────────────
# Native methods accessed from JNI must not be renamed
-keepclasseswithmembernames class * {
    native <methods>;
}

# ─── DataStore / Preferences ─────────────────────────────────
-keep class androidx.datastore.** { *; }

# ─── General Android safety ──────────────────────────────────
# Preserve Parcelable implementations (used by Android system)
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Preserve Serializable implementations
-keepnames class * implements java.io.Serializable

# Don't warn about missing classes from unused platforms
-dontwarn java.awt.**
-dontwarn javax.**