# Voice Button 0.97: recording duration sources, repair and regression coverage

Date: October 12, 2026. Base: verified 0.96. This is an incremental fix, not a GUI rewrite.

## Root cause

Older Android MP3 recovery counted MPEG audio-frame samples and divided by a hard-coded 16,000 samples/second even when the frame header declared 48,000 Hz. This could inflate the recorded duration threefold. The 0.96 source already replaced that with an exact rate-aware MPEG frame-clock accumulator using 14,112,000 ticks/second, a mathematical common multiple of *all nine MPEG sampling rates*, not a fixed assumed input sampling frequency.

This release retains that implementation and adds broad nine-rate and mixed-rate variable-bitrate/padding tests. A read-only MP3 inspection method makes local playback duration available without touching source audio.

The legacy WAV/PCM paths contained additional incorrect assumptions: dividing WAV byte lengths by constants for 16 kHz mono PCM, overwriting valid WAV headers to 16 kHz during recovery, and configuring a WAV-to-MP3 encoder to 16 kHz regardless of the WAV header. These now use the validated actual input sample rate, channel count, sample width, data-chunk byte length/offset and output encoder rate. A truly missing input sample rate is an error, not an arbitrary fallback. The only deliberate legacy writer default is the known 16 kHz mono 16-bit format of its **old on-disk WAV format**; this is not used as a generic duration assumption. New writers pass their actual capture rate into WAV headers.

For incomplete WAV files, recovery preserves valid format metadata and restores the last unsynchronized PCM bytes without overwriting the audio body. Unsupported channel/sample formats are rejected instead of being encoded using an incompatible LAME configuration. Local recovery detects older grossly inflated MP3 durations using preserved sample indices and actual audio where needed, and stores corrected durations in its existing durable manifest lifecycle.

## Two distinct meanings of duration

The actual playable MP3 duration includes encoded frame padding. Captured microphone sample count divided by its sample rate measures the **capture duration**. For recordings containing many short chunks, these differ meaningfully; do not conflate them. Source-capture sample indices and rates remain available for capture timing, while exported media duration is calculated from encoded frames.

Jetson's 2026-10-12 maintenance corrected historical playable duration metadata in 167 manifests (164 committed recordings, plus received fragments of uncommitted sessions). Every modified original JSON manifest was copied into a restricted backup set before writes. The MP3 files, their checksums, upload receipts and original signed client commit hashes were left unchanged. The updated manifests explicitly explain that old client hashes refer to the original client payload and are **not hashes of the corrected derived fields**.

The server's committed-recording metadata now totals approximately 54.789 hours of playable audio, versus 93.400 hours of previously miscalculated reported duration; its client capture-sample counters total approximately 54.564 hours. A dry-run validation after repair reported zero remaining discrepancies under the declared playback-duration definition. Source files and manifests reside on Jetson, not in this Android repository.

## Tests and acceptance limits

- Exact MPEG sample-rate coverage for MPEG-1, MPEG-2, MPEG-2.5 at 8, 11.025, 12, 16, 22.05, 24, 32, 44.1 and 48 kHz.
- Variable bitrate, MP3 padding, mixed-rate stream frames, truncated frame and read-only inspection.
- WAV mono/stereo, 8-/16-/24-/32-bit PCM, extra RIFF chunks, variable rates, validation of invalid/truncated samples.
- Preserving a valid 44.1 kHz stereo header through recovery; recovering an unsynced PCM tail without changing its rate.
- Legacy inflated duration corrected when valid source sample indices or playable MP3 provide an independent clock.
- Maintain canonical application ID and signing key so an installed phone can receive an in-place upgrade without losing offline recordings.

These tests do not substitute for acceptance on a physical phone with actual microphones and storage interruptions. No remote phone installation is claimed. Do not delete source recordings to perform repair.
