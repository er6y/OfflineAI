package com.example.starlocalrag;

import java.util.Arrays;
import java.util.Random;

/**
 * Vector Anomaly Handler - 向量异常处理工具类
 * 用于检测和修复各种类型的向量异常
 */
public class VectorAnomalyHandler {
    private static final String TAG = "StarLocalRAG_VectorAnomaly";
    
    // 异常检测阈值
    private static final float ZERO_THRESHOLD = 1e-8f;           // 零值阈值
    private static final float NORM_MIN_THRESHOLD = 1e-6f;       // 最小范数阈值
    private static final float NORM_MAX_THRESHOLD = 1e6f;        // 最大范数阈值
    private static final float EXTREME_VALUE_THRESHOLD = 1e3f;   // 极值阈值
    private static final float VARIANCE_MIN_THRESHOLD = 1e-6f;   // 最小方差阈值
    private static final float VARIANCE_MAX_THRESHOLD = 1e3f;    // 最大方差阈值
    private static final float SKEWNESS_THRESHOLD = 3.0f;        // 偏态阈值
    
    // 随机数生成器
    private static final Random random = new Random();
    
    /**
     * 向量异常类型枚举
     */
    public enum AnomalyType {
        NONE,                    // 无异常
        NAN_VALUES,             // NaN值
        INFINITE_VALUES,        // 无穷大值
        EXTREME_VALUES,         // 极值异常
        ZERO_VECTOR,            // 零向量
        DIMENSION_MISMATCH,     // 维度不匹配
        DIMENSION_MISSING,      // 维度缺失
        DIMENSION_REDUNDANT,    // 维度冗余
        LOW_VARIANCE,           // 方差过小
        HIGH_VARIANCE,          // 方差过大
        HIGH_SKEWNESS,          // 偏态分布
        ABNORMAL_CLUSTERING     // 异常聚集
    }
    
    /**
     * 向量异常检测结果
     */
    public static class AnomalyResult {
        public final AnomalyType type;
        public final String description;
        public final boolean isAnomalous;
        public final float severity;  // 异常严重程度 (0-1)
        
        public AnomalyResult(AnomalyType type, String description, boolean isAnomalous, float severity) {
            this.type = type;
            this.description = description;
            this.isAnomalous = isAnomalous;
            this.severity = severity;
        }
    }
    
    /**
     * 综合向量异常检测
     * @param vector 输入向量
     * @param expectedDimension 期望维度（-1表示不检查维度）
     * @return 异常检测结果
     */
    public static AnomalyResult detectAnomalies(float[] vector, int expectedDimension) {
        if (vector == null) {
            return new AnomalyResult(AnomalyType.ZERO_VECTOR, "Vector is null", true, 1.0f);
        }
        
        // 1. 检查维度异常
        if (expectedDimension > 0 && vector.length != expectedDimension) {
            if (vector.length < expectedDimension) {
                String desc = String.format("Dimension missing: expected %d, got %d", expectedDimension, vector.length);
                return new AnomalyResult(AnomalyType.DIMENSION_MISSING, desc, true, 0.9f);
            } else {
                String desc = String.format("Dimension mismatch: expected %d, got %d", expectedDimension, vector.length);
                return new AnomalyResult(AnomalyType.DIMENSION_MISMATCH, desc, true, 0.9f);
            }
        }
        
        if (vector.length == 0) {
            return new AnomalyResult(AnomalyType.ZERO_VECTOR, "Vector is empty", true, 1.0f);
        }
        
        // 2. 检查数值异常
        AnomalyResult numericalResult = detectNumericalAnomalies(vector);
        if (numericalResult.isAnomalous) {
            return numericalResult;
        }
        
        // 3. 检查分布异常
        AnomalyResult distributionResult = detectDistributionAnomalies(vector);
        if (distributionResult.isAnomalous) {
            return distributionResult;
        }
        
        // 4. 检查异常聚集
        AnomalyResult clusteringResult = detectAbnormalClustering(vector);
        if (clusteringResult.isAnomalous) {
            return clusteringResult;
        }
        
        return new AnomalyResult(AnomalyType.NONE, "No anomalies detected", false, 0.0f);
    }
    
