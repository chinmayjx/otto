# Otto's release build is shrunk by R8. The app reads no classes by reflection of its own; what's kept here is
# for libraries' optional parts that aren't in the build.
-dontwarn org.commonmark.ext.gfm.strikethrough.**
-dontwarn pl.droidsonroids.gif.**
-dontwarn com.caverock.androidsvg.**
-keepattributes SourceFile,LineNumberTable
# WebRTC (the assistant's voice call): its native code finds its Java classes, methods and fields by name.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
