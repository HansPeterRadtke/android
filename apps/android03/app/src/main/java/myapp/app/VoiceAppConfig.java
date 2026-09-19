package myapp.app;

import android.content.Context;

final class VoiceAppConfig {
  final String websocketUrl;
  final String healthUrl;
  final int sampleRate;
  final int frameMs;
  final long maxQueuedAudioBytes;
  final long connectTimeoutMs;
  final long reconnectBaseMs;
  final long reconnectMaxMs;
  final long reconnectJitterMs;
  final int reconnectMaxExponent;
  final long stopTimeoutMs;
  final int diagnosticsMaxChars;
  final int historyCacheMaxChars;
  final int workerPreviewMaxChars;
  final long heartbeatStaleMs;
  final long mediaAckStaleMs;
  final int playbackQueueChunks;
  final int playbackStartupChunks;
  final long heartbeatCheckMs;
  final long levelUpdateMs;
  final int playerMaxBytes;
  final int manualRecordingMaxBytes;

  private VoiceAppConfig(
      String websocketUrl,
      String healthUrl,
      int sampleRate,
      int frameMs,
      long maxQueuedAudioBytes,
      long connectTimeoutMs,
      long reconnectBaseMs,
      long reconnectMaxMs,
      long reconnectJitterMs,
      int reconnectMaxExponent,
      long stopTimeoutMs,
      int diagnosticsMaxChars,
      int historyCacheMaxChars,
      int workerPreviewMaxChars,
      long heartbeatStaleMs,
      long mediaAckStaleMs,
      int playbackQueueChunks,
      int playbackStartupChunks,
      long heartbeatCheckMs,
      long levelUpdateMs,
      int playerMaxBytes,
      int manualRecordingMaxBytes) {
    this.websocketUrl = websocketUrl;
    this.healthUrl = healthUrl;
    this.sampleRate = sampleRate;
    this.frameMs = frameMs;
    this.maxQueuedAudioBytes = maxQueuedAudioBytes;
    this.connectTimeoutMs = connectTimeoutMs;
    this.reconnectBaseMs = reconnectBaseMs;
    this.reconnectMaxMs = reconnectMaxMs;
    this.reconnectJitterMs = reconnectJitterMs;
    this.reconnectMaxExponent = reconnectMaxExponent;
    this.stopTimeoutMs = stopTimeoutMs;
    this.diagnosticsMaxChars = diagnosticsMaxChars;
    this.historyCacheMaxChars = historyCacheMaxChars;
    this.workerPreviewMaxChars = workerPreviewMaxChars;
    this.heartbeatStaleMs = heartbeatStaleMs;
    this.mediaAckStaleMs = mediaAckStaleMs;
    this.playbackQueueChunks = playbackQueueChunks;
    this.playbackStartupChunks = playbackStartupChunks;
    this.heartbeatCheckMs = heartbeatCheckMs;
    this.levelUpdateMs = levelUpdateMs;
    this.playerMaxBytes = playerMaxBytes;
    this.manualRecordingMaxBytes = manualRecordingMaxBytes;
    validate();
  }

  static VoiceAppConfig from(Context context) {
    return new VoiceAppConfig(
        context.getString(R.string.voice_ws_url),
        context.getString(R.string.voice_health_url),
        context.getResources().getInteger(R.integer.voice_sample_rate),
        context.getResources().getInteger(R.integer.voice_frame_ms),
        context.getResources().getInteger(R.integer.voice_max_queued_audio_bytes),
        context.getResources().getInteger(R.integer.voice_connect_timeout_ms),
        context.getResources().getInteger(R.integer.voice_reconnect_base_ms),
        context.getResources().getInteger(R.integer.voice_reconnect_max_ms),
        context.getResources().getInteger(R.integer.voice_reconnect_jitter_ms),
        context.getResources().getInteger(R.integer.voice_reconnect_max_exponent),
        context.getResources().getInteger(R.integer.voice_stop_timeout_ms),
        context.getResources().getInteger(R.integer.voice_diagnostics_max_chars),
        context.getResources().getInteger(R.integer.voice_history_cache_max_chars),
        context.getResources().getInteger(R.integer.voice_worker_preview_max_chars),
        context.getResources().getInteger(R.integer.voice_heartbeat_stale_ms),
        context.getResources().getInteger(R.integer.voice_media_ack_stale_ms),
        context.getResources().getInteger(R.integer.voice_playback_queue_chunks),
        context.getResources().getInteger(R.integer.voice_playback_startup_chunks),
        context.getResources().getInteger(R.integer.voice_heartbeat_check_ms),
        context.getResources().getInteger(R.integer.voice_level_update_ms),
        context.getResources().getInteger(R.integer.voice_player_max_bytes),
        context.getResources().getInteger(R.integer.voice_manual_recording_max_bytes));
  }

  private void validate() {
    if (!websocketUrl.startsWith("wss://")) throw new IllegalArgumentException("voice_ws_url must use wss");
    if (!healthUrl.startsWith("https://")) throw new IllegalArgumentException("voice_health_url must use https");
    if (sampleRate < 8000 || frameMs < 5 || maxQueuedAudioBytes < sampleRate / 2L) throw new IllegalArgumentException("invalid audio transport config");
    if (connectTimeoutMs <= 0 || reconnectBaseMs <= 0 || reconnectMaxMs < reconnectBaseMs || reconnectJitterMs < 0 || reconnectMaxExponent < 0) throw new IllegalArgumentException("invalid connection config");
    if (stopTimeoutMs <= 0 || diagnosticsMaxChars < 1000 || historyCacheMaxChars < 4000 || workerPreviewMaxChars < 40) throw new IllegalArgumentException("invalid runtime bounds");
    if (heartbeatCheckMs <= 0 || heartbeatStaleMs <= heartbeatCheckMs || mediaAckStaleMs <= heartbeatCheckMs || playbackQueueChunks < 8 || playbackStartupChunks < 1 || playbackStartupChunks >= playbackQueueChunks || levelUpdateMs <= 0) throw new IllegalArgumentException("invalid monitoring config");
    if (playerMaxBytes < sampleRate * 2 || manualRecordingMaxBytes < sampleRate * 2 || manualRecordingMaxBytes > playerMaxBytes) throw new IllegalArgumentException("invalid audio cache bounds");
  }
}
