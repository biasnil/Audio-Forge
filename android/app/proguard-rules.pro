# R8 rules for release builds (isMinifyEnabled = true in app/build.gradle.kts).
# The app's own code uses no reflection; these are for libraries that do and
# ship no rules of their own. Media3, Compose and Kotlin bring their own.

# Keep line numbers in crash reports (the file name is replaced, the line kept).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# jaudiotagger creates ID3 frame bodies by class name
# (Class.forName("...FrameBody" + frameId)), and has optional desktop-only parts.
-keep class org.jaudiotagger.** { *; }
-dontwarn org.jaudiotagger.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn javax.swing.**

# jump3r (MP3 encoder): plain Java, but has desktop/javax.sound parts the app doesn't use.
-keep class de.sciss.jump3r.** { *; }
-dontwarn de.sciss.jump3r.**
-dontwarn javax.sound.**

# LiteRT: its native code calls back into these classes through JNI.
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**
# Its Play AI-pack model download (unused) refers to Play libraries that
# app/build.gradle.kts leaves out.
-dontwarn com.google.android.play.**
-dontwarn com.google.android.gms.**

# GitHub version only: yt-dlp wrapper, which reads yt-dlp's JSON into its own
# classes with Jackson (by field name, via reflection).
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn com.fasterxml.jackson.**
-dontwarn org.apache.commons.**
