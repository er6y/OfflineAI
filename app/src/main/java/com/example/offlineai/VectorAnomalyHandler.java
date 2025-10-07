

package com.example.offlineai;

import java.util.Arrays;
import java.util.Random;

/**
 * Vector Anomaly Handler - Vector anomaly processing utility class
 * Used to detect and repair various types of vector anomalies
 */
public class VectorAnomalyHandler {
    private static final String TAG = "OfflineAI_VectorAnomaly";
    
    // Anomaly detection thresholds
    private static final float ZERO_THRESHOLD = 1e-8f;           // Zero value threshold
    private static final float NORM_MIN_THRESHOLD = 1e-6f;       // Minimum norm threshold
    private static final float NORM_MAX_THRESHOLD = 1e6f;        // Maximum norm threshold
    private static final float EXTREME_VALUE_THRESHOLD = 1e3f;   // Extreme value threshold
    private static final float VARIANCE_MIN_THRESHOLD = 1e-6f;   // Minimum variance threshold
    private static final float VARIANCE_MAX_THRESHOLD = 1e3f;    // Maximum variance threshold
    private static final float SKEWNESS_THRESHOLD = 3.0f;        // Skewness threshold
    
    // Random number generator
    private static final Random random = new Random();
    
    /**
     * Vector anomaly type enumeration
     */
    public enum AnomalyType {
        NONE,                    // No anomaly
        NAN_VALUES,             // NaN values
        INFINITE_VALUES,        // Infinite values
        EXTREME_VALUES,         // Extreme value anomaly
        ZERO_VECTOR,            // Zero vector
        DIMENSION_MISMATCH,     // Dimension mismatch
        DIMENSION_MISSING,      // Dimension missing
        DIMENSION_REDUNDANT,    // Dimension redundant
        LOW_VARIANCE,           // Variance too small
        HIGH_VARIANCE,          // Variance too large
        HIGH_SKEWNESS,          // Skewed distribution
        ABNORMAL_CLUSTERING     // Abnormal clustering
    }
    
    /**
     * Vector anomaly detection result
     */
    public static class AnomalyResult {
        public final AnomalyType type;
        public final String description;
        public final boolean isAnomalous;
        public final float severity;  // Anomaly severity level (0-1)
        
        public AnomalyResult(AnomalyType type, String description, boolean isAnomalous, float severity) {
            this.type = type;
            this.description = description;
            this.isAnomalous = isAnomalous;
            this.severity = severity;
        }
    }
    
    /**
     * Comprehensive vector anomaly detection
     * @param vector Input vector
     * @param expectedDimension Expected dimension (-1 means no dimension check)
     * @return Anomaly detection result
     */
    public static AnomalyResult detectAnomalies(float[] vector, int expectedDimension) {
        if (vector == null) {
            return new AnomalyResult(AnomalyType.ZERO_VECTOR, "Vector is null", true, 1.0f);
        }
        
        // 1. Check dimension anomalies
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
        
        // 2. Check numerical anomalies
        AnomalyResult numericalResult = detectNumericalAnomalies(vector);
        if (numericalResult.isAnomalous) {
            return numericalResult;
        }
        
        // 3. Check distribution anomalies
        AnomalyResult distributionResult = detectDistributionAnomalies(vector);
        if (distributionResult.isAnomalous) {
            return distributionResult;
        }
        
        // 4. Check abnormal clustering
        AnomalyResult clusteringResult = detectAbnormalClustering(vector);
        if (clusteringResult.isAnomalous) {
            return clusteringResult;
        }
        
        return new AnomalyResult(AnomalyType.NONE, "No anomalies detected", false, 0.0f);
    }
    
    /**
     * Detect numerical anomalies
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
        
        // NaN value detection
        if (nanCount > 0) {
            float severity = Math.min(1.0f, (float) nanCount / vector.length);
            String desc = String.format("Found %d NaN values out of %d elements", nanCount, vector.length);
            return new AnomalyResult(AnomalyType.NAN_VALUES, desc, true, severity);
        }
        
        // Infinite value detection
        if (infCount > 0) {
            float severity = Math.min(1.0f, (float) infCount / vector.length);
            String desc = String.format("Found %d infinite values out of %d elements", infCount, vector.length);
            return new AnomalyResult(AnomalyType.INFINITE_VALUES, desc, true, severity);
        }
        
        // Extreme value anomaly detection
        if (extremeCount > vector.length * 0.1) { // More than 10% of elements are extreme values
            float severity = Math.min(1.0f, (float) extremeCount / vector.length);
            String desc = String.format("Found %d extreme values out of %d elements", extremeCount, vector.length);
            return new AnomalyResult(AnomalyType.EXTREME_VALUES, desc, true, severity);
        }
        
        // Zero vector detection
        if (zeroCount == vector.length) {
            return new AnomalyResult(AnomalyType.ZERO_VECTOR, "All elements are zero", true, 1.0f);
        }
        
        // Calculate vector norm
        float norm = calculateL2Norm(vector);
        if (norm < NORM_MIN_THRESHOLD) {
            String desc = String.format("Vector norm too small: %.2e", norm);
            return new AnomalyResult(AnomalyType.ZERO_VECTOR, desc, true, 0.8f);
        }
        
        return new AnomalyResult(AnomalyType.NONE, "No numerical anomalies", false, 0.0f);
    }
    
    /**
     * Detect distribution anomalies
     */
    private static AnomalyResult detectDistributionAnomalies(float[] vector) {
        // Calculate statistics
        float mean = calculateMean(vector);
        float variance = calculateVariance(vector, mean);
        float skewness = calculateSkewness(vector, mean, variance);
        
        // Low variance detection
        if (variance < VARIANCE_MIN_THRESHOLD) {
            String desc = String.format("Variance too low: %.2e", variance);
            return new AnomalyResult(AnomalyType.LOW_VARIANCE, desc, true, 0.6f);
        }
        
        // High variance detection
        if (variance > VARIANCE_MAX_THRESHOLD) {
            String desc = String.format("Variance too high: %.2e", variance);
            return new AnomalyResult(AnomalyType.HIGH_VARIANCE, desc, true, 0.7f);
        }
        
        // Skewed distribution detection
        if (Math.abs(skewness) > SKEWNESS_THRESHOLD) {
            String desc = String.format("High skewness: %.2f", skewness);
            return new AnomalyResult(AnomalyType.HIGH_SKEWNESS, desc, true, 0.5f);
        }
        
        return new AnomalyResult(AnomalyType.NONE, "No distribution anomalies", false, 0.0f);
    }
    