    /**
     * 检测数值异常
     */
    private static AnomalyResult detectNumericalAnomalies(float[] vector) {
        int nanCount = 0;
        int infCount = 0;
        int extremeCount = 0;
        int zeroCount = 0;
        
        for (float value : vector) {
            if (Float.isNaN(value)) {
                nanCount++;
            } else if (Float.isInfinite(value)) {
                infCount++;
            } else if (Math.abs(value) > EXTREME_VALUE_THRESHOLD) {
                extremeCount++;
            } else if (Math.abs(value) < ZERO_THRESHOLD) {
                zeroCount++;
            }
        }
        
        // NaN值检测
        if (nanCount > 0) {
            float severity = Math.min(1.0f, (float) nanCount / vector.length);
            String desc = String.format("Found %d NaN values out of %d elements", nanCount, vector.length);
            return new AnomalyResult(AnomalyType.NAN_VALUES, desc, true, severity);
        }
        
        // 无穷大值检测
        if (infCount > 0) {
            float severity = Math.min(1.0f, (float) infCount / vector.length);
            String desc = String.format("Found %d infinite values out of %d elements", infCount, vector.length);
            return new AnomalyResult(AnomalyType.INFINITE_VALUES, desc, true, severity);
        }
        
        // 极值异常检测
        if (extremeCount > vector.length * 0.1) { // 超过10%的元素为极值
            float severity = Math.min(1.0f, (float) extremeCount / vector.length);
            String desc = String.format("Found %d extreme values out of %d elements", extremeCount, vector.length);
            return new AnomalyResult(AnomalyType.EXTREME_VALUES, desc, true, severity);
        }
        
        // 零向量检测
        if (zeroCount == vector.length) {
            return new AnomalyResult(AnomalyType.ZERO_VECTOR, "All elements are zero", true, 1.0f);
        }
        
        // 计算向量范数
        float norm = calculateL2Norm(vector);
        if (norm < NORM_MIN_THRESHOLD) {
            String desc = String.format("Vector norm too small: %.2e", norm);
            return new AnomalyResult(AnomalyType.ZERO_VECTOR, desc, true, 0.8f);
        }
        
        return new AnomalyResult(AnomalyType.NONE, "No numerical anomalies", false, 0.0f);
    }
    
    /**
     * 检测分布异常
     */
    private static AnomalyResult detectDistributionAnomalies(float[] vector) {
        // 计算统计量
        float mean = calculateMean(vector);
        float variance = calculateVariance(vector, mean);
        float skewness = calculateSkewness(vector, mean, variance);
        
        // 方差过小检测
        if (variance < VARIANCE_MIN_THRESHOLD) {
            String desc = String.format("Variance too low: %.2e", variance);
            return new AnomalyResult(AnomalyType.LOW_VARIANCE, desc, true, 0.6f);
        }
        
        // 方差过大检测
        if (variance > VARIANCE_MAX_THRESHOLD) {
            String desc = String.format("Variance too high: %.2e", variance);
            return new AnomalyResult(AnomalyType.HIGH_VARIANCE, desc, true, 0.7f);
        }
        
        // 偏态分布检测
        if (Math.abs(skewness) > SKEWNESS_THRESHOLD) {
            String desc = String.format("High skewness: %.2f", skewness);
            return new AnomalyResult(AnomalyType.HIGH_SKEWNESS, desc, true, 0.5f);
        }
        
        return new AnomalyResult(AnomalyType.NONE, "No distribution anomalies", false, 0.0f);
    }
    
