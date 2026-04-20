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

# ─── Media3 / ExoPlayer ──────────────────────────────────────
# Keep all public Media3 APIs (renderers, extractors, etc.)
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ─── Firebase ────────────────────────────────────────────────
# Firebase ships its own rules inside the AAR, but keep core auth models too
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# ─── Google Identity / Credential Manager ────────────────────
-keep class androidx.credentials.** { *; }
-keep class com.google.android.libraries.identity.** { *; }

# ─── Kotlin ──────────────────────────────────────────────────
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes SourceFile,LineNumberTable
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.coroutines.**

# ─── JNI ─────────────────────────────────────────────────────
# Native methods accessed from JNI must not be renamed
-keepclasseswithmembernames class * {
    native <methods>;
}

# ─── Jetpack Compose ─────────────────────────────────────────
# Compose ships its own rules. These extras protect runtime hooks.
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ─── DataStore / Preferences ─────────────────────────────────
-keep class androidx.datastore.** { *; }

# ─── Coil ────────────────────────────────────────────────────
-keep class coil.** { *; }
-dontwarn coil.**

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