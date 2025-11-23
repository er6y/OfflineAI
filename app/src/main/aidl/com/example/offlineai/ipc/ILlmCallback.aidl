package com.example.offlineai.ipc;

interface ILlmCallback {
    void onToken(String taskId, String token);
    void onComplete(String taskId, String fullResponse);
    void onError(String taskId, String errorMessage);
}
