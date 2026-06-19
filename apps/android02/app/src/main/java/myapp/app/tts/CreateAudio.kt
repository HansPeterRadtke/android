// C:\dev\GPT\github\android_02\app\src\main\java\myapp\app\tts\CreateAudio.kt
package myapp.app.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log

fun createAudioFromStyleVector(
    phonemes: String,
    voice: Array<FloatArray>,
    speed: Float,
    session: OrtSession,
): Pair<FloatArray, Int> {

    val TAG = "CreateAudio"
    val MAX_PHONEMES = 510

    // --- DEBUG: show input phonemes before truncation ---
    Log.d(TAG, "phonemes_in='$phonemes' (len=${phonemes.length})")

    val truncated = phonemes.take(MAX_PHONEMES)
    Log.d(TAG, "phonemes_trunc='$truncated' (len=${truncated.length})")

    // Tokenize WITHOUT pads (pads are added below, like kokoro-onnx python).
    val tokens = Tokenizer.tokenize(truncated)
    Log.d(TAG, "tokens_len=${tokens.size}")

    // DEBUG: dump first 64 token IDs so we see what actually hits the model
    val dumpCount = if (tokens.size < 64) tokens.size else 64
    val sb = StringBuilder()
    for (i in 0 until dumpCount) {
        if (i > 0) sb.append(',')
        sb.append(tokens[i])
    }
    Log.d(TAG, "tokens_first=${dumpCount}: [$sb]")

    return createAudioFromTokens(tokens, voice, speed, session)
}

fun createAudioFromTokens(
    tokens: LongArray,
    voice: Array<FloatArray>,
    speed: Float,
    session: OrtSession,
): Pair<FloatArray, Int> {
    val TAG = "CreateAudio"
    val SAMPLE_RATE = 24000

    // Pad with 0 at start and end (model expects pad token id 0).
    val padded = LongArray(tokens.size + 2)
    padded[0] = 0
    System.arraycopy(tokens, 0, padded, 1, tokens.size)
    padded[padded.size - 1] = 0

    Log.d(TAG, "padded_len=${padded.size}, first3=${padded.getOrNull(0)},${padded.getOrNull(1)},${padded.getOrNull(2)}")

    val env = OrtEnvironment.getEnvironment()

    val tokenTensor = OnnxTensor.createTensor(env, arrayOf(padded))
    val styleTensor = OnnxTensor.createTensor(env, voice)
    val speedTensor = OnnxTensor.createTensor(env, floatArrayOf(speed))

    val outputs = session.run(
        mapOf(
            "tokens" to tokenTensor,
            "style" to styleTensor,
            "speed" to speedTensor
        )
    )

    val audio = outputs[0].value as FloatArray

    outputs.close()
    tokenTensor.close()
    styleTensor.close()
    speedTensor.close()

    return Pair(audio, SAMPLE_RATE)
}