    /**
     * 检测异常聚集
     * 检测向量中是否存在某些维度值异常集中的情况
     */
    private static AnomalyResult detectAbnormalClustering(float[] vector) {
        if (vector.length < 10) {
            return new AnomalyResult(AnomalyType.NONE, "Vector too short for clustering analysis", false, 0.0f);
        }
        
        // 计算值的分布
        float[] sortedVector = Arrays.copyOf(vector, vector.length);
        Arrays.sort(sortedVector);
        
        // 检查是否有过多相同或相近的值
        int maxClusterSize = 0;
        int currentClusterSize = 1;
        float tolerance = 1e-6f;
        
        for (int i = 1; i < sortedVector.length; i++) {
            if (Math.abs(sortedVector[i] - sortedVector[i-1]) < tolerance) {
                currentClusterSize++;
            } else {
                maxClusterSize = Math.max(maxClusterSize, currentClusterSize);
                currentClusterSize = 1;
            }
        }
        maxClusterSize = Math.max(maxClusterSize, currentClusterSize);
        
        // 如果超过30%的值聚集在一起，认为是异常聚集
        float clusterRatio = (float) maxClusterSize / vector.length;
        if (clusterRatio > 0.3f) {
            String desc = String.format("Abnormal clustering detected: %.1f%% values clustered", clusterRatio * 100);
            return new AnomalyResult(AnomalyType.ABNORMAL_CLUSTERING, desc, true, clusterRatio);
        }
        
        return new AnomalyResult(AnomalyType.NONE, "No abnormal clustering", false, 0.0f);
    }
    
    /**
     * 修复向量异常
     * @param vector 输入向量
     * @param anomalyType 异常类型
     * @return 修复后的向量
     */
    public static float[] repairVector(float[] vector, AnomalyType anomalyType) {
        return repairVector(vector, anomalyType, -1);
    }
    
    /**
     * 修复向量异常（带期望维度参数）
     * @param vector 输入向量
     * @param anomalyType 异常类型
     * @param expectedDimension 期望维度
     * @return 修复后的向量
     */
    public static float[] repairVector(float[] vector, AnomalyType anomalyType, int expectedDimension) {
        if (vector == null) {
            LogManager.logW(TAG, "Input vector is null, cannot repair");
            return null;
        }
        
        LogManager.logD(TAG, String.format("Repairing vector anomaly: %s, vector length: %d", 
                anomalyType.name(), vector.length));
        
        switch (anomalyType) {
            case NAN_VALUES:
                return repairNaNValues(vector);
            case INFINITE_VALUES:
                return repairInfiniteValues(vector);
            case EXTREME_VALUES:
                return repairExtremeValues(vector);
            case ZERO_VECTOR:
                return repairZeroVector(vector);
            case DIMENSION_MISSING:
                return repairDimensionMissing(vector, expectedDimension);
            case DIMENSION_REDUNDANT:
                return repairDimensionRedundant(vector);
            case LOW_VARIANCE:
                return repairLowVariance(vector);
            case HIGH_VARIANCE:
                return repairHighVariance(vector);
            case ABNORMAL_CLUSTERING:
                return repairAbnormalClustering(vector);
            default:
                LogManager.logD(TAG, "No repair needed for anomaly type: " + anomalyType.name());
                return Arrays.copyOf(vector, vector.length);
        }
    }
    
    /**
     * 修复NaN值
     */
    private static float[] repairNaNValues(float[] vector) {
        float[] repaired = Arrays.copyOf(vector, vector.length);
        float mean = calculateMeanIgnoreNaN(vector);
        
        int repairedCount = 0;
        for (int i = 0; i < repaired.length; i++) {
            if (Float.isNaN(repaired[i])) {
                repaired[i] = mean;
                repairedCount++;
            }
        }
        
        LogManager.logD(TAG, String.format("Repaired %d NaN values with mean value: %.6f", 
                repairedCount, mean));
        return repaired;
    }
    
