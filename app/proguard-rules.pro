# Shrink-only R8 configuration.
#
# The point is not obfuscation or speed: it is to make sure code this app must
# never run is not even present in the APK. AndroidX ships notification helpers
# (androidx.core.app.NotificationCompat and friends) that know how to read
# notification titles and message text. Nothing here calls them - the privacy
# guard in gradle/privacy-guard.gradle fails the build if anything ever does -
# and shrinking removes them from the dex entirely.
#
# No renaming and no optimisation, so stack traces, reflection-free behaviour
# and debugging stay exactly as they are.

-dontobfuscate
-dontoptimize
-dontwarn
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,SourceFile,LineNumberTable

# The app itself is kept completely.
-keep class com.zoffcc.applications.aod.** { *; }

# Views named in layout XML.
-keep class androidx.appcompat.widget.** { *; }
-keep class androidx.recyclerview.widget.** { *; }
-keep class * extends android.view.View {
    <init>(android.content.Context);
    <init>(android.content.Context, android.util.AttributeSet);
    <init>(android.content.Context, android.util.AttributeSet, int);
}

# Classes the merged manifest instantiates by name.
-keep class androidx.core.app.CoreComponentFactory { *; }
-keep class androidx.startup.** { *; }
-keep class androidx.emoji2.text.EmojiCompatInitializer { *; }
-keep class androidx.lifecycle.ProcessLifecycleInitializer { *; }

# Contracts the framework relies on.
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclasseswithmembernames class * { native <methods>; }
-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
}
