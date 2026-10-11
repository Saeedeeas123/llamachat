package com.saeed.llamacpp;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs the real JNI layer (native/llama_jni.cpp) under a plain JVM.
 *
 * The host probe (linux_probe.cpp) exercises llama_core directly, which means the JNI
 * marshalling and the per-token Java callback were never covered by any test. This harness
 * closes that gap: it calls the exported JNI entry points with the same shapes the app uses.
 *
 * Cases:
 *   1. several turns with a streaming sink that accumulates and decodes, like the app does
 *   2. cancellation: the sink returns true part-way through
 *   3. a throwing sink: must not unwind through native code
 */
public final class HarnessMain {

    private static final AtomicInteger failures = new AtomicInteger();

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("usage: HarnessMain <model.gguf> [turns] [maxTokens]");
            System.exit(2);
        }
        final String model = args[0];
        final int turns = args.length > 1 ? Integer.parseInt(args[1]) : 4;
        final int maxTokens = args.length > 2 ? Integer.parseInt(args[2]) : 96;

        System.out.println("llama.cpp : " + LlamaCppBridge.nativeVersion());

        final long started = System.currentTimeMillis();
        final long handle = LlamaCppBridge.nativeLoadModel(model, 2048, 6, 0);
        System.out.printf("loaded    : %.2fs%n", (System.currentTimeMillis() - started) / 1000.0);
        System.out.println("describe  : " + LlamaCppBridge.nativeDescribe(handle));
        if (handle == 0L) {
            System.err.println("FAIL: load returned handle 0");
            System.exit(1);
        }

        final String[] roles = {"system", "user"};
        final String[] contents = {
            "You are a helpful assistant running entirely offline on this phone. Keep answers short.",
            "Say hello in one short sentence.",
        };

        // ---- case 1: streaming turns, exactly like the app -----------------------------
        for (int turn = 1; turn <= turns; turn++) {
            final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            final int[] tokenCount = {0};
            LlamaCppTokenSink sink = utf8 -> {
                buffer.write(utf8, 0, utf8.length);
                tokenCount[0]++;
                // The app re-decodes the whole accumulated buffer on every token.
                String soFar = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
                if (soFar.isEmpty() && tokenCount[0] > 1) {
                    System.err.println("unexpected empty accumulation");
                }
                return false;
            };
            long g0 = System.currentTimeMillis();
            String reply = LlamaCppBridge.nativeChat(
                    handle, roles, contents, maxTokens, 0.8f, 40, 0.95f, 1.1f, 64, -1L, sink);
            double seconds = (System.currentTimeMillis() - g0) / 1000.0;
            System.out.printf("turn %-2d   : %d tokens, %d chars, %.2fs -> %s%n",
                    turn, tokenCount[0], reply.length(), seconds,
                    reply.replace("\n", "\\n"));
            if (reply.isEmpty()) {
                failures.incrementAndGet();
                System.err.println("FAIL: turn " + turn + " produced no text");
            }
        }

        // ---- case 2: cancellation ------------------------------------------------------
        // A deliberately long-winded prompt: with a short one the model hits end-of-generation
        // after ~3 tokens and the test cannot tell "cancelled" from "finished on its own".
        final String[] longRoles = {"user"};
        final String[] longContents = {"Count from 1 to 50, one number per line, nothing else."};
        final int[] seen = {0};
        LlamaCppTokenSink cancelling = utf8 -> ++seen[0] >= 5;
        String cancelled = LlamaCppBridge.nativeChat(
                handle, longRoles, longContents, 512, 0.8f, 40, 0.95f, 1.1f, 64, -1L, cancelling);
        System.out.printf("cancel    : stopped after %d tokens, %d chars%n", seen[0], cancelled.length());
        if (seen[0] != 5) {
            failures.incrementAndGet();
            System.err.println("FAIL: cancellation did not stop at the requested token");
        }

        // ---- case 3: a sink that throws ------------------------------------------------
        final int[] thrown = {0};
        LlamaCppTokenSink throwing = utf8 -> {
            if (++thrown[0] == 3) {
                throw new IllegalStateException("sink failure on purpose");
            }
            return false;
        };
        String afterThrow = LlamaCppBridge.nativeChat(
                handle, longRoles, longContents, 512, 0.8f, 40, 0.95f, 1.1f, 64, -1L, throwing);
        System.out.printf("throwing  : stopped after %d tokens, %d chars%n",
                thrown[0], afterThrow.length());
        if (thrown[0] != 3) {
            failures.incrementAndGet();
            System.err.println("FAIL: a throwing sink should have stopped generation");
        }

        // ---- case 4: null sink (no streaming) ------------------------------------------
        String plain = LlamaCppBridge.nativeChat(
                handle, roles, contents, 32, 0.8f, 40, 0.95f, 1.1f, 64, -1L, null);
        System.out.printf("null sink : %d chars%n", plain.length());
        if (plain.isEmpty()) {
            failures.incrementAndGet();
            System.err.println("FAIL: null sink produced no text");
        }

        LlamaCppBridge.nativeFree(handle);
        System.out.println(failures.get() == 0
                ? "HARNESS OK - JNI layer survived every case"
                : "HARNESS FAILED with " + failures.get() + " problem(s)");
        System.exit(failures.get() == 0 ? 0 : 1);
    }
}