    /**
     * 修复无穷大值
     */
    private static float[] repairInfiniteValues(float[] vector) {
        float[] repaired = Arrays.copyOf(vector, vector.length);
        
        // 找到非无穷大值的最大和最小值
        float maxFinite = Float.NEGATIVE_INFINITY;
        float minFinite = Float.POSITIVE_INFINITY;
        
        for (float value : vector) {
            if (Float.isFinite(value)) {
                maxFinite = Math.max(maxFinite, value);
                minFinite = Math.min(minFinite, value);
            }
        }
        
        // 如果没有有限值，使用默认值
        if (!Float.isFinite(maxFinite)) {
            maxFinite = 1.0f;
            minFinite = -1.0f;
        }
        
        int repairedCount = 0;
        for (int i = 0; i < repaired.length; i++) {
            if (Float.isInfinite(repaired[i])) {
                repaired[i] = repaired[i] > 0 ? maxFinite : minFinite;
                repairedCount++;
            }
        }
        
        LogManager.logD(TAG, String.format("Repaired %d infinite values, range: [%.6f, %.6f]", 
                repairedCount, minFinite, maxFinite));
        return repaired;
    }
    
    /**
     * 修复极值异常
     */
    private static float[] repairExtremeValues(float[] vector) {
        float[] repaired = Arrays.copyOf(vector, vector.length);
        
        // 使用3σ原则进行钳制
        float mean = calculateMean(vector);
        float std = (float) Math.sqrt(calculateVariance(vector, mean));
        float upperBound = mean + 3 * std;
        float lowerBound = mean - 3 * std;
        
        int repairedCount = 0;
        for (int i = 0; i < repaired.length; i++) {
            if (repaired[i] > upperBound) {
                repaired[i] = upperBound;
                repairedCount++;
            } else if (repaired[i] < lowerBound) {
                repaired[i] = lowerBound;
                repairedCount++;
            }
        }
        
        LogManager.logD(TAG, String.format("Repaired %d extreme values, bounds: [%.6f, %.6f]", 
                repairedCount, lowerBound, upperBound));
        return repaired;
    }
    
    /**
     * 修复零向量
     */
    private static float[] repairZeroVector(float[] vector) {
        float[] repaired = new float[vector.length];
        
        // 生成随机单位向量
        for (int i = 0; i < repaired.length; i++) {
            repaired[i] = (float) (random.nextGaussian() * 0.1); // 小的随机值
        }
        
        // 归一化为单位向量
        float norm = calculateL2Norm(repaired);
        if (norm > NORM_MIN_THRESHOLD) {
            for (int i = 0; i < repaired.length; i++) {
                repaired[i] /= norm;
            }
        }
        
        LogManager.logD(TAG, String.format("Generated random unit vector to replace zero vector, norm: %.6f", 
                calculateL2Norm(repaired)));
        return repaired;
    }
    
    /**
     * 修复低方差
     */
    private static float[] repairLowVariance(float[] vector) {
        float[] repaired = Arrays.copyOf(vector, vector.length);
        float mean = calculateMean(vector);
        
        // 添加小的随机噪声
        for (int i = 0; i < repaired.length; i++) {
            float noise = (float) (random.nextGaussian() * 0.01); // 1%的噪声
            repaired[i] += noise;
        }
        
        float newVariance = calculateVariance(repaired, calculateMean(repaired));
        LogManager.logD(TAG, String.format("Added noise to low variance vector, new variance: %.6f", 
                newVariance));
        return repaired;
    }
    
    /**
     * 修复高方差
     */
    private static float[] repairHighVariance(float[] vector) {
        float[] repaired = Arrays.copyOf(vector, vector.length);
        float mean = calculateMean(vector);
        float std = (float) Math.sqrt(calculateVariance(vector, mean));
        
        // 将值压缩到合理范围内
        float compressionFactor = 0.5f;
        for (int i = 0; i < repaired.length; i++) {
            repaired[i] = mean + (repaired[i] - mean) * compressionFactor;
        }
        
        float newVariance = calculateVariance(repaired, calculateMean(repaired));
        LogManager.logD(TAG, String.format("Compressed high variance vector, new variance: %.6f", 
                newVariance));
        return repaired;
    }
    
