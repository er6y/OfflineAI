# Keep JNI methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep tokenizer JNI class
-keep class com.offlineai.tokenizers.HuggingfaceTokenizerJNI {
    *;
}