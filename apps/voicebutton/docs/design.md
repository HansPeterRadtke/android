# Voice Button chunk architecture

The microphone thread reads fifty-millisecond signed sixteen-bit mono PCM blocks and places them into a bounded queue. It never touches disk or network. A dedicated writer simultaneously journals raw PCM and encodes MP3. Every one hundred milliseconds it fsyncs both files. Every two seconds it closes an independent MP3 chunk, verifies complete frames, publishes rich metadata, and wakes the uploader.

The bounded queue protects capture from temporary flash or encoder stalls without unbounded RAM growth. Its two-minute maximum is only a few mebibytes at speech rates. Exhaustion is a visible hard failure because silently discarding a block is forbidden.

Phone paths are folder based: `folders/<folder>/sessions/<recording>`. Each session contains immutable MP3 chunks, temporary PCM recovery journals only while required, manifest metadata, transcript chunk files, an assembled transcript, and the optional assembled playback MP3. Legacy sessions move into Default.

The `/audio/v2` Jetson receiver streams request bodies directly to temporary files while hashing, fsyncs them, atomically publishes, fsyncs the directory and manifest, and only then acknowledges. Its manifest revision and stable server identity let Android distinguish confirmed server state from a lost response. A restart-safe STT scanner queues every missing or due-retry transcript independently of reception.

Classic Bluetooth HFP uses the communication sink from Android's available communication devices, then verifies the automatically paired source. Classic SCO tries eight-kilohertz input first and resamples to the standard sixteen-kilohertz MP3 output. Built-in and other inputs normally use sixteen kilohertz.


Version 0.12 raises the normal quality target from speech-only sixteen-kilohertz, thirty-two-kilobit MP3 to forty-eight-kilohertz, one-hundred-ninety-two-kilobit MP3. The raw PCM journal preserves the unamplified microphone samples. Only the encoded playback and transfer stream receives adaptive gain, capped at plus twelve decibels with immediate downward adjustment when a block approaches clipping. The live meter reports the unmodified microphone signal in dBFS, so it remains a truthful input diagnostic.

The main screen uses one transmission progress bar and one narrow microphone meter. The folder menu has a blue outline, a visible dropdown label and a version badge so an old installation is immediately obvious.


Version 0.13 separates the two-second audio-storage chunk from the network retry unit. A high-quality chunk is approximately forty-eight kilobytes, but its upload unit is four kibibytes. Each part has its own SHA-256, offset, byte length and fsync acknowledgement. The server keeps an append-only partial file and durable sidecar metadata. After restart, the client asks for the exact durable offset and continues there. The final part triggers whole-chunk SHA-256 verification, atomic publication, manifest update and STT queueing.

The uploader prioritizes audio parts over full-session metadata. It remembers folders already synchronized during the process, retries active audio after two hundred fifty milliseconds, caps exponential retry delay at five seconds, and polls transcript-only work at one-second intervals. The existing whole-chunk endpoint remains available for version 0.12 and earlier clients.
