// C:\dev\GPT\github\android_02\app\src\main\java\myapp\app\tts\StyleLoaderJava.java
package myapp.app.tts;

import android.content.Context;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Kokoro voice style loader.
 *
 * Supports the upstream voice pack:
 * - voices-v1.0.bin (NPZ with entries like af_sarah.npy, am_adam.npy, ...)
 *
 * Also supports a legacy raw float32 format:
 * - voices_XX.bin (float32 little-endian, shape [N, 256])
 *
 * Public API:
 * - getStyleArray(name, tokenLength) -> float[1][256]
 *
 * For voices-v1.0.bin, Kokoro expects the style row to be selected by the (unpadded)
 * token length, so tokenLength is used as the row index (clamped to valid range).
 */
public class StyleLoaderJava {

    private static final String TAG = "StyleLoaderJava";
    private static final int STYLE_DIM = 256;
    private static final String VOICES_NPZ_FILE = "voices-v1.0.bin";

    private final Context context;

    private static final class VoiceData {
        final float[] data;  // flattened row-major
        final int[] shape;   // e.g. [510, 1, 256] or [N, 256]
        final int rowStride; // floats per row for indexing by shape[0]

        VoiceData(float[] data, int[] shape, int rowStride) {
            this.data = data;
            this.shape = shape;
            this.rowStride = rowStride;
        }

        int rows() {
            return (shape != null && shape.length >= 1) ? shape[0] : 0;
        }
    }

    // Cache: voiceName -> flattened data
    private final Map<String, VoiceData> cache = new HashMap<>();

    public StyleLoaderJava(Context context) {
        this.context = context.getApplicationContext();
    }

    private VoiceData loadVoice(String voiceName) throws IOException {
        if (voiceName == null || voiceName.isEmpty()) {
            throw new IOException("voiceName is null/empty");
        }

        // If cached, return immediately
        VoiceData cached = cache.get(voiceName);
        if (cached != null) {
            return cached;
        }

        // Prefer the upstream NPZ pack (voices-v1.0.bin)
        try {
            VoiceData vd = loadVoiceFromNpz(voiceName);
            if (vd != null) {
                cache.put(voiceName, vd);
                return vd;
            }
        } catch (Throwable t) {
            Log.w(TAG, "NPZ voice load failed for '" + voiceName + "': " + t.getMessage());
        }

        // Fallback: legacy raw voices_XX.bin format
        VoiceData vd = loadVoiceFromRawBin(voiceName);
        cache.put(voiceName, vd);
        return vd;
    }

