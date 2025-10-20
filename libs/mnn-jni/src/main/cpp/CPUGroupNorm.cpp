//
//  CPUGroupNorm.cpp
//  MNN
//
//  Created for OfflineAI - CPU implementation of GroupNorm
//  Based on CPULayerNorm.cpp structure
//

#include "CPUGroupNorm.hpp"
#include "backend/cpu/CPUBackend.hpp"
#include "backend/cpu/compute/CommonOptFunction.h"
#include "core/Concurrency.h"
#include "core/TensorUtils.hpp"
#include <cmath>
#include <jni.h>

namespace MNN {

CPUGroupNorm::CPUGroupNorm(Backend *bn, const Op *op) : Execution(bn) {
    auto groupNormParam = op->main_as_GroupNorm();
    
    mGroup = groupNormParam->group();
    mEpsilon = groupNormParam->epsilon();
    mBSwish = groupNormParam->bSwish();
    
    // Copy gamma and beta
    if (groupNormParam->gamma() && groupNormParam->beta()) {
        int size = groupNormParam->gamma()->size();
        mGamma.resize(size);
        mBeta.resize(size);
        memcpy(mGamma.data(), groupNormParam->gamma()->data(), size * sizeof(float));
        memcpy(mBeta.data(), groupNormParam->beta()->data(), size * sizeof(float));
    }
}

ErrorCode CPUGroupNorm::onResize(const std::vector<Tensor *> &inputs, const std::vector<Tensor *> &outputs) {
    auto input = inputs[0];
    
    // Input shape: [N, C, H, W] or [N, C, D]
    // Group Norm: split C into groups, normalize within each group
    
    int batch = input->batch();
    int channels = input->channel();
    int spatial = 1;
    for (int i = 2; i < input->dimensions(); ++i) {
        spatial *= input->length(i);
    }
    
    // Allocate temp buffers for mean and variance
    // Each batch has 'mGroup' groups
    mMean.reset(Tensor::createDevice<float>({batch * mGroup}));
    mVar.reset(Tensor::createDevice<float>({batch * mGroup}));
    
    bool success = backend()->onAcquireBuffer(mMean.get(), Backend::DYNAMIC);
    success = success && backend()->onAcquireBuffer(mVar.get(), Backend::DYNAMIC);
    
    if (!success) {
        return OUT_OF_MEMORY;
    }
    
    backend()->onReleaseBuffer(mMean.get(), Backend::DYNAMIC);
    backend()->onReleaseBuffer(mVar.get(), Backend::DYNAMIC);
    
    return NO_ERROR;
}

ErrorCode CPUGroupNorm::onExecute(const std::vector<Tensor *> &inputs, const std::vector<Tensor *> &outputs) {
    auto input = inputs[0];
    auto output = outputs[0];
    
    const float *inputData = input->host<float>();
    float *outputData = output->host<float>();
    float *meanData = mMean->host<float>();
    float *varData = mVar->host<float>();
    
    int batch = input->batch();
    int channels = input->channel();
    int spatial = 1;
    for (int i = 2; i < input->dimensions(); ++i) {
        spatial *= input->length(i);
    }
    
    int channelsPerGroup = channels / mGroup;
    int elementsPerGroup = channelsPerGroup * spatial;
    
    auto bn = static_cast<CPUBackend*>(backend());
    int threadNumber = bn->threadNumber();
    
    // Step 1: Compute mean and variance for each group
    MNN_CONCURRENCY_BEGIN(tId, threadNumber) {
        for (int b = tId; b < batch * mGroup; b += threadNumber) {
            int n = b / mGroup;
            int g = b % mGroup;
            
            const float *groupData = inputData + n * channels * spatial + g * channelsPerGroup * spatial;
            
            // Compute mean
            double sum = 0.0;
            for (int i = 0; i < elementsPerGroup; ++i) {
                sum += groupData[i];
            }
            float mean = sum / elementsPerGroup;
            meanData[b] = mean;
            
            // Compute variance
            double varSum = 0.0;
            for (int i = 0; i < elementsPerGroup; ++i) {
                float diff = groupData[i] - mean;
                varSum += diff * diff;
            }
            float variance = varSum / elementsPerGroup;
            varData[b] = variance;
        }
    }
    MNN_CONCURRENCY_END();
    
    // Step 2: Normalize and apply gamma/beta
    MNN_CONCURRENCY_BEGIN(tId, threadNumber) {
        for (int idx = tId; idx < batch * channels * spatial; idx += threadNumber) {
            int n = idx / (channels * spatial);
            int c = (idx / spatial) % channels;
            int s = idx % spatial;
            
            int g = c / channelsPerGroup;
            int groupIdx = n * mGroup + g;
            
            float mean = meanData[groupIdx];
            float variance = varData[groupIdx];
            float invStd = 1.0f / sqrtf(variance + mEpsilon);
            
            // Normalize
            float normalized = (inputData[idx] - mean) * invStd;
            
            // Apply gamma and beta if available
            float result = normalized;
            if (!mGamma.empty()) {
                result = result * mGamma[c] + mBeta[c];
            }
            
            // Apply Swish activation if needed
            if (mBSwish) {
                result = result / (1.0f + expf(-result)); // Swish: x * sigmoid(x)
            }
            
            outputData[idx] = result;
        }
    }
    MNN_CONCURRENCY_END();
    
    return NO_ERROR;
}

class CPUGroupNormCreator : public CPUBackend::Creator {
public:
    virtual Execution *onCreate(const std::vector<Tensor *> &inputs, const std::vector<Tensor *> &outputs,
                                const MNN::Op *op, Backend *backend) const override {
        return new CPUGroupNorm(backend, op);
    }
};

// Static creator instance for registration
static CPUGroupNormCreator* gCPUGroupNormCreator = nullptr;

} // namespace MNN

// JNI function to register CPUGroupNorm
// Call this from Java after MNN initialization
extern "C" JNIEXPORT void JNICALL
Java_com_offlineai_mnn_MnnInference_registerCPUGroupNorm(JNIEnv* env, jclass clazz) {
    MNN_PRINT("[CPUGroupNorm] JNI registration function called\n");
    
    if (MNN::gCPUGroupNormCreator == nullptr) {
        MNN::gCPUGroupNormCreator = new MNN::CPUGroupNormCreator();
        MNN::CPUBackend::addCreator(MNN::OpType_GroupNorm, MNN::gCPUGroupNormCreator);
        MNN_PRINT("[CPUGroupNorm] Successfully registered CPUGroupNorm operator\n");
    } else {
        MNN_PRINT("[CPUGroupNorm] Already registered, skipping\n");
    }
}