    /**
     * Detect abnormal clustering
     * Detect if there are abnormal concentrations of values in certain dimensions of the vector
     */
    private static AnomalyResult detectAbnormalClustering(float[] vector) {
        if (vector.length < 10) {
            return new AnomalyResult(AnomalyType.NONE, "Vector too short for clustering analysis", false, 0.0f);
        }
        
        // Calculate value distribution
        float[] sortedVector = Arrays.copyOf(vector, vector.length);
        Arrays.sort(sortedVector);
        
        // Check if there are too many identical or similar values
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
        
        // If more than 30% of values cluster together, consider it abnormal clustering
        float clusterRatio = (float) maxClusterSize / vector.length;
        if (clusterRatio > 0.3f) {
            String desc = String.format("Abnormal clustering detected: %.1f%% values clustered", clusterRatio * 100);
            return new AnomalyResult(AnomalyType.ABNORMAL_CLUSTERING, desc, true, clusterRatio);
        }
        
        return new AnomalyResult(AnomalyType.NONE, "No abnormal clustering", false, 0.0f);
    }
    
    /**
     * Repair vector anomalies
     * @param vector Input vector
     * @param anomalyType Anomaly type
     * @return Repaired vector
     */
    public static float[] repairVector(float[] vector, AnomalyType anomalyType) {
        return repairVector(vector, anomalyType, -1);
    }
    
