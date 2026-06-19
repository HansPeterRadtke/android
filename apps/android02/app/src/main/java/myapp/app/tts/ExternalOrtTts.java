// app/src/main/java/myapp/app/tts/ExternalOrtTts.java
package myapp.app.tts;

import android.content.Context;

import java.io.File;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

public final class ExternalOrtTts {

    private static volatile boolean initialized = false;

    private static OrtEnvironment environment;
    private static OrtSession session;

    private ExternalOrtTts() {
    }

    public static synchronized void initialize(Context context) {
        if (initialized) {
            return;
        }

        if (context == null) {
            throw new IllegalStateException("ExternalOrtTts.initialize: context is null");
        }

        File modelsDir = context.getExternalFilesDir("models");
        if (modelsDir == null) {
            throw new IllegalStateException("ExternalOrtTts.initialize: getExternalFilesDir(\"models\") returned null");
        }

        File modelFile = new File(modelsDir, "kokoro.onnx");
        if (!modelFile.exists()) {
            throw new IllegalStateException("Kokoro ONNX model not found at: " + modelFile.getAbsolutePath());
        }

        try {
            environment = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions sessionOptions = new OrtSession.SessionOptions();

            int cores = Runtime.getRuntime().availableProcessors();
            sessionOptions.setIntraOpNumThreads(cores);
            sessionOptions.setInterOpNumThreads(1);

            try {
                sessionOptions.addConfigEntry("session.use_xnnpack", "1");
            } catch (Throwable ignored) {
            }

            session = environment.createSession(modelFile.getAbsolutePath(), sessionOptions);
            initialized = true;
        } catch (OrtException e) {
            throw new IllegalStateException("ExternalOrtTts.initialize OrtException: " + e.getMessage(), e);
        }
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static OrtSession getSession() {
        if (!initialized) {
            throw new IllegalStateException("ExternalOrtTts is not initialized. Call initialize(context) first.");
        }
        return session;
    }

    public static synchronized void shutdown() {
        if (!initialized) {
            return;
        }

        try {
            if (session != null) {
                session.close();
            }
        } catch (Throwable ignored) {
        }

        try {
            if (environment != null) {
                environment.close();
            }
        } catch (Throwable ignored) {
        }

        session = null;
        environment = null;
        initialized = false;
    }
}
