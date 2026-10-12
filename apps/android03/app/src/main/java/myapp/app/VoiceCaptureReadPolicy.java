package myapp.app;

/** Detect a stalled or failed AudioRecord instead of spinning forever. */
final class VoiceCaptureReadPolicy {
  private final long maxSilenceWithoutSamplesMs;
  private long firstZeroAtMs;

  VoiceCaptureReadPolicy(long maxSilenceWithoutSamplesMs) {
    if (maxSilenceWithoutSamplesMs < 100 || maxSilenceWithoutSamplesMs > 60000)
      throw new IllegalArgumentException("invalid no-data grace period");
    this.maxSilenceWithoutSamplesMs = maxSilenceWithoutSamplesMs;
  }

  void observe(int returnedSamples, long elapsedRealtimeMs) {
    if (returnedSamples > 0) {
      firstZeroAtMs = 0;
      return;
    }
    if (returnedSamples < 0) {
      throw new IllegalStateException("AudioRecord.read failed with code " + returnedSamples);
    }
    if (firstZeroAtMs == 0) {
      firstZeroAtMs = elapsedRealtimeMs;
    } else if (elapsedRealtimeMs - firstZeroAtMs >= maxSilenceWithoutSamplesMs) {
      throw new IllegalStateException("AudioRecord returned no microphone samples for "
          + maxSilenceWithoutSamplesMs + " milliseconds");
    }
  }
}