    /**
     * 修复维度冗余
     * 对于维度冗余的情况，通过添加小的随机扰动来增加向量的多样性
     */
    private static float[] repairDimensionRedundant(float[] vector) {
        float[] repaired = Arrays.copyOf(vector, vector.length);
        
        // 添加小的随机扰动来打破冗余
        for (int i = 0; i < repaired.length; i++) {
            float noise = (float) (random.nextGaussian() * 0.005); // 0.5%的噪声
            repaired[i] += noise;
        }
        
        // 重新归一化
        float norm = calculateL2Norm(repaired);
        if (norm > NORM_MIN_THRESHOLD) {
            for (int i = 0; i < repaired.length; i++) {
                repaired[i] /= norm;
            }
        }
        
        LogManager.logD(TAG, "Added noise to repair dimension redundancy and renormalized vector");
        return repaired;
    }
    
    /**
     * 修复维度缺失
     * 通过插值或填充来补充缺失的维度
     */
    private static float[] repairDimensionMissing(float[] vector, int expectedDimension) {
        if (expectedDimension <= 0 || vector.length >= expectedDimension) {
            LogManager.logW(TAG, "Cannot repair dimension missing: invalid expected dimension");
            return Arrays.copyOf(vector, vector.length);
        }
        
        float[] repaired = new float[expectedDimension];
        
        // 复制现有维度
        System.arraycopy(vector, 0, repaired, 0, vector.length);
        
        // 计算现有维度的统计信息
        float mean = calculateMean(vector);
        float std = (float) Math.sqrt(calculateVariance(vector, mean));
        
        // 填充缺失的维度
        for (int i = vector.length; i < expectedDimension; i++) {
            // 使用基于现有数据的插值策略
            if (vector.length > 1) {
                // 线性插值 + 随机噪声
                float interpolated = vector[i % vector.length];
                float noise = (float) (random.nextGaussian() * std * 0.1);
                repaired[i] = interpolated + noise;
            } else {
                // 如果只有一个维度，使用均值 + 噪声
                float noise = (float) (random.nextGaussian() * 0.1);
                repaired[i] = mean + noise;
            }
        }
        
        LogManager.logD(TAG, String.format("Repaired dimension missing: filled %d dimensions", 
                expectedDimension - vector.length));
        return repaired;
    }
    
    /**
     * 修复异常聚集
     * 通过添加差异化噪声来分散聚集的值
     */
    private static float[] repairAbnormalClustering(float[] vector) {
        float[] repaired = Arrays.copyOf(vector, vector.length);
        
        // 计算均值和标准差
        float mean = calculateMean(vector);
        float std = (float) Math.sqrt(calculateVariance(vector, mean));
        
        // 对聚集的值添加差异化噪声
        float[] sortedIndices = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            sortedIndices[i] = i;
        }
        
        // 为相似的值添加不同的噪声
        for (int i = 0; i < repaired.length; i++) {
            // 添加与位置相关的差异化噪声
            float positionNoise = (float) (random.nextGaussian() * std * 0.02); // 2%的标准差作为噪声
            float indexNoise = (float) (Math.sin(i * 0.1) * std * 0.01); // 基于索引的周期性噪声
            repaired[i] += positionNoise + indexNoise;
        }
        
