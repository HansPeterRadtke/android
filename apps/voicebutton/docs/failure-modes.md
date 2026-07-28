# Voice Button failure modes

Network loss does not block capture or local durability. Every unconfirmed chunk remains in the ledger and retries after reconciliation. A lost acknowledgement causes an identical duplicate upload, which Jetson accepts only when hash and bytes match. A sequence conflict is never overwritten.

A process or power failure may leave a synchronized raw PCM journal, a partial MP3, or both. PCM wins unless an already-published MP3 is verified against the manifest. Recovery re-encodes PCM once, commits metadata, then deletes the journal. Incomplete MP3 tails are never accepted as complete chunks.

Flash or encoder stalls are isolated from AudioRecord by a two-minute bounded PCM queue. Once that protection is exhausted the app stops with an exact error rather than dropping audio silently. The durability interval is one hundred milliseconds, but hardware and kernel buffers remain outside the application guarantee.

Jetson receiver, final assembly and STT are independent. Receiver acknowledgement precedes STT. STT failures record retry count, next retry time and error, then use bounded exponential backoff. Receiver restart scans persisted manifests and resumes missing transcript work.

Pause and Finish drain blocks already returned by AudioRecord before the final chunk is committed. Unexpected process restart retains auto-resume intent; user Pause, Finish and explicit Close clear it. Explicit Android force-stop and device reboot remain operating-system boundaries.

No local chunk is automatically deleted after remote acknowledgement. The destructive local cleanup action remains explicit, confirmed, and does not delete server folders, audio, or transcripts.
