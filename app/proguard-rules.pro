# Add project specific ProGuard rules here.

-keep class com.example.eventmanagementhub.data.model.** { *; }

# osmdroid (OpenStreetMap map view) — keep the public API surface intact.
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# ---- Release keep rules (R8 full mode) ---------------------------------------

# Retrofit: keep generic signatures + annotations so suspend Response<T> return types
# and @GET/@POST/@Body metadata survive; the HubApi interface is called reflectively.
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep interface com.example.eventmanagementhub.data.api.HubApi { *; }
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*

# kotlinx.serialization: generated $serializer classes, Companion.serializer() and
# the @Serializable data classes in data.model / OfflineCacheData / PendingBookmark.
-keepattributes *Annotation*, EnclosingMethod
-keepclassmembers class com.example.eventmanagementhub.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.eventmanagementhub.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.example.eventmanagementhub.**$$serializer { *; }
-keep class com.example.eventmanagementhub.data.OfflineCacheData { *; }
-keep class com.example.eventmanagementhub.data.PendingBookmark { *; }
-dontwarn kotlinx.serialization.**

# OkHttp / Okio
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# socket.io-client / engine.io-client (+ org.json bundled by Android)
-keep class io.socket.** { *; }
-dontwarn io.socket.**
-dontwarn org.json.**
