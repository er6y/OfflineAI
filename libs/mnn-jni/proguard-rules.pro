# Add project specific ProGuard rules here.
# MNN JNI ProGuard rules

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep MNN inference classes
-keep class com.offlineai.mnn.** { *; }

# Keep callback interfaces
-keep interface com.offlineai.mnn.MnnInference$InferenceCallback { *; }
