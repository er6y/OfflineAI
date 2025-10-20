//
//  CPUGroupNorm.hpp
//  MNN
//
//  Created for OfflineAI
//

#ifndef CPUGroupNorm_hpp
#define CPUGroupNorm_hpp

#include "core/Execution.hpp"

namespace MNN {

class CPUGroupNorm : public Execution {
public:
    CPUGroupNorm(Backend *bn, const Op *op);
    virtual ~CPUGroupNorm() = default;
    virtual ErrorCode onResize(const std::vector<Tensor *> &inputs, const std::vector<Tensor *> &outputs) override;
    virtual ErrorCode onExecute(const std::vector<Tensor *> &inputs, const std::vector<Tensor *> &outputs) override;
    
private:
    int mGroup;
    float mEpsilon;
    int mBSwish;
    std::vector<float> mGamma;
    std::vector<float> mBeta;
    
    // Temp buffers
    std::shared_ptr<Tensor> mMean;
    std::shared_ptr<Tensor> mVar;
};

} // namespace MNN

#endif /* CPUGroupNorm_hpp */
