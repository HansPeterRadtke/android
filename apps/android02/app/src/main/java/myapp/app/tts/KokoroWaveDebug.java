package myapp.app.tts;

import android.content.ContentValues;
import android.content.Context;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Debug helper: dumps a float[] (Kokoro PCM) into a mono 16-bit WAV in the Music directory.
 * NO Toasts (to avoid "Can't toast on a thread that has not called Looper.prepare()").
 */
public final class KokoroWaveDebug {

    private KokoroWaveDebug() {}

    public static void saveAudio(float[] audioData, int sampleRate, Context context) {
        if (audioData == null || audioData.length == 0) {
            Log.e("KokoroDebug", "saveAudio: empty audioData");
            return;
        }

        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(new Date());

        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, "KOKORO_DEBUG_" + timeStamp + ".wav");
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav");
        contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC);

        byte[] header = createWavHeader(audioData.length, sampleRate);

        ByteBuffer byteBuffer = ByteBuffer.allocate(audioData.length * 2);
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
        for (float sample : audioData) {
            float v = sample;
            if (v > 1.0f) v = 1.0f;
            if (v < -1.0f) v = -1.0f;
            short pcm = (short) (v * Short.MAX_VALUE);
            byteBuffer.putShort(pcm);
        }

        try {
            Context appCtx = context.getApplicationContext();
            android.content.ContentResolver resolver = appCtx.getContentResolver();
            android.net.Uri uri = resolver.insert(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    contentValues
            );

            if (uri == null) {
                Log.e("KokoroDebug", "saveAudio: failed to create MediaStore URI");
                return;
            }

            try (OutputStream os = resolver.openOutputStream(uri)) {
                if (os == null) {
                    Log.e("KokoroDebug", "saveAudio: openOutputStream returned null");
                    return;
                }

                os.write(header);
                os.write(byteBuffer.array());
                os.flush();
            }

            Log.d("KokoroDebug", "Audio saved to: " + uri.toString());
        } catch (Exception e) {
            Log.e("KokoroDebug", "Error saving audio: " + e.getMessage(), e);
        }
    }

    private static byte[] createWavHeader(int dataSizeFloats, int sampleRate) {
        // dataSizeFloats = number of float samples. PCM16 = 2 bytes per sample.
        int dataSizeBytes = dataSizeFloats * 2;
        int totalDataSize = dataSizeBytes + 36;
        int byteRate = sampleRate * 2; // mono, 16-bit

        byte[] header = new byte[44];

        // "RIFF"
        header[0] = 'R';
        header[1] = 'I';
        header[2] = 'F';
        header[3] = 'F';

        // ChunkSize
        header[4] = (byte) (totalDataSize & 0xff);
        header[5] = (byte) ((totalDataSize >> 8) & 0xff);
        header[6] = (byte) ((totalDataSize >> 16) & 0xff);
        header[7] = (byte) ((totalDataSize >> 24) & 0xff);

        // "WAVE"
        header[8]  = 'W';
        header[9]  = 'A';
        header[10] = 'V';
        header[11] = 'E';

        // "fmt "
        header[12] = 'f';
        header[13] = 'm';
        header[14] = 't';
        header[15] = ' ';

        // Subchunk1Size (16 for PCM)
        header[16] = 16;
        header[17] = 0;
        header[18] = 0;
        header[19] = 0;

        // AudioFormat (1 = PCM)
        header[20] = 1;
        header[21] = 0;

        // NumChannels (1 = mono)
        header[22] = 1;
        header[23] = 0;

        // SampleRate
        header[24] = (byte) (sampleRate & 0xff);
        header[25] = (byte) ((sampleRate >> 8) & 0xff);
        header[26] = (byte) ((sampleRate >> 16) & 0xff);
        header[27] = (byte) ((sampleRate >> 24) & 0xff);

        // ByteRate = SampleRate * NumChannels * BitsPerSample/8
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);

        // BlockAlign = NumChannels * BitsPerSample/8
        header[32] = 2;
        header[33] = 0;

        // BitsPerSample
        header[34] = 16;
        header[35] = 0;

        // "data"
        header[36] = 'd';
        header[37] = 'a';
        header[38] = 't';
        header[39] = 'a';

        // Subchunk2Size = dataSize bytes
        header[40] = (byte) (dataSizeBytes & 0xff);
        header[41] = (byte) ((dataSizeBytes >> 8) & 0xff);
        header[42] = (byte) ((dataSizeBytes >> 16) & 0xff);
        header[43] = (byte) ((dataSizeBytes >> 24) & 0xff);

        return header;
    }
}
