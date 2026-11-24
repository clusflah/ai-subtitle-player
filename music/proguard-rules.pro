# VoxMusic ProGuard rules
-keep class com.spotify.** { *; }
-keep class com.voxmusic.app.music.WebStreamingMusic$** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
