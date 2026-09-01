# yt-dlp wrapper parses JSON into model classes by reflection, so these
# must survive shrinking or getInfo() returns empty fields at runtime.
-keep class com.yausername.youtubedl_android.** { *; }
-keep class com.yausername.youtubedl_common.** { *; }

# Jackson databind, used for that parsing
-keep class com.fasterxml.jackson.** { *; }
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.* *;
}
-dontwarn com.fasterxml.jackson.databind.**

# Kotlin coroutines
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# Our own classes referenced from XML or the manifest
-keep class com.manwar.videodownloader.MainActivity { *; }
-keep class com.manwar.videodownloader.DownloadService { *; }
