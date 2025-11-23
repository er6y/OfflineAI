package com.example.offlineai.ipc;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Lightweight status snapshot for inference service.
 * Used by main process to inspect current LLM state.
 */
public class ServiceStatus implements Parcelable {
    public boolean hasActiveTask;
    public String currentTaskId;
    public String modelState;
    public boolean llmBusy;
    public boolean llmRunning;

    public ServiceStatus() {
    }

    protected ServiceStatus(Parcel in) {
        hasActiveTask = in.readByte() != 0;
        currentTaskId = in.readString();
        modelState = in.readString();
        llmBusy = in.readByte() != 0;
        llmRunning = in.readByte() != 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeByte((byte) (hasActiveTask ? 1 : 0));
        dest.writeString(currentTaskId);
        dest.writeString(modelState);
        dest.writeByte((byte) (llmBusy ? 1 : 0));
        dest.writeByte((byte) (llmRunning ? 1 : 0));
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<ServiceStatus> CREATOR = new Creator<ServiceStatus>() {
        @Override
        public ServiceStatus createFromParcel(Parcel in) {
            return new ServiceStatus(in);
        }

        @Override
        public ServiceStatus[] newArray(int size) {
            return new ServiceStatus[size];
        }
    };
}
