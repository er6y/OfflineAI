package com.example.offlineai.ipc;

interface IInferenceStatusCallback {
    void onModelStateChanged(String component, String modelPath,
                             String state, boolean busy, int threads);

    void onRerankProgress(String taskId, int current, int total);
}
