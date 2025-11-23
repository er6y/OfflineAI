package com.example.offlineai;

import androidx.annotation.Nullable;

import com.example.offlineai.ipc.RuntimeConfig;

/**
 * Process-local holder for the latest RuntimeConfig pushed from the
 * main process. Used primarily by the inference process so that it
 * never needs to read the .config file directly.
 */
public final class RuntimeConfigHolder {
    private static final Object LOCK = new Object();
    private static RuntimeConfig current;

    private RuntimeConfigHolder() {}

    public static void update(RuntimeConfig config) {
        if (config == null) {
            return;
        }
        synchronized (LOCK) {
            current = config;
        }
    }

    @Nullable
    public static RuntimeConfig get() {
        synchronized (LOCK) {
            return current;
        }
    }

    public static int getThreadsOrDefault(int defaultValue) {
        RuntimeConfig cfg;
        synchronized (LOCK) {
            cfg = current;
        }
        return (cfg != null && cfg.threads > 0) ? cfg.threads : defaultValue;
    }

    public static int getHistoryRoundsOrDefault(int defaultValue) {
        RuntimeConfig cfg;
        synchronized (LOCK) {
            cfg = current;
        }
        return (cfg != null && cfg.historyRounds >= 0) ? cfg.historyRounds : defaultValue;
    }

    public static int getMaxSequenceLengthOrDefault(int defaultValue) {
        RuntimeConfig cfg;
        synchronized (LOCK) {
            cfg = current;
        }
        return (cfg != null && cfg.maxSequenceLength > 0) ? cfg.maxSequenceLength : defaultValue;
    }

    public static int getMaxNewTokensOrDefault(int defaultValue) {
        RuntimeConfig cfg;
        synchronized (LOCK) {
            cfg = current;
        }
        return (cfg != null && cfg.maxNewTokens > 0) ? cfg.maxNewTokens : defaultValue;
    }

    public static String getBackendPreferenceOrDefault(String defaultValue) {
        RuntimeConfig cfg;
        synchronized (LOCK) {
            cfg = current;
        }
        if (cfg == null || cfg.backendPreference == null || cfg.backendPreference.isEmpty()) {
            return defaultValue;
        }
        return cfg.backendPreference;
    }

    public static String getCurrentChatFolderOrNull() {
        RuntimeConfig cfg;
        synchronized (LOCK) {
            cfg = current;
        }
        return (cfg != null && cfg.currentChatFolder != null && !cfg.currentChatFolder.isEmpty())
                ? cfg.currentChatFolder
                : null;
    }

    public static int getDiffusionMemoryModeOrDefault(int defaultValue) {
        RuntimeConfig cfg;
        synchronized (LOCK) {
            cfg = current;
        }
        return (cfg != null && cfg.diffusionMemoryMode >= 0) ? cfg.diffusionMemoryMode : defaultValue;
    }

    public static int getDiffusionStepsOrDefault(int defaultValue) {
        RuntimeConfig cfg;
        synchronized (LOCK) {
            cfg = current;
        }
        return (cfg != null && cfg.diffusionSteps > 0) ? cfg.diffusionSteps : defaultValue;
    }

    public static boolean isDiffusionSeedRandomOrDefault(boolean defaultValue) {
        RuntimeConfig cfg;
        synchronized (LOCK) {
            cfg = current;
        }
        return (cfg != null) ? cfg.diffusionSeedRandom : defaultValue;
    }

    public static int getDiffusionSeedOrDefault(int defaultValue) {
        RuntimeConfig cfg;
        synchronized (LOCK) {
            cfg = current;
        }
        return (cfg != null) ? cfg.diffusionSeed : defaultValue;
    }

    public static String getTtsModelOrDefault(String defaultValue) {
        RuntimeConfig cfg;
        synchronized (LOCK) {
            cfg = current;
        }
        if (cfg == null || cfg.ttsModel == null || cfg.ttsModel.isEmpty()) {
            return defaultValue;
        }
        return cfg.ttsModel;
    }

    public static int getTtsDitStepsOrDefault(int defaultValue) {
        RuntimeConfig cfg;
        synchronized (LOCK) {
            cfg = current;
        }
        return (cfg != null && cfg.ttsDitSteps > 0) ? cfg.ttsDitSteps : defaultValue;
    }
}