    /**
     * Repair vector anomalies (with expected dimension parameter)
     * @param vector Input vector
     * @param anomalyType Anomaly type
     * @param expectedDimension Expected dimension
     * @return Repaired vector
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
     * Repair NaN values
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
     * Repair infinite values
     */
    private static float[] repairInfiniteValues(float[] vector) {
        float[] repaired = Arrays.copyOf(vector, vector.length);
        
        // Find maximum and minimum values among non-infinite values
        float maxFinite = Float.NEGATIVE_INFINITY;
        float minFinite = Float.POSITIVE_INFINITY;
        
        for (float value : vector) {
            if (Float.isFinite(value)) {
                maxFinite = Math.max(maxFinite, value);
                minFinite = Math.min(minFinite, value);
            }
        }
        
        // If no finite values exist, use default values
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
     * Repair extreme value anomalies
     */
    private static float[] repairExtremeValues(float[] vector) {
        float[] repaired = Arrays.copyOf(vector, vector.length);
        
        // Use 3σ principle for clamping
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
     * Repair zero vector
     */
    private static float[] repairZeroVector(float[] vector) {
        float[] repaired = new float[vector.length];
        
        // Generate random unit vector
        for (int i = 0; i < repaired.length; i++) {
            repaired[i] = (float) (random.nextGaussian() * 0.1); // Small random values
        }
        
        // Normalize to unit vector
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
     * Repair low variance
     */
    private static float[] repairLowVariance(float[] vector) {
        float[] repaired = Arrays.copyOf(vector, vector.length);
        float mean = calculateMean(vector);
        
        // Add small random noise
        for (int i = 0; i < repaired.length; i++) {
            float noise = (float) (random.nextGaussian() * 0.01); // 1% noise
            repaired[i] += noise;
        }
        
        float newVariance = calculateVariance(repaired, calculateMean(repaired));
        LogManager.logD(TAG, String.format("Added noise to low variance vector, new variance: %.6f", 
                newVariance));
        return repaired;
    }
    
    /**
     * Repair high variance
     */
    private static float[] repairHighVariance(float[] vector) {
        float[] repaired = Arrays.copyOf(vector, vector.length);
        float mean = calculateMean(vector);
        float std = (float) Math.sqrt(calculateVariance(vector, mean));
        
        // Compress values to reasonable range
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
     * Repair dimension redundancy
     * For dimension redundancy cases, add small random perturbations to increase vector diversity
     */
    private static float[] repairDimensionRedundant(float[] vector) {
        float[] repaired = Arrays.copyOf(vector, vector.length);
        
        // Add small random perturbations to break redundancy
        for (int i = 0; i < repaired.length; i++) {
            float noise = (float) (random.nextGaussian() * 0.005); // 0.5% noise
            repaired[i] += noise;
        }
        
        // Renormalize
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
     * Repair dimension missing
     * Supplement missing dimensions through interpolation or padding
     */
    private static float[] repairDimensionMissing(float[] vector, int expectedDimension) {
        if (expectedDimension <= 0 || vector.length >= expectedDimension) {
            LogManager.logW(TAG, "Cannot repair dimension missing: invalid expected dimension");
            return Arrays.copyOf(vector, vector.length);
        }
        
        float[] repaired = new float[expectedDimension];
        
        // Copy existing dimensions
        System.arraycopy(vector, 0, repaired, 0, vector.length);
        
        // Calculate statistics of existing dimensions
        float mean = calculateMean(vector);
        float std = (float) Math.sqrt(calculateVariance(vector, mean));
        
        // Fill missing dimensions
        for (int i = vector.length; i < expectedDimension; i++) {
            // Use interpolation strategy based on existing data
            if (vector.length > 1) {
                // Linear interpolation + random noise
                float interpolated = vector[i % vector.length];
                float noise = (float) (random.nextGaussian() * std * 0.1);
                repaired[i] = interpolated + noise;
            } else {
                // If only one dimension exists, use mean + noise
                float noise = (float) (random.nextGaussian() * 0.1);
                repaired[i] = mean + noise;
            }
        }
        
        LogManager.logD(TAG, String.format("Repaired dimension missing: filled %d dimensions", 
                expectedDimension - vector.length));
        return repaired;
    }
    
    /**
     * Repair abnormal clustering
     * Disperse clustered values by adding differentiated noise
     */
    private static float[] repairAbnormalClustering(float[] vector) {
        float[] repaired = Arrays.copyOf(vector, vector.length);
        
        // Calculate mean and standard deviation
        float mean = calculateMean(vector);
        float std = (float) Math.sqrt(calculateVariance(vector, mean));
        
        // Add differentiated noise to clustered values
        float[] sortedIndices = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            sortedIndices[i] = i;
        }
        
        // Add different noise to similar values
        for (int i = 0; i < repaired.length; i++) {
            // Add position-related differentiated noise
            float positionNoise = (float) (random.nextGaussian() * std * 0.02); // 2% of standard deviation as noise
            float indexNoise = (float) (Math.sin(i * 0.1) * std * 0.01); // Index-based periodic noise
            repaired[i] += positionNoise + indexNoise;
        }
        
        LogManager.logD(TAG, "Added differentiated noise to repair abnormal clustering");
        return repaired;
    }
    
    /**
     * Comprehensive vector anomaly processing
     * @param vector Input vector
     * @param expectedDimension Expected dimension
     * @return Processed vector
     */
    public static float[] processVector(float[] vector, int expectedDimension) {
        if (vector == null) {
            LogManager.logW(TAG, "Input vector is null");
            return null;
        }
        
        // Detect anomalies
        AnomalyResult result = detectAnomalies(vector, expectedDimension);
        
        if (result.isAnomalous) {
            LogManager.logW(TAG, String.format("Vector anomaly detected: %s (severity: %.2f) - %s", 
                    result.type.name(), result.severity, result.description));
            
            // Repair anomalies
            float[] repairedVector = repairVector(vector, result.type, expectedDimension);
            
            // Verify repair results
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
    
    // Helper calculation methods
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
     * Generate random unit vector
     * @param dimension Vector dimension
     * @return Random unit vector
     */
    public static float[] generateRandomUnitVector(int dimension) {
        float[] vector = new float[dimension];
        
        // Generate random vector
        for (int i = 0; i < dimension; i++) {
            vector[i] = (float) (random.nextGaussian() * 0.1);
        }
        
        // Normalize to unit vector
        float norm = calculateL2Norm(vector);
        if (norm > NORM_MIN_THRESHOLD) {
            for (int i = 0; i < dimension; i++) {
                vector[i] /= norm;
            }
        } else {
            // If norm is too small, generate standard unit vector
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
     * Get vector quality report
     * @param vector Input vector
     * @return Quality report string
     */
    public static String getVectorQualityReport(float[] vector) {
        if (vector == null) {
            return "Vector is null";
        }
        
        StringBuilder report = new StringBuilder();
        report.append("=== Vector Quality Report ===\n");
        report.append(String.format("Dimension: %d\n", vector.length));
        
        // Basic statistics
        float mean = calculateMean(vector);
        float variance = calculateVariance(vector, mean);
        float norm = calculateL2Norm(vector);
        
        report.append(String.format("Mean: %.6f\n", mean));
        report.append(String.format("Variance: %.6f\n", variance));
        report.append(String.format("L2 Norm: %.6f\n", norm));
        
        // Anomaly detection
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
