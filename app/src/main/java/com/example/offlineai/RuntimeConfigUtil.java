package com.example.offlineai;

import android.content.Context;

import com.example.offlineai.ipc.InferenceClient;
import com.example.offlineai.ipc.RuntimeConfig;

/**
 * Utility for building and pushing RuntimeConfig from the main process
 * to the inference process via IPC.
 */
public final class RuntimeConfigUtil {

    private RuntimeConfigUtil() {}

    /**
     * Build a RuntimeConfig snapshot from ConfigManager and Settings.
     */
    public static RuntimeConfig buildRuntimeConfig(Context context) {
        if (context == null) {
            return new RuntimeConfig();
        }
        Context appCtx = context.getApplicationContext();
        RuntimeConfig config = new RuntimeConfig();

        // Threads and sequence settings
        config.threads = ConfigManager.getThreads(appCtx);
        config.historyRounds = ConfigManager.getHistoryRounds(appCtx);
        config.maxSequenceLength = ConfigManager.getMaxSequenceLength(appCtx);
        config.maxNewTokens = ConfigManager.getMaxNewTokens(appCtx);

        // Thinking and manual parameters
        config.noThinking = ConfigManager.getNoThinking(appCtx);
        config.priorityManualParams = ConfigManager.getPriorityManualParams(appCtx);
        config.manualTemperature = ConfigManager.getManualTemperature(appCtx);
        config.manualTopK = ConfigManager.getManualTopK(appCtx);
        config.manualTopP = ConfigManager.getManualTopP(appCtx);
        config.manualRepeatPenalty = ConfigManager.getManualRepeatPenalty(appCtx);

        // Global LLM parameters (used when manual params are not prioritized)
        config.llamaTemperature = ConfigManager.getLlamaCppTemperature(appCtx);
        config.llamaTopK = ConfigManager.getLlamaCppTopK(appCtx);
        config.llamaTopP = ConfigManager.getLlamaCppTopP(appCtx);
        config.llamaRepeatPenalty = ConfigManager.getLlamaCppRepetitionPenalty(appCtx);
        config.llamaSeed = ConfigManager.getLlamaCppSeed(appCtx);

        // Diffusion settings
        config.diffusionMemoryMode = ConfigManager.getDiffusionMemoryMode(appCtx);
        config.diffusionSteps = ConfigManager.getDiffusionSteps(appCtx);
        config.diffusionSeed = ConfigManager.getDiffusionSeed(appCtx);
        config.diffusionSeedRandom = ConfigManager.getDiffusionSeedRandom(appCtx);

        // Prompting and chat folder
        config.systemPrompt = ConfigManager.getSystemPrompt(appCtx);
        config.currentChatFolder = ConfigManager.getCurrentChatFolder(appCtx);

        // Backend and TTS
        config.backendPreference = SettingsFragment.getBackendPreference(appCtx);
        config.ttsModel = ConfigManager.getString(appCtx, ConfigManager.KEY_TTS_MODEL, ConfigManager.DEFAULT_TTS_MODEL);
        config.ttsDitSteps = ConfigManager.getTtsDitSteps(appCtx);

        // Model base paths
        config.llmModelBasePath = ConfigManager.getModelPath(appCtx);
        config.asrModelBasePath = ConfigManager.getAsrModelPath(appCtx);
        config.ttsModelBasePath = ConfigManager.getTtsModelPath(appCtx);
        config.rerankerModelBasePath = ConfigManager.getRerankerModelPath(appCtx);

        return config;
    }

    /**
     * Build and push RuntimeConfig to the inference process.
     */
    public static void pushToInference(Context context) {
        if (context == null) {
            return;
        }
        Context appCtx = context.getApplicationContext();
        RuntimeConfig config = buildRuntimeConfig(appCtx);
        InferenceClient client = InferenceClient.getInstance(appCtx);
        client.updateRuntimeConfig(config);
    }
}
