# ProGuard rules для myVPNproject v3.0

# Обязательно сохраняем JNI entry points.
# Если ProGuard переименует ProxyEngine, JNI-вызовы сломаются с UnsatisfiedLinkError.
-keep class com.makskbz.myvpnproject.vpn.ProxyEngine {
    native <methods>;
    public static *;
}

# Сохраняем модели данных для Gson (JSON сериализация)
-keep class com.makskbz.myvpnproject.vpn.BypassConfig { *; }
-keep class com.makskbz.myvpnproject.vpn.Preset { *; }

# Gson internals
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# AndroidX / Compose — стандартные правила
-keep class androidx.** { *; }
-dontwarn androidx.**
