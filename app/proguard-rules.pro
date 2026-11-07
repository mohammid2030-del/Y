# Room entities and DAOs are generated; keep entity fields for reflection-free access is not needed,
# but keep Gson streaming classes (used without reflection) and Retrofit service interfaces.
-keep interface com.nova.iptv.data.network.** { *; }
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions*
# Never log in release
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
