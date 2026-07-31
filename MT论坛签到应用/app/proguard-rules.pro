# MT论坛签到助手 ProGuard配置

# 保留应用主类
-keep public class com.example.mtsignin.App { *; }
-keep public class com.example.mtsignin.MainActivity { *; }

# 保留Room数据库相关类
-keep class com.example.mtsignin.data.local.** { *; }
-keep class com.example.mtsignin.data.model.** { *; }

# 保留Hilt注入类
-keep class com.example.mtsignin.di.** { *; }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }

# 保留网络API类
-keep class com.example.mtsignin.network.** { *; }

# 保留UI组件
-keep class com.example.mtsignin.ui.** { *; }

# 保留Service
-keep class com.example.mtsignin.service.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Kotlin Coroutines
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep class * extends androidx.room.Dao
-dontwarn androidx.room.paging.**

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# 保留加密工具类
-keep class com.example.mtsignin.util.CryptoUtils { *; }

# 优化配置
-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontpreverify
-verbose

# 优化选项
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*,!code/allocation/variable

# 保留注解
-keepattributes *Annotation*

# 保留调试信息
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile