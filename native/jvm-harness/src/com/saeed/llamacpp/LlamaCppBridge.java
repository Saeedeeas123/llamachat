package com.saeed.llamacpp;

/**
 * JVM stand-in for the Kotlin LlamaCppBridge, with the exact package, class and method names
 * that native/llama_jni.cpp exports. It exists so the real JNI layer can be run under a plain
 * JVM on this machine — the layer the host probe cannot reach.
 */
public final class LlamaCppBridge {

    static {
        System.loadLibrary("llama_jni");
    }

    private LlamaCppBridge() {}

    public static native String nativeVersion();

    public static native long nativeLoadModel(
            String path, int context, int threads, int gpuLayers, int loadMode);

    public static native String nativeDescribe(long handle);

    public static native String nativeChat(
            long handle,
            String[] roles,
            String[] contents,
            int maxTokens,
            float temperature,
            int topK,
            float topP,
            float repeatPenalty,
            int repeatLastN,
            long seed,
            LlamaCppTokenSink sink);

    public static native void nativeFree(long handle);
}