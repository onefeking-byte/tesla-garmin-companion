# App components
-keep class com.example.teslagarmin.** { *; }
# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# security-crypto / Tink
-keep class com.google.crypto.tink.** { *; }
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite { <fields>; }
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**
# Play services wearable
-keep class com.google.android.gms.wearable.** { *; }
-dontwarn com.google.android.gms.**
# Tiles / ProtoLayout
-keep class androidx.wear.tiles.** { *; }
-keep class androidx.wear.protolayout.** { *; }
-dontwarn androidx.wear.**
# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**
# Guava
-dontwarn com.google.common.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn org.checkerframework.**
# Tesla Fleet SDK (Vehicle Command Protocol) — protobuf-lite 리플렉션, Retrofit/Gson 모델
-keep class com.boltfortesla.teslafleetsdk.** { *; }
-keep class com.tesla.generated.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-dontwarn retrofit2.**
-dontwarn com.google.protobuf.**
-keep class com.garmin.** { *; }
-dontwarn com.garmin.**
