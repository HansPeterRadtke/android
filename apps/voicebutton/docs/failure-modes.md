# Voice Button failure modes

Network loss does not block capture or local durability. Every unconfirmed chunk remains in the ledger and retries after reconciliation. A lost acknowledgement causes an identical duplicate upload, which Jetson accepts only when hash and bytes match. A sequence conflict is never overwritten.

A process or power failure may leave a synchronized raw PCM journal, a partial MP3, or both. PCM wins unless an already-published MP3 is verified against the manifest. Recovery re-encodes PCM once, commits metadata, then deletes the journal. Incomplete MP3 tails are never accepted as complete chunks.

Flash or encoder stalls are isolated from AudioRecord by a two-minute bounded PCM queue. Once that protection is exhausted the app stops with an exact error rather than dropping audio silently. The durability interval is one hundred milliseconds, but hardware and kernel buffers remain outside the application guarantee.

Jetson receiver, final assembly and STT are independent. Receiver acknowledgement precedes STT. STT failures record retry count, next retry time and error, then use bounded exponential backoff. Receiver restart scans persisted manifests and resumes missing transcript work.

Pause and Finish drain blocks already returned by AudioRecord before the final chunk is committed. Unexpected process restart retains auto-resume intent; user Pause, Finish and explicit Close clear it. Explicit Android force-stop and device reboot remain operating-system boundaries.

No local chunk is automatically deleted after remote acknowledgement. The destructive local cleanup action remains explicit, confirmed, and does not delete server folders, audio, or transcripts.


A connection reset during a part upload cannot erase previously acknowledged bytes. If the acknowledgement itself is lost, Android requests the durable offset; Jetson either reports the appended offset or safely accepts an identical duplicate part. Overlap with different bytes, wrong offsets, wrong part hashes and whole-chunk hash mismatches are rejected. Partial files and sidecar metadata survive a Jetson service restart.

A weak connection may still fail repeatedly, but no retry waits longer than five seconds after failure and no successful four-kibibyte part is retransmitted except as a verified idempotent duplicate. The UI progress bar advances by acknowledged bytes inside the current chunk.


A network operation that remains blocked without a durable acknowledgement for twelve seconds is actively disconnected and retried. Jetson's resumable-part status remains authoritative, so retry begins at the server's fsynced byte offset. The manual Retry button replaces a blocked uploader instance. Copy debug exposes both the persisted per-chunk offset and the live worker offset so a disagreement is immediately visible.

Swiping the Android task is a UI action only and does not call service shutdown. The service remains foreground while recording, recovering, alarming, converting or synchronizing. A default-network callback wakes the uploader immediately whenever any usable network appears, while the normal resumable retry loop remains authoritative.

Android force-stop, revoked microphone permission, device shutdown, destroyed hardware, kernel loss and samples never delivered by AudioRecord remain platform boundaries. Every PCM block already returned by AudioRecord continues through the bounded queue and durable journal; the application never intentionally discards such a block.
