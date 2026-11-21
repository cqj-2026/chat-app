# OkHttp 可选依赖，缺失时不应报错
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# 保留 Kotlin 元数据，避免反射相关异常
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

# sherpa-onnx：JNI 通过反射回调这些类，必须保留
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**
