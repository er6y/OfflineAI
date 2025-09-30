package com.starlocalrag.llamacpp;

public class OpenCLDetector {
    
    static {
        try {
            System.loadLibrary("llamacpp_jni");
        } catch (UnsatisfiedLinkError e) {
            e.printStackTrace();
        }
    }
    
    /**
     * Detect OpenCL availability on the device
     * @return Detection result string with details
     */
    public static native String detectOpenCL();
}
