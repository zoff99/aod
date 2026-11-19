# Rules for the androidTest APK only. Never applied to the app APK.
#
# The app keeps its shrink-only R8 setup (app/proguard-rules.pro): that is what
# removes AndroidX's unused notification helpers from the shipped dex, which
# tools/check_apk_privacy.py verifies.
#
# The test APK is a different thing. AGP minifies it because the tested build
# type is minified, but it does NOT inherit the app's rule files - so R8 ran
# with no -dontwarn and failed on optional references inside AndroidX Test:
#
#   Missing class javax.lang.model.element.Modifier
#     (from com.google.errorprone.annotations.IncompatibleModifiers)
#   Missing class kotlin.jvm.internal.SourceDebugExtension
#     (from androidx.test.core.app.DeviceCapture, Kotlin 1.8 annotation on a
#      1.7 stdlib)
#
# Both are compile-only leftovers in the test libraries, not real problems.
# And a test harness must not be shrunk, renamed or optimised at all: JUnit and
# Espresso discover runners, rules and test methods reflectively.
#
# So: R8 still runs, but it is told to leave everything alone.

-dontshrink
-dontoptimize
-dontobfuscate

# Optional/compile-only references in the test libraries. This file applies to
# the test harness only, so a blanket -dontwarn cannot hide anything in the app.
-dontwarn

-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,SourceFile,LineNumberTable

# Belt and braces: everything the runner looks up by name stays present even
# if -dontshrink is ever removed from this file.
-keep class com.zoffcc.applications.aod.** { *; }
-keep class androidx.test.** { *; }
-keep class org.junit.** { *; }
-keep class org.hamcrest.** { *; }
-keep class * extends android.app.Instrumentation { *; }
-keepclassmembers class * {
    @org.junit.Test <methods>;
    @org.junit.Before <methods>;
    @org.junit.After <methods>;
    @org.junit.runner.RunWith <fields>;
}
