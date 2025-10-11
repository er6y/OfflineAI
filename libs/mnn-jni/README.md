# MNN JNI - MNN LLM Inference Engine for Android

This module provides JNI bindings for MNN (Mobile Neural Network) LLM inference engine, enabling high-performance on-device LLM inference on Android.

## Features

- **One-shot Inference**: Built-in autoregressive loop, no manual token generation needed
- **Multiple Backends**: CPU, OpenCL, Vulkan, NNAPI, KleidiAI support
- **Streaming Output**: Token-by-token streaming via callback
- **Multi-modal Support**: Text, image, and audio inputs
- **Automatic KV Cache**: Built-in KV cache management for multi-turn conversations
- **High Performance**: 8.6x faster prefill, 2.3x faster decode vs llama.cpp (official benchmark)

## Architecture

```
libs/mnn-jni/
├── src/main/
│   ├── cpp/
│   │   ├── CMakeLists.txt          # CMake build configuration
│   │   └── mnn_llm_jni.cpp         # JNI implementation
│   └── java/com/offlineai/mnn/
│       └── MnnInference.java       # Java interface
├── build.gradle                     # Gradle configuration
└── proguard-rules.pro              # ProGuard rules
```

## Build Configuration

### Gradle Configuration

The module supports multiple backends configured in `build.gradle`:

```gradle
externalNativeBuild {
    cmake {
        arguments "-DMNN_BUILD_LLM=ON",           // Enable LLM support
                  "-DMNN_OPENCL=ON",              // OpenCL backend
                  "-DMNN_VULKAN=ON",              // Vulkan backend
                  "-DMNN_NNAPI=ON",               // NNAPI backend
                  "-DMNN_ARM82=ON",               // ARM82 (fp16/dot)
                  "-DMNN_SUPPORT_TRANSFORMER_FUSE=ON",
                  "-DMNN_LOW_MEMORY=ON",
                  "-DMNN_CPU_WEIGHT_DEQUANT_GEMM=ON",
                  "-DMNN_USE_LOGCAT=ON",
                  "-DLLM_SUPPORT_VISION=ON"       // Vision support
        
        // Optional: Enable KleidiAI
        if (project.hasProperty('ENABLE_KLEIDIAI')) {
            arguments "-DMNN_KLEIDIAI=ON"
        }
    }
}
```

### CMake Configuration

Key CMake options in `CMakeLists.txt`:

- `MNN_BUILD_LLM`: Enable LLM inference support
- `MNN_OPENCL`: Enable OpenCL GPU backend
- `MNN_VULKAN`: Enable Vulkan GPU backend
- `MNN_NNAPI`: Enable Android NNAPI backend
- `MNN_ARM82`: Enable ARM82 optimizations (fp16, dot product)
- `MNN_KLEIDIAI`: Enable ARM KleidiAI library (optional)
- `MNN_SUPPORT_TRANSFORMER_FUSE`: Enable transformer operator fusion
- `MNN_LOW_MEMORY`: Enable low memory mode
- `MNN_CPU_WEIGHT_DEQUANT_GEMM`: Enable CPU weight dequantization GEMM

## Usage

### Java API

#### Create Session

```java
// Build configuration
String config = new MnnInference.ConfigBuilder()
    .backendType("cpu")          // or "opencl", "vulkan", "nnapi"
    .threadNum(4)
    .precision("low")            // fp16 for performance
    .memory("low")               // enable runtime quantization
    .maxNewTokens(512)
    .temperature(0.7f)
    .topP(0.9f)
    .topK(40)
    .reuseKv(true)              // enable KV cache
    .useMmap(true)              // low memory mode
    .tmpPath("/path/to/cache")
    .build();

// Create session
long sessionHandle = MnnInference.createSession("/path/to/model", config);
```

#### Text Inference

```java
MnnInference.inference(sessionHandle, prompt, new MnnInference.InferenceCallback() {
    @Override
    public boolean onToken(String token) {
        // Handle streaming token
        System.out.print(token);
        return false; // return true to stop
    }
    
    @Override
    public void onComplete(Map<String, Long> stats) {
        // Inference complete
        System.out.println("\nDone!");
        System.out.println("Prefill: " + stats.get("prefill_us") + " us");
        System.out.println("Decode: " + stats.get("decode_us") + " us");
    }
    
    @Override
    public void onError(String error) {
        System.err.println("Error: " + error);
    }
});
```

#### Multi-modal Inference

```java
String[] imagePaths = {"/path/to/image1.jpg", "/path/to/image2.jpg"};

MnnInference.inferenceWithImages(sessionHandle, prompt, imagePaths, callback);
```