    private VoiceData loadVoiceFromRawBin(String voiceName) throws IOException {
        File modelsDir = context.getExternalFilesDir("models");
        if (modelsDir == null) {
            throw new IOException("getExternalFilesDir(\"models\") returned null");
        }

        String fileName = "voices_" + voiceName + ".bin";
        File voicesFile = new File(modelsDir, fileName);

        Log.d(TAG, "Loading legacy voice '" + voiceName + "' from: " + voicesFile.getAbsolutePath());

        if (!voicesFile.exists()) {
            throw new IOException("Voices file not found: " + voicesFile.getAbsolutePath());
        }

        long length = voicesFile.length();
        if (length <= 0L) {
            throw new IOException("Voices file is empty: " + voicesFile.getAbsolutePath());
        }
        if ((length % 4L) != 0L) {
            throw new IOException("Voices file size is not a multiple of 4 bytes (float32): " + length);
        }

        int totalFloats = (int) (length / 4L);
        if ((totalFloats % STYLE_DIM) != 0) {
            throw new IOException("Total floats " + totalFloats +
                    " is not a multiple of STYLE_DIM=" + STYLE_DIM);
        }

        int numVoices = totalFloats / STYLE_DIM;
        Log.d(TAG, "Voices '" + voiceName + "': totalFloats=" + totalFloats +
                ", numVoices=" + numVoices + ", styleDim=" + STYLE_DIM);

        byte[] bytes = new byte[(int) length];
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(voicesFile))) {
            int read = 0;
            while (read < bytes.length) {
                int r = in.read(bytes, read, bytes.length - read);
                if (r < 0) {
                    throw new IOException("Unexpected EOF while reading voices file");
                }
                read += r;
            }
        }

        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

        float[] data = new float[totalFloats];
        for (int i = 0; i < totalFloats; i++) {
            data[i] = buf.getFloat();
        }
        int[] shape = new int[]{numVoices, STYLE_DIM};
        Log.d(TAG, "Loaded legacy voice '" + voiceName + "' shape=[" + numVoices + "," + STYLE_DIM + "]");
        return new VoiceData(data, shape, STYLE_DIM);
    }

    private VoiceData loadVoiceFromNpz(String voiceName) throws IOException {
        File modelsDir = context.getExternalFilesDir("models");
        if (modelsDir == null) {
            throw new IOException("getExternalFilesDir(\"models\") returned null");
        }
        File npzFile = new File(modelsDir, VOICES_NPZ_FILE);
        if (!npzFile.exists() || npzFile.length() <= 0L) {
            return null;
        }

        String entryName = voiceName.endsWith(".npy") ? voiceName : (voiceName + ".npy");
        try (ZipFile zip = new ZipFile(npzFile)) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                return null;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                NpyFloatArray npy = readNpyFloat32(in);
                if (npy == null || npy.data == null || npy.shape == null || npy.shape.length == 0) {
                    throw new IOException("Invalid npy entry: " + entryName);
                }
                if (npy.shape[npy.shape.length - 1] != STYLE_DIM) {
                    throw new IOException("Unexpected style dim in " + entryName + ": shape=" + shapeToString(npy.shape));
                }
                int rowStride = 1;
                for (int i = 1; i < npy.shape.length; i++) rowStride *= npy.shape[i];
                Log.d(TAG, "Loaded NPZ voice '" + voiceName + "' shape=" + shapeToString(npy.shape) + " rowStride=" + rowStride);
                return new VoiceData(npy.data, npy.shape, rowStride);
            }
        }
    }

    public float[][] getStyleArray(String name, int index) {
        // name like "af_sarah", "am_adam", etc. tokenLength=index is clamped.
        if (name == null || name.isEmpty()) {
            Log.w(TAG, "getStyleArray called with empty name, returning neutral style.");
            return neutralStyle();
        }

        try {
            VoiceData voice = loadVoice(name);
            if (voice == null || voice.rows() <= 0) {
                Log.w(TAG, "Voice data empty for '" + name + "', returning neutral.");
                return neutralStyle();
            }

            int chosen = index;
            if (chosen < 0) chosen = 0;
            if (chosen >= voice.rows()) chosen = voice.rows() - 1;

            int offset = chosen * voice.rowStride;
            if (offset < 0 || offset + STYLE_DIM > voice.data.length) {
                Log.w(TAG, "Voice offset out of range for '" + name + "': idx=" + chosen);
                return neutralStyle();
            }

            float[][] out = new float[1][STYLE_DIM];
            System.arraycopy(voice.data, offset, out[0], 0, STYLE_DIM);
            Log.d(TAG, "Returning style voice='" + name + "', tokenLen=" + index + " -> idx=" + chosen);
            return out;

        } catch (Exception e) {
            Log.e(TAG, "Error in getStyleArray('" + name + "', " + index + "): " + e.getMessage(), e);
            return neutralStyle();
        }
    }

    private float[][] neutralStyle() {
        float[][] neutral = new float[1][STYLE_DIM];
        for (int i = 0; i < STYLE_DIM; i++) {
            neutral[0][i] = 0.0f;
        }
        return neutral;
    }

    // ======== Minimal NPY (float32) reader ========

    private static final class NpyFloatArray {
        final float[] data;
        final int[] shape;

        NpyFloatArray(float[] data, int[] shape) {
            this.data = data;
            this.shape = shape;
        }
    }

    private static NpyFloatArray readNpyFloat32(InputStream in) throws IOException {
        if (in == null) throw new IOException("InputStream is null");

        byte[] magic = new byte[6];
        readFully(in, magic);
        if (magic[0] != (byte) 0x93 ||
                magic[1] != 'N' || magic[2] != 'U' || magic[3] != 'M' || magic[4] != 'P' || magic[5] != 'Y') {
            throw new IOException("Invalid NPY magic");
        }

        int major = readU8(in);
        int minor = readU8(in);

        int headerLen;
        if (major == 1) {
            headerLen = readU16LE(in);
        } else if (major == 2 || major == 3) {
            headerLen = readU32LE(in);
        } else {
            throw new IOException("Unsupported NPY version: " + major + "." + minor);
        }

        byte[] headerBytes = new byte[headerLen];
        readFully(in, headerBytes);
        String header = new String(headerBytes, StandardCharsets.US_ASCII);

        String descr = match1(header, "'descr'\\s*:\\s*'([^']+)'");
        if (descr == null) throw new IOException("NPY header missing descr");
        // Expect little-endian float32.
        if (!descr.endsWith("f4") || (!descr.startsWith("<") && !descr.startsWith("|"))) {
            throw new IOException("Unsupported dtype in NPY: " + descr);
        }

        String fortran = match1(header, "'fortran_order'\\s*:\\s*(True|False)");
        if (fortran == null) throw new IOException("NPY header missing fortran_order");
        if (!"False".equals(fortran)) {
            throw new IOException("Fortran order arrays not supported");
        }

        String shapeStr = match1(header, "'shape'\\s*:\\s*\\(([^\\)]*)\\)");
        if (shapeStr == null) throw new IOException("NPY header missing shape");

        String[] parts = shapeStr.split(",");
        java.util.ArrayList<Integer> dims = new java.util.ArrayList<>();
        for (String p : parts) {
            String s = p.trim();
            if (s.isEmpty()) continue;
            dims.add(Integer.parseInt(s));
        }
        if (dims.isEmpty()) throw new IOException("NPY shape is empty");

        int[] shape = new int[dims.size()];
        long count = 1L;
        for (int i = 0; i < dims.size(); i++) {
            int d = dims.get(i);
            shape[i] = d;
            count *= (long) d;
        }
        if (count > Integer.MAX_VALUE) {
            throw new IOException("NPY array too large: " + count);
        }

        int n = (int) count;
        int dataBytes = n * 4;
        byte[] raw = new byte[dataBytes];
        readFully(in, raw);

        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[n];
        buf.asFloatBuffer().get(out);
        return new NpyFloatArray(out, shape);
    }

    private static int readU8(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new IOException("Unexpected EOF");
        return b & 0xFF;
    }

    private static int readU16LE(InputStream in) throws IOException {
        int b0 = readU8(in);
        int b1 = readU8(in);
        return (b0) | (b1 << 8);
    }

    private static int readU32LE(InputStream in) throws IOException {
        int b0 = readU8(in);
        int b1 = readU8(in);
        int b2 = readU8(in);
        int b3 = readU8(in);
        return (b0) | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private static void readFully(InputStream in, byte[] out) throws IOException {
        int off = 0;
        while (off < out.length) {
            int r = in.read(out, off, out.length - off);
            if (r < 0) throw new IOException("Unexpected EOF");
            off += r;
        }
    }

    private static String match1(String s, String regex) {
        Matcher m = Pattern.compile(regex).matcher(s);
        if (!m.find()) return null;
        return m.group(1);
    }

    private static String shapeToString(int[] shape) {
        if (shape == null) return "null";
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < shape.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(shape[i]);
        }
        sb.append(']');
        return sb.toString();
    }
}
