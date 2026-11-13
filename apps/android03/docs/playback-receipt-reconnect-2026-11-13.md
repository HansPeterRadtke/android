# Playback confirmation on reconnect — November 13, 2026

The source rollover archive ends with the Android voice client dropping the `playback_completed` message when the socket disconnects before locally rendered audio finishes. The service previously issued `sendText` once from the playback completion callback and ignored `false`.

The new `VoicePlaybackReceipts` queue retains completion receipts while disconnected, coalesces repeated callbacks for the same turn, generation and stream, and flushes after a new server `hello` on the same conversation. It does not replay audio, issue voice commands, or send a receipt for another conversation. Receipt sending follows the service's `sessionLock` acquisition order, avoiding inversion during Activity attachment. A successfully enqueued OkHttp frame is considered sent; the server currently provides no application-level acknowledgement for `playback_completed`, so a socket that accepts a frame and then fails before server processing remains an unsolved delivery gap. This is not durable across Android process death.

Reproduction: disconnect the WebSocket while a short assistant reply is still rendering; allow local playback to complete before reconnection; the old callback silently discards its receipt. With the new queue, the receipt remains pending after a failed send, and the next server greeting triggers exactly one successful send. It is cleared on conversation identity changes.

Offline verification: `./gradlew --offline --no-daemon --console=plain :apps:android03:testDebugUnitTest :apps:android03:assembleDebug`. Focused tests: `VoicePlaybackReceiptsTest`, including failed socket send, retry, ordering, deduplication and isolation. This does not certify a physical Android reconnect or a live Jetson acknowledgement.