#### Destroy Session

```java
MnnInference.destroySession(sessionHandle);
```

### Backend Selection

Check backend availability before use:

```java
if (MnnInference.isBackendAvailable("vulkan")) {
    // Use Vulkan backend
} else if (MnnInference.isBackendAvailable("opencl")) {
    // Fallback to OpenCL
} else {
    // Fallback to CPU
}
```

## Model Format

### Required Files

MNN models require the following files in the model directory:

```
model_dir/
├── config.json              # Runtime configuration
├── llm.mnn                  # Model structure
├── llm.mnn.weight           # Model weights (quantized)
├── tokenizer.txt            # Tokenizer vocabulary
├── llm_config.json          # Model metadata
└── embeddings_bf16.bin      # Embeddings (optional)
```

### Model Conversion

Convert PyTorch/HuggingFace models to MNN format:

```bash
cd libs/mnn/transformers/llm/export

# Install dependencies
pip install -r requirements.txt

# Convert model
python llmexport.py \
    --path /path/to/model \
    --export mnn \
    --quant_bit 4 \
    --quant_block 128 \
    --hqq
```

**Conversion Options:**
- `--export mnn`: Export to MNN format
- `--quant_bit 4`: 4-bit quantization (supports 4/8 bit)
- `--quant_block 128`: Quantization block size
- `--hqq`: Use HQQ quantization (better accuracy)
- `--awq`: Use AWQ quantization (alternative)

## Build Instructions

### Build Module

```bash
# Build debug version
./gradlew :libs:mnn-jni:assembleDebug

# Build release version
./gradlew :libs:mnn-jni:assembleRelease -PKEYPSWD=abc-1234
```

### Build with KleidiAI

```bash
./gradlew :libs:mnn-jni:assembleDebug -PENABLE_KLEIDIAI=true
```

### Build Complete App

```bash
# Debug
./gradlew :app:assembleDebug -PKEYPSWD=abc-1234

# Release
./gradlew :app:assembleRelease -PKEYPSWD=abc-1234
```

## Performance Optimization

### Memory Optimization

1. **mmap Mode**: `use_mmap=true`
   - Write weights to disk, load on demand
   - Reduce memory footprint
   - Suitable for large models

2. **Low Memory Mode**: `MNN_LOW_MEMORY=ON`
   - Runtime quantization
   - Reduce intermediate tensor memory

3. **KV Cache Reuse**: `reuse_kv=true`
   - Reuse KV cache for multi-turn conversations
   - Reduce redundant computation

### Compute Optimization

1. **Transformer Fusion**: `MNN_SUPPORT_TRANSFORMER_FUSE=ON`
   - Operator fusion optimization
   - Reduce kernel launch overhead

2. **Weight Dequant GEMM**: `MNN_CPU_WEIGHT_DEQUANT_GEMM=ON`
   - Direct computation on quantized weights
   - Avoid dequantization overhead

3. **Precision Strategy**:
   - `precision="low"`: Use fp16 (recommended)
   - `precision="high"`: Use fp32 (accuracy priority)

## Supported Backends

### CPU Backend
- ARM NEON optimizations
- ARM82: fp16 and dot product acceleration
- KleidiAI: ARM Kleidi AI library acceleration (optional)

### OpenCL Backend
- Mobile GPU acceleration
- Compatible with most Android devices
- Runtime availability detection

### Vulkan Backend
- Cross-platform GPU acceleration
- Better performance and compatibility
- Requires Vulkan 1.2+ support

### NNAPI Backend
- Android Neural Networks API
- Utilize device-specific accelerators (NPU/DSP)
- Android 8.1+ support

## Troubleshooting

### Build Issues

**Problem**: CMake configuration fails
- **Solution**: Ensure MNN submodule is properly initialized
  ```bash
  git submodule update --init --recursive
  ```

**Problem**: Missing MNN headers
- **Solution**: Check MNN_ROOT path in CMakeLists.txt

### Runtime Issues

**Problem**: Session creation fails
- **Solution**: Check model directory contains all required files
- **Solution**: Verify config.json is valid JSON

**Problem**: Backend not available
- **Solution**: Check device support for the backend
- **Solution**: Fallback to CPU backend

## License

This module integrates MNN (Apache 2.0 License).

## References

- [MNN GitHub](https://github.com/alibaba/MNN)
- [MNN LLM Documentation](https://github.com/alibaba/MNN/tree/master/transformers/llm)
- [MNN Android Demo](https://github.com/alibaba/MNN/tree/master/apps/Android/MnnLlmChat)
