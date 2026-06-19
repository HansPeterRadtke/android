package com.example.app.engine;

import android.content.Context;
import android.util.Log;
import ai.onnxruntime.*;
import java.io.*;
import java.util.*;
import java.nio.FloatBuffer;
import org.json.*;

public class SttEngine {
  private static final String TAG = "SttEngine";
  private final Context context;
  private OrtEnvironment env;
  private OrtSession session;
  private Map<Integer, String> decoder;

  public SttEngine(Context context) {
    this.context = context;
    try {
      Log.d(TAG, "Initializing STT Engine");
      env = OrtEnvironment.getEnvironment();
      OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
      InputStream modelIn = context.getAssets().open("models/stt/whisper_tiny_en.onnx");
      File modelFile = new File(context.getCacheDir(), "whisper.onnx");
      try (FileOutputStream out = new FileOutputStream(modelFile)) {
        byte[] buffer = new byte[4096];
        int read;
        while ((read = modelIn.read(buffer)) != -1) out.write(buffer, 0, read);
      }
      session = env.createSession(modelFile.getAbsolutePath(), opts);
      decoder = loadDecoder();
    } catch (Exception e) {
      Log.e(TAG, "Failed to load STT model", e);
    }
  }

  private Map<Integer, String> loadDecoder() throws IOException, JSONException {
    Map<Integer, String> map = new HashMap<>();
    InputStream jsonStream = context.getAssets().open("models/stt/vocab.json");
    try {
      StringBuilder sb = new StringBuilder();
      BufferedReader reader = new BufferedReader(new InputStreamReader(jsonStream));
      String line;
      while ((line = reader.readLine()) != null) sb.append(line);
      JSONObject json = new JSONObject(sb.toString());
      Iterator<String> keys = json.keys();
      while (keys.hasNext()) {
        String token = keys.next();
        int id = json.getInt(token);
        map.put(id, token);
      }
    } finally {
      if (jsonStream != null) jsonStream.close();
    }
    return map;
  }

  public String transcribe(float[] pcm) {
    try {
      if (session == null) {
        Log.e(TAG, "ONNX session not initialized");
        return "";
      }
      long[] shape = new long[]{1, pcm.length, 1};
      OnnxTensor inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(pcm), shape);
      OrtSession.Result output = session.run(Collections.singletonMap("audio_input", inputTensor));
      long[] tokens = (long[]) output.get(0).getValue();
      StringBuilder transcript = new StringBuilder();
      for (long token : tokens) {
        String word = decoder.get((int) token);
        if (word != null) transcript.append(word);
      }
      return transcript.toString().trim();
    } catch (Exception e) {
      Log.e(TAG, "Transcription failed", e);
      return "";
    }
  }
}