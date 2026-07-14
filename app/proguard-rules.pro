# ─── MCServer Launcher ProGuard / R8 Rules ───

# ── Kotlin ──
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes Signature
-keepattributes SourceFile,LineNumberTable
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# ── Kotlin Coroutines ──
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ── Kotlin Serialization (Compose) ──
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class kotlinx.serialization.** { *; }

# ── Application classes ──
-keep class com.mcserver.launcher.** { *; }
-keep class com.mcserver.launcher.data.** { *; }
-keep class com.mcserver.launcher.server.** { *; }
-keep class com.mcserver.launcher.ui.** { *; }
-keep class com.mcserver.launcher.utils.** { *; }

# ── DataStore Preferences ──
-keep class androidx.datastore.** { *; }
-keep class androidx.datastore.preferences.** { *; }
-keep class androidx.datastore.preferences.core.** { *; }

# ── Compose ──
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**
-keep class androidx.compose.ui.** { *; }
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.material3.** { *; }
-keep class androidx.compose.material.icons.** { *; }

# ── Navigation Compose ──
-keep class androidx.navigation.** { *; }

# ── AndroidX / Lifecycle ──
-keep class androidx.lifecycle.** { *; }
-keep class androidx.activity.** { *; }
-keep class androidx.core.** { *; }

# ── JSON (org.json, included in Android SDK) ──
-keep class org.json.** { *; }
-keepclassmembers class org.json.** { *; }

# ── Java IO / NIO / JAR / ZIP ──
-keep class java.io.** { *; }
-keep class java.nio.** { *; }
-keep class java.nio.file.** { *; }
-keep class java.util.jar.** { *; }
-keep class java.util.zip.** { *; }

# ── Network ──
-keep class java.net.** { *; }
-keep class javax.net.** { *; }
-keep class javax.net.ssl.** { *; }

# ── Security / Crypto (MD5 for offline UUID) ──
-keep class java.security.** { *; }

# ── Prevent removal of used classes via reflection ──
-keep class * extends android.app.Service { *; }
-keep class * extends android.content.BroadcastReceiver { *; }
-keep class * extends android.app.Application { *; }
-keep class * extends android.app.Activity { *; }

# ── Enums ──
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── R8 specific ──
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ── Reduce APK size: remove unused resources and debug info ──
-allowaccessmodification
-repackageclasses
