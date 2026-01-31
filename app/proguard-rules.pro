# ═══════════════════════════════════════════════════════════════════════════════
# OpusIDE ProGuard Rules
# ═══════════════════════════════════════════════════════════════════════════════

# ═══════════════════════════════════════════════════════════════════════════════
# KOTLIN SERIALIZATION
# ═══════════════════════════════════════════════════════════════════════════════
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep serializable classes
-keep,includedescriptorclasses class com.opuside.app.**$$serializer { *; }
-keepclassmembers class com.opuside.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.opuside.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ═══════════════════════════════════════════════════════════════════════════════
# KTOR
# ═══════════════════════════════════════════════════════════════════════════════
-keep class io.ktor.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.atomicfu.**
-dontwarn io.netty.**
-dontwarn com.typesafe.**
-dontwarn org.slf4j.**

# ═══════════════════════════════════════════════════════════════════════════════
# OKHTTP
# ═══════════════════════════════════════════════════════════════════════════════
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ═══════════════════════════════════════════════════════════════════════════════
# ROOM
# ═══════════════════════════════════════════════════════════════════════════════
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ═══════════════════════════════════════════════════════════════════════════════
# HILT GENERATED CLASSES
# ═══════════════════════════════════════════════════════════════════════════════
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ComponentSupplier { *; }
-keep class * implements dagger.hilt.internal.GeneratedComponent { *; }
-keepnames @dagger.hilt.android.lifecycle.HiltViewModel class * extends androidx.lifecycle.ViewModel
-keep class **_HiltModules { *; }
-keep class **_HiltComponents { *; }
-keep class **_Factory { *; }
-keep class **_MembersInjector { *; }

# ═══════════════════════════════════════════════════════════════════════════════
# WORKMANAGER
# ═══════════════════════════════════════════════════════════════════════════════
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker { *; }
-keep class androidx.work.impl.WorkDatabase { *; }
-keep class androidx.work.** { *; }
-dontwarn androidx.work.impl.**

# ═══════════════════════════════════════════════════════════════════════════════
# COMPOSE
# ═══════════════════════════════════════════════════════════════════════════════
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ═══════════════════════════════════════════════════════════════════════════════
# DATA CLASSES (API Models)
# ═══════════════════════════════════════════════════════════════════════════════
-keep class com.opuside.app.core.network.anthropic.model.** { *; }
-keep class com.opuside.app.core.network.github.model.** { *; }
-keep class com.opuside.app.core.database.entity.** { *; }

# ═══════════════════════════════════════════════════════════════════════════════
# DEBUG (Можно удалить для release)
# ═══════════════════════════════════════════════════════════════════════════════
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile