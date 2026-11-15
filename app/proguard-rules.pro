-keep class com.k2fsa.sherpa.onnx.** { *; }

# Telegram（TDLib）：接口类由原生库按名字反射调用
-keep class org.drinkless.tdlib.** { *; }

# VLC 原生音频分流桥与 libVLC JNI 指针字段防混淆
-keep class com.aisub.player.media.VlcAudioBridge { *; }
-keep class org.videolan.libvlc.** { *; }
