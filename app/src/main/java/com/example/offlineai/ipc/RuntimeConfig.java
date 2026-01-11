package com.example.offlineai.ipc;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Runtime configuration snapshot pushed from the main process to the
 * inference process. The inference process must NOT read .config directly
 * and should instead rely on this in-memory configuration.
 */
public class RuntimeConfig implements Parcelable {
    // General LLM / engine settings
    public int threads;
    public int historyRounds;
    public int maxSequenceLength;
    public int maxNewTokens;

    // Thinking / manual params
    public boolean noThinking;
    public boolean agentModeEnabled;  // Agent mode enabled flag
    public boolean priorityManualParams;
    public float manualTemperature;
    public int manualTopK;
    public float manualTopP;
    public float manualRepeatPenalty;

    // Global LLM (LlamaCpp-style) parameters used when manual params are not prioritized
    public float llamaTemperature;
    public int llamaTopK;
    public float llamaTopP;
    public float llamaRepeatPenalty;
    public int llamaSeed;

    // Diffusion settings
    public int diffusionMemoryMode;
    public int diffusionImageSize;  // Legacy: single size for square images
    public int diffusionImageWidth;  // Output image width (for non-square)
    public int diffusionImageHeight; // Output image height (for non-square)
    public int diffusionSteps;
    public float diffusionCfg;  // CFG scale (0.0-10.0)
    public int diffusionSeed;
    public boolean diffusionSeedRandom;
    public boolean diffusionTextEncoderOnCPU = true; // Force text_encoder on CPU to avoid GPU buffer size limit
    public int diffusionGpuMemoryMode;  // 0=AUTO, 1=BUFFER, 2=IMAGE
    public int diffusionPrecisionMode;  // 0=AUTO, 1=LOW(FP16), 2=NORMAL(FP32), 3=HIGH(FP32)

    // Prompting / chat folder
    public String systemPrompt;
    public String currentChatFolder;

    // Backend / TTS
    public String backendPreference;   // CPU / OPENCL / VULKAN / NNAPI
    public String ttsModel;            // Selected TTS model name
    public int ttsDitSteps;            // Omni DiT steps
    
    // VL (Vision-Language) settings
    public int imagePreprocessSize;    // VL image preprocess size (0=Auto, 420-800=manual)

    // Model base paths (resolved by main process)
    public String llmModelBasePath;
    public String asrModelBasePath;
    public String ttsModelBasePath;
    public String rerankerModelBasePath;

    public RuntimeConfig() {
    }

    protected RuntimeConfig(Parcel in) {
        threads = in.readInt();
        historyRounds = in.readInt();
        maxSequenceLength = in.readInt();
        maxNewTokens = in.readInt();

        noThinking = in.readByte() != 0;
        agentModeEnabled = in.readByte() != 0;
        priorityManualParams = in.readByte() != 0;
        manualTemperature = in.readFloat();
        manualTopK = in.readInt();
        manualTopP = in.readFloat();
        manualRepeatPenalty = in.readFloat();

        llamaTemperature = in.readFloat();
        llamaTopK = in.readInt();
        llamaTopP = in.readFloat();
        llamaRepeatPenalty = in.readFloat();
        llamaSeed = in.readInt();

        diffusionMemoryMode = in.readInt();
        diffusionImageSize = in.readInt();
        diffusionImageWidth = in.readInt();
        diffusionImageHeight = in.readInt();
        diffusionSteps = in.readInt();
        diffusionCfg = in.readFloat();
        diffusionSeed = in.readInt();
        diffusionSeedRandom = in.readByte() != 0;
        diffusionTextEncoderOnCPU = in.readByte() != 0;
        diffusionGpuMemoryMode = in.readInt();
        diffusionPrecisionMode = in.readInt();

        systemPrompt = in.readString();
        currentChatFolder = in.readString();

        backendPreference = in.readString();
        ttsModel = in.readString();
        ttsDitSteps = in.readInt();
        imagePreprocessSize = in.readInt();

        llmModelBasePath = in.readString();
        asrModelBasePath = in.readString();
        ttsModelBasePath = in.readString();
        rerankerModelBasePath = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(threads);
        dest.writeInt(historyRounds);
        dest.writeInt(maxSequenceLength);
        dest.writeInt(maxNewTokens);

        dest.writeByte((byte) (noThinking ? 1 : 0));
        dest.writeByte((byte) (agentModeEnabled ? 1 : 0));
        dest.writeByte((byte) (priorityManualParams ? 1 : 0));
        dest.writeFloat(manualTemperature);
        dest.writeInt(manualTopK);
        dest.writeFloat(manualTopP);
        dest.writeFloat(manualRepeatPenalty);

        dest.writeFloat(llamaTemperature);
        dest.writeInt(llamaTopK);
        dest.writeFloat(llamaTopP);
        dest.writeFloat(llamaRepeatPenalty);
        dest.writeInt(llamaSeed);

        dest.writeInt(diffusionMemoryMode);
        dest.writeInt(diffusionImageSize);
        dest.writeInt(diffusionImageWidth);
        dest.writeInt(diffusionImageHeight);
        dest.writeInt(diffusionSteps);
        dest.writeFloat(diffusionCfg);
        dest.writeInt(diffusionSeed);
        dest.writeByte((byte) (diffusionSeedRandom ? 1 : 0));
        dest.writeByte((byte) (diffusionTextEncoderOnCPU ? 1 : 0));
        dest.writeInt(diffusionGpuMemoryMode);
        dest.writeInt(diffusionPrecisionMode);

        dest.writeString(systemPrompt);
        dest.writeString(currentChatFolder);

        dest.writeString(backendPreference);
        dest.writeString(ttsModel);
        dest.writeInt(ttsDitSteps);
        dest.writeInt(imagePreprocessSize);

        dest.writeString(llmModelBasePath);
        dest.writeString(asrModelBasePath);
        dest.writeString(ttsModelBasePath);
        dest.writeString(rerankerModelBasePath);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<RuntimeConfig> CREATOR = new Creator<RuntimeConfig>() {
        @Override
        public RuntimeConfig createFromParcel(Parcel in) {
            return new RuntimeConfig(in);
        }

        @Override
        public RuntimeConfig[] newArray(int size) {
            return new RuntimeConfig[size];
        }
    };
}