        LogManager.logD(TAG, "Added differentiated noise to repair abnormal clustering");
        return repaired;
    }
    
    /**
     * 综合向量异常处理
     * @param vector 输入向量
     * @param expectedDimension 期望维度
     * @return 处理后的向量
     */
    public static float[] processVector(float[] vector, int expectedDimension) {
        if (vector == null) {
            LogManager.logW(TAG, "Input vector is null");
            return null;
        }
        
        // 检测异常
        AnomalyResult result = detectAnomalies(vector, expectedDimension);
        
        if (result.isAnomalous) {
            LogManager.logW(TAG, String.format("Vector anomaly detected: %s (severity: %.2f) - %s", 
                    result.type.name(), result.severity, result.description));
            
            // 修复异常
            float[] repairedVector = repairVector(vector, result.type, expectedDimension);
            
            // 验证修复结果
            AnomalyResult verifyResult = detectAnomalies(repairedVector, expectedDimension);
            if (verifyResult.isAnomalous) {
                LogManager.logW(TAG, String.format("Vector still anomalous after repair: %s", 
                        verifyResult.description));
            } else {
                LogManager.logD(TAG, "Vector anomaly successfully repaired");
            }
            
            return repairedVector;
        } else {
            LogManager.logD(TAG, "Vector is normal, no processing needed");
            return Arrays.copyOf(vector, vector.length);
        }
    }
    
    // 辅助计算方法
    private static float calculateL2Norm(float[] vector) {
        float sum = 0.0f;
        for (float value : vector) {
            sum += value * value;
        }
        return (float) Math.sqrt(sum);
    }
    
    private static float calculateMean(float[] vector) {
        float sum = 0.0f;
        for (float value : vector) {
            sum += value;
        }
        return sum / vector.length;
    }
    
    private static float calculateMeanIgnoreNaN(float[] vector) {
        float sum = 0.0f;
        int count = 0;
        for (float value : vector) {
            if (!Float.isNaN(value)) {
                sum += value;
                count++;
            }
        }
        return count > 0 ? sum / count : 0.0f;
    }
    
    private static float calculateVariance(float[] vector, float mean) {
        float sum = 0.0f;
        for (float value : vector) {
            float diff = value - mean;
            sum += diff * diff;
        }
        return sum / vector.length;
    }
    
    private static float calculateSkewness(float[] vector, float mean, float variance) {
        if (variance < ZERO_THRESHOLD) {
            return 0.0f;
        }
        
        float sum = 0.0f;
        float std = (float) Math.sqrt(variance);
        
        for (float value : vector) {
            float standardized = (value - mean) / std;
            sum += standardized * standardized * standardized;
        }
        
        return sum / vector.length;
    }
    
    /**
     * 生成随机单位向量
     * @param dimension 向量维度
     * @return 随机单位向量
     */
    public static float[] generateRandomUnitVector(int dimension) {
        float[] vector = new float[dimension];
        
        // 生成随机向量
        for (int i = 0; i < dimension; i++) {
            vector[i] = (float) (random.nextGaussian() * 0.1);
        }
        
        // 归一化为单位向量
        float norm = calculateL2Norm(vector);
        if (norm > NORM_MIN_THRESHOLD) {
            for (int i = 0; i < dimension; i++) {
                vector[i] /= norm;
            }
        } else {
            // 如果范数太小，生成标准单位向量
            vector[0] = 1.0f;
            for (int i = 1; i < dimension; i++) {
                vector[i] = 0.0f;
            }
        }
        
        LogManager.logD(TAG, String.format("Generated random unit vector with dimension %d, norm: %.6f", 
                dimension, calculateL2Norm(vector)));
        return vector;
    }
    
    /**
     * 获取向量质量报告
     * @param vector 输入向量
     * @return 质量报告字符串
     */
    public static String getVectorQualityReport(float[] vector) {
        if (vector == null) {
            return "Vector is null";
        }
        
        StringBuilder report = new StringBuilder();
        report.append("=== Vector Quality Report ===\n");
        report.append(String.format("Dimension: %d\n", vector.length));
        
        // 基本统计
        float mean = calculateMean(vector);
        float variance = calculateVariance(vector, mean);
        float norm = calculateL2Norm(vector);
        
        report.append(String.format("Mean: %.6f\n", mean));
        report.append(String.format("Variance: %.6f\n", variance));
        report.append(String.format("L2 Norm: %.6f\n", norm));
        
        // 异常检测
        AnomalyResult result = detectAnomalies(vector, -1);
        report.append(String.format("Anomaly Status: %s\n", result.isAnomalous ? "ANOMALOUS" : "NORMAL"));
        if (result.isAnomalous) {
            report.append(String.format("Anomaly Type: %s\n", result.type.name()));
            report.append(String.format("Severity: %.2f\n", result.severity));
            report.append(String.format("Description: %s\n", result.description));
        }
        
        return report.toString();
    }
}