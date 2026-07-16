# Kotlin
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Compose
-keep class androidx.compose.** { *; }

# AndroidX
-keep class * extends androidx.lifecycle.ViewModel { *; }

# Keep WiFi classes
-keep class org.wifiauditor.pro.** { *; }
