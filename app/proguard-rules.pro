# Keep plist/bouncycastle reflection-heavy classes
-keep class com.dd.plist.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
