# =============================================================================
# ProGuard / R8 代码缩减规则 — Elva LaoBai
# =============================================================================

# ---- LiteRT-LM 本地推理库 (JNI + 反射) ----
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# ---- TensorFlow Lite ----
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# ---- Protobuf (Lite 模式) ----
-keep class com.google.ai.edge.gallery.proto.** { *; }
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }

# ---- Gson 序列化 (模型白名单解析) ----
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.ai.edge.gallery.data.AllowedModel { *; }
-keep class com.google.ai.edge.gallery.data.ModelAllowlist { *; }
-keep class com.google.ai.edge.gallery.data.DefaultConfig { *; }
-keep class com.google.ai.edge.gallery.data.SocModelFile { *; }
-keep class com.google.ai.edge.gallery.data.ModelFile { *; }
-keep class com.google.ai.edge.gallery.data.NamedDeviceGroup { *; }
-keep class com.google.ai.edge.gallery.data.DeviceRequirements { *; }
-keep class com.google.ai.edge.gallery.data.PromptTemplate { *; }
-keep class com.google.ai.edge.gallery.data.ModelDataFile { *; }
-keep class com.google.ai.edge.gallery.data.Model { *; }
-keep class com.google.gson.** { *; }
-keepclassmembers,allowobfuscation class * {
  @com.google.gson.annotations.SerializedName <fields>;
}

# ---- Moshi (JSON 序列化) ----
-keep class com.squareup.moshi.** { *; }
-dontwarn com.squareup.moshi.**
-keepclasseswithmembers class * {
    @com.squareup.moshi.* <methods>;
}

# ---- Hilt 依赖注入 ----
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-keep class dagger.hilt.android.internal.managers.** { *; }
-keep class dagger.hilt.internal.aggregatedroot.** { *; }
-dontwarn dagger.hilt.**

# ---- Kotlin 协程 ----
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepnames class kotlinx.coroutines.android.AndroidDispatcherFactory {}

# ---- Kotlin 序列化 ----
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class kotlinx.serialization.json.** { *; }

# ---- AppAuth (HuggingFace OAuth) ----
-keep class net.openid.appauth.** { *; }
-dontwarn net.openid.appauth.**

# ---- WebView JS 桥接 (Skills 使用) ----
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ---- Jetpack Compose ----
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ---- WorkManager ----
-keep class androidx.work.** { *; }
-keep class com.google.ai.edge.gallery.worker.DownloadWorker { *; }

# ---- CameraX ----
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ---- Ktor Client ----
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# ---- MLKit GenAI ----
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# ---- Elva LaoBai 核心类 ----
-keep class com.elva.laobai.** { *; }

# ---- 保持枚举 ----
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---- 保持 Serializable 类 ----
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ---- 通用规则 ----
-keepattributes SourceFile,LineNumberTable
-keepattributes InnerClasses
-keepattributes EnclosingMethod
