# Application.mk for llamacpp-jni
# NDK configuration to optimize ARM to x86 translation

# Force disable F16C instruction set to prevent NDK translation issues
APP_CFLAGS += -mno-f16c
APP_CPPFLAGS += -mno-f16c

# Additional flags to disable other potentially problematic instruction sets
APP_CFLAGS += -mno-fma -mno-avx2 -mno-avx -mno-sse4.2 -mno-sse4.1 -mno-ssse3 -mno-sse3
APP_CPPFLAGS += -mno-fma -mno-avx2 -mno-avx -mno-sse4.2 -mno-sse4.1 -mno-ssse3 -mno-sse3

# Undefine compiler macros for instruction sets
APP_CFLAGS += -U__F16C__ -U__FMA__ -U__AVX2__ -U__AVX__ -U__SSE4_2__ -U__SSE4_1__ -U__SSSE3__ -U__SSE3__
APP_CPPFLAGS += -U__F16C__ -U__FMA__ -U__AVX2__ -U__AVX__ -U__SSE4_2__ -U__SSE4_1__ -U__SSSE3__ -U__SSE3__

# Explicitly disable GGML instruction set features
APP_CFLAGS += -DGGML_F16C=0 -DGGML_USE_F16C=0 -DGGML_AVX=0 -DGGML_SSE=0
APP_CPPFLAGS += -DGGML_F16C=0 -DGGML_USE_F16C=0 -DGGML_AVX=0 -DGGML_SSE=0

# Android NDK Translation specific flags
APP_CFLAGS += -DANDROID_DISABLE_F16C=1 -DANDROID_NDK_TRANSLATION_DISABLE_F16C=1
APP_CPPFLAGS += -DANDROID_DISABLE_F16C=1 -DANDROID_NDK_TRANSLATION_DISABLE_F16C=1

# Target all supported ABIs
APP_ABI := all

# Use the latest Android platform
APP_PLATFORM := android-21

# Use libc++ as the STL
APP_STL := c++_shared

# Enable optimization
APP_OPTIM := release