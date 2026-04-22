# Hilt / Dagger generated and injected types
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.internal.GeneratedComponent { *; }
-keep class * extends dagger.hilt.internal.GeneratedComponentManager { *; }
-keep class * implements dagger.hilt.internal.GeneratedComponentManagerHolder { *; }
-keep class * extends androidx.lifecycle.ViewModel

# Room database + entities (reflection and generated adapters)
-keep class androidx.room.RoomDatabase { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class *

# Firebase model and SDK internals commonly accessed reflectively
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Kotlin serialization generated serializers
-keepclassmembers class **$$serializer { *; }
-keepclassmembers class kotlinx.serialization.internal.** { *; }
-keep @kotlinx.serialization.Serializable class *

# Coil image loading
-keep class coil.** { *; }
-dontwarn coil.**

