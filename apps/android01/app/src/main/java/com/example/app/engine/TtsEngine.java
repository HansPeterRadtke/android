package com.example.app.engine;

import android.content.Context;
import android.util.Log;
import ai.onnxruntime.*;
import java.io.*;
import java.util.*;
import java.nio.FloatBuffer;

public class TtsEngine {
  private static final String TAG = "TtsEngine";
  private final Context context;
  private OrtEnvironment env;
  private OrtSession session;
  private Map<String, Integer> tokenMap;

  public TtsEngine(Context context) {
    this.context = context;
    try {
      Log.d(TAG, "Initializing TTS Engine");
      env = OrtEnvironment.getEnvironment();
      OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
      InputStream modelIn = context.getAssets().open("models/tts/vits_ljspeech.onnx");
      File modelFile = new File(context.getCacheDir(), "vits.onnx");
      try (FileOutputStream out = new FileOutputStream(modelFile)) {
        byte[] buffer = new byte[4096];
        int read;
        while ((read = modelIn.read(buffer)) != -1) out.write(buffer, 0, read);
      }
      session = env.createSession(modelFile.getAbsolutePath(), opts);
      tokenMap = loadTokens();
    } catch (Exception e) {
      Log.e(TAG, "Failed to load TTS model", e);
    }
  }

  private Map<String, Integer> loadTokens() throws IOException {
    Map<String, Integer> map = new HashMap<>();
    InputStream stream = context.getAssets().open("models/tts/tokens.txt");
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
      String line;
      int index = 0;
      while ((line = reader.readLine()) != null) {
        map.put(line.trim(), index++);
      }
    } finally {
      if (stream != null) stream.close();
    }
    return map;
  }

  private int[] tokenize(String text) {
    text = text.toLowerCase().replaceAll("[^a-zA-Z ]", "");
    List<Integer> tokens = new ArrayList<>();
    for (char c : text.toCharArray()) {
      Integer token = tokenMap.get(String.valueOf(c));
      if (token != null) tokens.add(token);
    }
    return tokens.stream().mapToInt(i -> i).toArray();
  }

  public short[] synthesize(String text) {
    try {
      if (session == null) {
        Log.e(TAG, "ONNX session not initialized");
        return new short[0];
      }
      int[] tokenIds = tokenize(text);
      long[] shape = new long[]{1, tokenIds.length};
      float[] input = new float[tokenIds.length];
      for (int i = 0; i < tokenIds.length; i++) input[i] = tokenIds[i];
      OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape);
      OrtSession.Result result = session.run(Collections.singletonMap("text_input", tensor));
      float[] audio = (float[]) result.get(0).getValue();
      short[] pcm = new short[audio.length];
      for (int i = 0; i < audio.length; i++) pcm[i] = (short) (audio[i] * Short.MAX_VALUE);
      return pcm;
    } catch (Exception e) {
      Log.e(TAG, "TTS synthesis failed", e);
      return new short[0];
    }
  }
}