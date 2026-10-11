package com.saeed.llamacpp;

/** JVM stand-in for the Kotlin LlamaCppTokenSink (JNI signature "([B)Z"). */
public interface LlamaCppTokenSink {
    /** @return true to cancel generation after this token. */
    boolean onToken(byte[] utf8);
}