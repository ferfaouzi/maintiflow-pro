# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.maintiflow.pro.**$$serializer { *; }
-keepclassmembers class com.maintiflow.pro.** { *** Companion; }
-keepclasseswithmembers class com.maintiflow.pro.** { kotlinx.serialization.KSerializer serializer(...); }
# Retrofit
-keepattributes Signature, Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep interface com.maintiflow.pro.data.api.MaintiFlowApi { *; }
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
